package xyz.daikirai.inject;

import java.lang.reflect.Method;

/**
 * The three methods the injected DLL calls, and nothing else.
 *
 * <p>This class exists so that the native side has something to bind to that
 * cannot fail to link. {@link NativeBridge} is the real implementation, but its
 * method signatures mention ASM and every other class in this jar, and 1.8.9
 * Forge runs every class through its deobfuscation transformer on the way in.
 * When that goes wrong the symptom is a class that loads and then reports
 * {@code NoSuchMethodError} for a method that is plainly there -- which is what
 * happened, and it took a while to see.
 *
 * <p>So: no fields, no imports beyond {@code Method}, no static initialiser, and
 * every parameter and return type is a {@code String}, an array, or
 * {@code Object}. Nothing here can be renamed, and nothing here has to be
 * resolved before it can be called.
 *
 * <p>All four methods swallow {@link Throwable} on purpose. They are called from
 * JVMTI callbacks and from a native thread with no Java frame above them, where
 * a propagating exception has nowhere to go and takes the agent down with it.
 */
public final class Bridge {

    private Bridge() {
    }

    /**
     * A guard against re-entering this class while it is already running.
     *
     * <p>Defining a class makes the JVM re-run the class file load hook for it,
     * which lands back here. Without this the second pass would try to define
     * the same class again and the JVM would refuse the duplicate. The inner
     * call returns the bytes untouched, which is what the definition already
     * has.
     */
    private static final ThreadLocal<Boolean> DEFINING = new ThreadLocal<Boolean>();

    /** True while a class of ours is being defined, so nested passes stand down. */
    static boolean isDefining() {
        return Boolean.TRUE.equals(DEFINING.get());
    }

    static void beginDefining() {
        DEFINING.set(Boolean.TRUE);
    }

    static void endDefining() {
        DEFINING.remove();
    }

    /**
     * The transformer. Called for every class the JVM defines once the hook is
     * armed, and again for each class retransformed.
     *
     * @param internalName the class being defined, in internal form
     * @param classfile    its bytes as the JVM has them now
     * @return the bytes to define instead, or the input unchanged
     */
    public static byte[] transform(String internalName, byte[] classfile) {
        if (isDefining()) {
            return classfile;
        }
        try {
            return NativeBridge.transform(internalName, classfile);
        } catch (Throwable failed) {
            Log.throwable("transform failed for " + internalName, failed);
            return classfile;
        }
    }

    /** Reads a class out of our own jar, for the native side to define. */
    public static byte[] readOwnClass(String internalName) {
        try {
            java.io.InputStream in = Bridge.class.getClassLoader()
                    .getResourceAsStream(internalName + ".class");
            if (in == null) {
                return null;
            }
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    out.write(chunk, 0, read);
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (Throwable failed) {
            Log.throwable("cannot read " + internalName + " out of our own jar", failed);
            return null;
        }
    }

    /**
     * Registers the hook table and checks every callback against it, before
     * anything is transformed.
     */
    public static void install() {

        try {
            NativeBridge.ownJar(jarPath);
            InjectHooks.register();
            NativeBridge.install();
        } catch (Throwable failed) {
            Log.throwable("install failed", failed);
        }
    }

    /** Told by the native side before install, because DefineClass has no CodeSource. */
    private static String jarPath;

    /** Called from the DLL with the unpacked jar's path. */
    public static void jar(String path) {
        jarPath = path;
    }

    /**
     * Retransforms the classes that were already loaded when the hook went on.
     *
     * <p>Goes through {@link NativeBridge#retransformLoaded()} rather than
     * calling the native method here. The full version re-checks the accessors,
     * asks for the environment's spelling of every hooked owner, catches up on
     * our own classes when the game renamed them, and drains the transformer's
     * log -- all of which used to be skipped because this method called the
     * native retransform itself with a shorter list of names.
     */
    public static void retransformLoaded() {
        try {
            NativeBridge.retransformLoaded();
        } catch (Throwable failed) {
            Log.throwable("retransformLoaded failed", failed);
        }
    }

    /**
     * Asks the agent to retransform the named classes.
     *
     * <p>Bound by {@code RegisterNatives} from the injected DLL, which is why it
     * has to live on this class rather than on {@link NativeBridge}: the native
     * side holds a reference to the class it defined, and a native method has to
     * be registered against the class that declares it.
     */
    static native void retransform(String[] classNames);

    /** Hands control to the client, on a tick that has a world. */
    public static void start() {
        try {
            NativeBridge.start();
        } catch (Throwable failed) {
            Log.throwable("start failed", failed);
        }
    }

    /**
     * Finds a method by its descriptor rather than by its name.
     *
     * A fallback for the case where something in the game's transformer chain
     * renamed a method on the way in. The descriptor is harder to rewrite by
     * accident than a name is, and searching for it does not depend on knowing
     * what the name became.
     *
     * @return the method, or null
     */
    public static Method find(Class<?> owner, String descriptor) {
        try {
            for (Method method : owner.getDeclaredMethods()) {
                if (describe(method).equals(descriptor)) {
                    return method;
                }
            }
        } catch (Throwable failed) {
            Log.throwable("find failed on " + owner, failed);
        }
        return null;
    }

    /** A method's JNI descriptor, built from its parameter and return types. */
    private static String describe(Method method) {
        StringBuilder out = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) {
            out.append(name(parameter));
        }
        return out.append(')').append(name(method.getReturnType())).toString();
    }

    private static String name(Class<?> type) {
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        if (!type.isPrimitive()) {
            return "L" + type.getName().replace('.', '/') + ";";
        }
        if (type == void.class) {
            return "V";
        }
        if (type == boolean.class) {
            return "Z";
        }
        if (type == byte.class) {
            return "B";
        }
        if (type == char.class) {
            return "C";
        }
        if (type == short.class) {
            return "S";
        }
        if (type == int.class) {
            return "I";
        }
        if (type == long.class) {
            return "J";
        }
        if (type == float.class) {
            return "F";
        }
        return "D";
    }
}
