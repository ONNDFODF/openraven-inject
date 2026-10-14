package xyz.daikirai.inject;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * What the injected DLL calls into.
 *
 * The DLL resolves this class as {@code xyz.daikirai.inject.NativeBridge} from
 * the game's own class loader, so it is loaded by the same loader as the game
 * -- which is what lets the callbacks it registers name game classes at all.
 * The native methods are bound by {@code RegisterNatives} rather than by a JNI
 * symbol name, because the DLL is not named after this class.
 *
 * <p>Order matters and is driven by the DLL: {@code install} registers the
 * hooks and validates them, the DLL then turns on ClassFileLoadHook,
 * {@code retransformLoaded} catches up on classes that were already defined,
 * and {@code start} hands control to the client.
 */
public final class NativeBridge {

    private static volatile Set<String> ourClasses;
    /** False until our own already-loaded classes have been renamed. */
    private static volatile boolean readyToPatch;
    private static HookTransformer transformer;
    private static boolean installed;

    private NativeBridge() {
    }

    public static HookTransformer transformer() {
        return transformer;
    }

    // ------------------------------------------------------------- lifecycle

    public static synchronized void install() {
        if (installed) {
            Log.line("already installed, ignoring");
            return;
        }
        installed = true;

        Log.line("---- openraven inject ----");
        try {
            ourClasses = readOwnJar();

            // The hook table first, then the mapping table, then the accessors.
            //
            // Order matters and it is not obvious. prepare() reads the mapping
            // resource and works out whether the game uses MCP names; if it is
            // left until the first transform -- which is where it used to run --
            // it happens while the JVM is already defining a class and loading
            // MappingBridge then throws ClassCircularityError. Doing it here,
            // before the hook is armed, means the answer is known before
            // anything depends on it.
            InjectHooks.register();
            MappingBridge.prepare();
            Log.line("hooks registered: " + HookRegistry.size() + " on "
                    + HookRegistry.owners().size() + " classes");
            Log.line("mapping: " + (MappingBridge.needsRemapping()
                    ? "the game does not use MCP names, remapping is on"
                    : "the game uses MCP names, no remapping needed"));
            checkCallbacks();
            try {
                InjectAccess.selfCheck();
            } catch (Throwable accessors) {
                // Not fatal: a missing accessor breaks the features that use
                // it, not the injection. The log names which ones.
                Log.throwable("accessor check failed", accessors);
            }

            transformer = new HookTransformer(new Class<?>[0]);
            // Forces the delayed-start listener to register before any game
            // class is transformed, so the client comes up on a tick rather
            // than in the middle of the class that is loading right now.
            Class.forName(Bootstrap.class.getName(), true, NativeBridge.class.getClassLoader());
            warmUp();
        } catch (Throwable t) {
            Log.throwable("install failed -- nothing will be patched", t);
        }
    }

    /**
     * Runs the whole rewriting pipeline once, before the hook is armed.
     *
     * <p>This is InjectMyau's {@code warmUp}, and the reason it is not optional
     * is where the hook runs. {@code ClassFileLoadHook} fires <i>during</i> a
     * class definition, so anything the transformer touches for the first time
     * inside it -- {@code ClassReader}, {@code ClassNode}, the tree package,
     * {@code ClassWriter}, and the verifier for each of them -- is a class being
     * loaded by the game's loader while that loader is already busy defining
     * something else. At best that is a {@code ClassCircularityError} for the
     * class the game wanted; at worst the two loads wait on each other and the
     * game hangs at the point of injection, with nothing in the log to say why.
     *
     * <p>None of those classes are loaded just by naming them: {@code HookTransformer}
     * imports ASM and holds it in fields, and fields are not resolved until they
     * are used. The constructor runs before this and does not touch them either.
     *
     * <p>So one of our own classes is fetched out of the jar and put through
     * {@link #transform}, exactly as a game class will be. Our classes are the
     * right sample: loadable here, correct for this naming in the jar, and
     * cheap. The answer is thrown away -- what is being warmed is the pipeline,
     * not the class.
     */
    private static void warmUp() {
        try {
            String sample = HookRegistry.owners().isEmpty()
                    ? "xyz/daikirai/inject/NativeBridge"
                    : HookRegistry.owners().get(0).replace('.', '/');
            // Our own class first: always present, and it is the one the
            // remapping pass will be asked for by name.
            byte[] bytes = readOwn(sample + ".class");
            if (bytes == null) {
                sample = Bootstrap.class.getName().replace('.', '/');
                bytes = readOwn(sample + ".class");
            }
            if (bytes == null) {
                Log.line("warm-up skipped: no class of ours to read out of the jar");
                return;
            }
            byte[] rewritten = transform(sample, bytes);
            Log.line("warm-up: " + sample + " (" + (bytes.length / 1024) + "k) through the pipeline"
                    + (rewritten == null ? " -- not a hooked class, loader warm" : " -- rewritten"));
        } catch (Throwable failed) {
            // Not fatal: the first transform will do the same work, it will
            // just do it at the worst possible moment. Say so, once, here.
            Log.throwable("warm-up failed -- the first transformed class may deadlock", failed);
        }
    }

    /**
     * The MCP class names that have hooks, for the native side to ask about.
     *
     * Read through {@link Bridge} rather than by the DLL directly, because the
     * DLL holds a reference to Bridge and not to this class.
     */
    public static String[] owners() {
        return HookRegistry.owners().toArray(new String[0]);
    }

    /** Called by the DLL once, after ClassFileLoadHook is on. */
    public static synchronized void retransformLoaded() {
        if (transformer == null) {
            return;
        }
        try {
            InjectAccess.selfCheck();
        } catch (Throwable t) {
            // Not fatal: a missing accessor breaks the features that use it,
            // not the injection itself. The log says which names are wrong.
            Log.throwable("accessor check failed", t);
        }
        try {
            // Ours first, and nothing in the game is patched until they are
            // done. The hook is armed after install has run, so every one of
            // our classes that is already defined went in with its MCP names
            // still on it; retransforming them re-runs the hook, which is where
            // they get renamed. Order matters because the game is live while
            // this walks one class at a time: a hook that lands early is a game
            // method calling into this client, and if the class it reaches is
            // still in MCP names that call is a NoSuchMethodError inside the
            // render thread. The first live run did exactly that -- the main
            // menu rendered through a patched FontRenderer into an un-renamed
            // Hooks and died on getMinecraft.
            Set<String> ours = ourClasses;
            if (ours != null && MappingBridge.needsRemapping()
                    && !MappingBridge.ourClassesAlreadyNamed()) {
                List<String> rename = new ArrayList<String>(ours);
                Log.line("renaming " + rename.size() + " of our own classes first");
                Bridge.retransform(rename.toArray(new String[rename.size()]));
                for (String line : transformer.drain()) {
                    Log.line("  " + line);
                }
            }
            readyToPatch = true;
            List<String> owners = new ArrayList<String>();
            for (String owner : HookRegistry.owners()) {
                for (String name : MappingBridge.ownerNames(owner)) {
                    if (!owners.contains(name)) {
                        owners.add(name);
                    }
                }
            }
            Log.line("asking for " + owners.size() + " already-loaded names");
            Bridge.retransform(owners.toArray(new String[owners.size()]));
            for (String line : transformer.drain()) {
                Log.line("  " + line);
            }
        } catch (Throwable t) {
            readyToPatch = true;
            Log.throwable("retransform failed", t);
        }
    }

    /** Called by the DLL last. */
    public static void start() {
        Bootstrap.requestStart();
    }

    // --------------------------------------------------------------- reports

    public static void flushTransformLog() {
        HookTransformer active = transformer;
        if (active != null && active.hasLog()) {
            for (String line : active.drain()) {
                Log.line("  " + line);
            }
        }
    }

    /**
     * Every hook either landed or it did not, and the difference is the whole
     * reason this build is harder to debug than the Mixin one. Says so once, at
     * the end, instead of leaving it to be inferred from missing behaviour.
     */
    public static void reportUnapplied(String phase) {
        HookTransformer active = transformer;
        if (active == null) {
            return;
        }
        try {
            Set<String> applied = active.appliedKeys();
            Set<String> patchedOwners = active.patchedOwners();
            List<String> missingRequired = new ArrayList<String>();
            List<String> missingOptional = new ArrayList<String>();
            int total = 0;
            int appliedVisible = 0;
            int appliedInvisible = 0;
            Set<String> missedInvisible = new TreeSet<String>();
            for (HookRegistry.Hook hook : HookRegistry.all()) {
                boolean landed = applied.contains(hook.key());
                if (!hook.visible) {
                    // An invisible hook is one whose injection point cannot be
                    // confirmed statically -- it is picked by ordinal, and an
                    // ordinal that shifted is a silent miss rather than a
                    // defect. It is counted on its own line instead of in the
                    // main fraction, which used to read "applied 29/29 ...
                    // optional missing 1" on a green run: the numerator held two
                    // hooks the denominator had left out.
                    if (landed) {
                        appliedInvisible++;
                    } else {
                        missedInvisible.add(hook.key());
                    }
                    continue;
                }
                total++;
                if (landed) {
                    appliedVisible++;
                    continue;
                }
                String line = hook.key()
                        + (patchedOwners.contains(hook.owner) ? "" : "  [class never transformed]");
                if (hook.required) {
                    missingRequired.add(line);
                } else {
                    missingOptional.add(line);
                }
            }
            Log.line("=== hook report (" + phase + ") ===");
            Log.line("applied " + appliedVisible + "/" + total
                    + ", required missing " + missingRequired.size()
                    + ", optional missing " + missingOptional.size());
            if (appliedInvisible > 0 || !missedInvisible.isEmpty()) {
                Log.line("  (the " + (appliedInvisible + missedInvisible.size())
                        + " ordinal-picked hooks are counted separately: "
                        + appliedInvisible + " placed"
                        + (missedInvisible.isEmpty() ? ")" : ", missed: " + missedInvisible + ")"));
            }
            for (String line : missingRequired) {
                Log.line("  MISSING(required) " + line);
            }
            for (String line : missingOptional) {
                Log.line("  MISSING(optional) " + line);
            }
            for (String failure : active.failures()) {
                Log.line("  " + failure);
            }
            Log.line("=== end hook report ===");
            for (String failure : MappingBridge.failures()) {
                Log.line("unresolved name: " + failure);
            }
        } catch (Throwable t) {
            Log.throwable("hook report failed", t);
        }
    }

    // ------------------------------------------------------------- transform

    /**
     * The single entry point for every class the JVM loads while the hook is
     * armed.
     *
     * <p>Our own classes are rewritten here, and Forge cannot do it for us. Its
     * deobfuscation transformer maps the game's notch names up to SRG; this
     * client is written in MCP names, which are not in that table. A class of
     * ours therefore arrives holding {@code getMinecraft} while the game class
     * has been renamed to {@code func_71410_x}, and that is a
     * {@code NoSuchMethodError} the first time a tick runs -- which is exactly
     * how this build used to die. {@link ClassRemapper} applies
     * {@link MappingBridge}, member names as well as class names.
     *
     * <p>Game classes go the other way: the table is also what lets
     * {@link HookTransformer} find MCP-named targets inside bytecode that Forge
     * has already renamed. See {@link MappingBridge#methodNames}.
     */
    public static byte[] transform(String internalName, byte[] classfile) {
        try {
            if (isOurs(internalName)) {
                return remapOurClass(internalName, classfile);
            }
            HookTransformer active = transformer;
            if (active == null || !readyToPatch) {
                // Held off until every one of our classes that was already
                // defined has been renamed. Patching a game class earlier is a
                // call into this client from the game thread, and the client
                // may still be answering in MCP names.
                return classfile;
            }
            byte[] patched = active.patch(internalName, classfile);
            return patched == null ? classfile : patched;
        } catch (Throwable t) {
            Log.line("transform failed for " + internalName + ": " + t);
            return classfile;
        }
    }

    private static boolean isOurs(String internalName) {
        Set<String> known = ourClasses;
        return known != null && known.contains(internalName);
    }

    /**
     * Rewrites this jar's references from MCP names to the names the running
     * game uses.
     *
     * The callback descriptors in the hook table are rewritten as they are
     * inserted; this is the other half, for the client's own method bodies.
     * {@link ClassRemapper} is doing the work because a class's name is not the
     * only thing that needs changing -- every method call, field access and
     * descriptor that mentions a game class does too.
     */
    /** Set while one of ours is being rewritten, so the nested pass stands down. */
    private static final ThreadLocal<Boolean> REMAPPING = new ThreadLocal<Boolean>();

    private static byte[] remapOurClass(String internalName, byte[] classfile) {
        if (!MappingBridge.needsRemapping() || MappingBridge.ourClassesAlreadyNamed()
                || skipRemap(internalName)) {
            return classfile;
        }
        // Defining a class makes the JVM run this hook again -- for the class
        // being defined and for whatever loading it pulls in. The nested pass
        // returns its bytes untouched rather than rewrite a class while the
        // class doing the rewriting is still being defined, which is how a
        // ClassCircularityError happens.
        if (Boolean.TRUE.equals(REMAPPING.get())) {
            return classfile;
        }
        REMAPPING.set(Boolean.TRUE);
        try {
            // The class's own header, for the ancestors a renamed override has
            // to be found on. Reading it twice is cheaper than explaining.
            ClassNode header = new ClassNode();
            new ClassReader(classfile).accept(header,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            MappingBridge.beginClassRemap(internalName, gameAncestors(header));
            try {
                ClassReader reader = new ClassReader(classfile);
                ClassWriter writer = new ClassWriter(0);
                reader.accept(new ClassRemapper(writer, MAPPING_REMAPPER), 0);
                return writer.toByteArray();
            } finally {
                MappingBridge.endClassRemap();
            }
        } catch (Throwable t) {
            Log.throwable("could not remap " + internalName, t);
            dumpForRemapFailure(internalName, classfile);
            return classfile;
        } finally {
            REMAPPING.remove();
        }
    }

    /**
     * Writes the bytes that could not be remapped, next to the log, so a
     * failure that only happens inside a running game can be looked at
     * afterwards instead of guessed at.
     */
    private static void dumpForRemapFailure(String internalName, byte[] classfile) {
        try {
            File directory = Log.file().getParentFile();
            File out = new File(directory, "remapfail-" + internalName.replace('/', '_') + ".class");
            java.io.FileOutputStream stream = new java.io.FileOutputStream(out);
            try {
                stream.write(classfile);
            } finally {
                stream.close();
            }
            Log.line("dumped " + out.getAbsolutePath());
        } catch (Throwable ignored) {
            Log.swallowed(ignored);
        }
    }

    /**
     * The game types a class inherits from, walked by name.
     *
     * <p>Loads the classes to read their headers, which is safe here: they are
     * game classes, defined long before anything of ours is rewritten, and the
     * walk stops at anything that will not load (one of our own superclasses
     * that has not been defined yet, say -- its own rewrite has already run by
     * the time it matters).
     */
    private static java.util.Set<String> gameAncestors(ClassNode node) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<String>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<String>();
        if (node.superName != null) {
            queue.add(node.superName);
        }
        if (node.interfaces != null) {
            queue.addAll(node.interfaces);
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current == null || !found.add(current)) {
                continue;
            }
            try {
                Class<?> type = Class.forName(
                        MappingBridge.mapClass(current.replace('/', '.')),
                        false, MappingBridge.class.getClassLoader());
                Class<?> parent = type.getSuperclass();
                if (parent != null) {
                    queue.add(parent.getName().replace('.', '/'));
                }
                for (Class<?> face : type.getInterfaces()) {
                    queue.add(face.getName().replace('.', '/'));
                }
            } catch (Throwable notLoadableHere) {
                // The table may still carry the name; the walk just stops here.
            }
        }
        return found;
    }

    /** The build-time reobf entry point: rewrite one of our classes now. */
    public static byte[] remapForBuild(String internalName, byte[] classfile) {
        return remapOurClass(internalName, classfile);
    }

    /** Shaded and framework classes, plus the ones the JVM generates itself. */
    private static boolean skipRemap(String internalName) {
        return internalName.startsWith("org/") || internalName.startsWith("java/")
                || internalName.startsWith("net/minecraft/")
                || internalName.startsWith("module-info")
                || internalName.contains("$$Lambda") || internalName.contains("/0x");
    }

    private static final org.objectweb.asm.commons.Remapper MAPPING_REMAPPER =
            new org.objectweb.asm.commons.Remapper() {
                @Override
                public String map(String internalName) {
                    return MappingBridge.mapClass(internalName.replace('/', '.'))
                            .replace('.', '/');
                }

                @Override
                public String mapMethodName(String owner, String name, String descriptor) {
                    return MappingBridge.mapMethodName(owner, name, descriptor);
                }

                @Override
                public String mapFieldName(String owner, String name, String descriptor) {
                    return MappingBridge.mapFieldName(owner, name, descriptor);
                }
            };

    // ------------------------------------------------------------- own jar

    /**
     * Where our jar is.
     *
     * Set by the DLL at injection time, because a class the JVM was handed
     * through DefineClass has no CodeSource -- it was not loaded from a URL, so
     * getProtectionDomain().getCodeSource() is null and the path cannot be
     * discovered from the class itself. Without this the next line throws
     * NullPointerException and every caller treats the jar as unreadable.
     */
    private static String g_jarPath;

    public static void ownJar(String path) {
        g_jarPath = path;
    }

    /**
     * The client jar, as the DLL unpacked it.
     *
     * <p>Public because the assets inside it have to be handed to the game:
     * the game's resource manager reads mod jars through their mod container,
     * and this jar does not have one, so its textures are invisible until
     * something puts them on a path the game looks at. See
     * {@link ClientAssets}.
     */
    public static File jarFile() {
        try {
            return ownJarPath();
        } catch (Throwable failed) {
            Log.throwable("cannot locate our own jar", failed);
            return null;
        }
    }

    private static File ownJarPath() throws Exception {
        if (g_jarPath != null) {
            return new File(g_jarPath);
        }
        String location = NativeBridge.class.getProtectionDomain().getCodeSource()
                .getLocation().toString();
        if (location.startsWith("jar:")) {
            int bang = location.indexOf("!/");
            location = location.substring(4, bang < 0 ? location.length() : bang);
        }
        return new File(new java.net.URI(location));
    }

    private static Set<String> readOwnJar() {
        Set<String> found = new HashSet<String>();
        JarFile jar = null;
        try {
            jar = new JarFile(ownJarPath());
            int shaded = 0;
            for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                name = name.substring(0, name.length() - ".class".length());
                if (name.startsWith("org/") || name.startsWith("net/minecraft/")
                        || name.startsWith("xyz/daikirai/shadow/")) {
                    shaded++;
                    continue;
                }
                found.add(name);
            }
            Log.line("own classes: " + found.size() + " (" + shaded + " shaded, left alone)");
        } catch (Throwable t) {
            Log.throwable("cannot read own jar -- own classes will not be remapped", t);
        } finally {
            close(jar);
        }
        return found;
    }

    private static byte[] readOwn(String entry) {
        JarFile jar = null;
        try {
            jar = new JarFile(ownJarPath());
            JarEntry found = jar.getJarEntry(entry);
            if (found == null) {
                return null;
            }
            InputStream in = jar.getInputStream(found);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    out.write(chunk, 0, read);
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        } finally {
            close(jar);
        }
    }

    private static void close(JarFile jar) {
        if (jar != null) {
            try {
                jar.close();
            } catch (Throwable ignored) {
                Log.swallowed(ignored);
            }
        }
    }

    // ---------------------------------------------------------- callbacks

    /**
     * Confirms that every callback in the hook table exists, is static, and has
     * the descriptor the table claims.
     *
     * This is the check Mixin does at build time and this build cannot: the
     * table names methods by string, so a typo becomes a {@code
     * NoSuchMethodError} thrown from inside a game method the first time the
     * hook fires. Doing it here turns that into one log line before the game is
     * touched.
     */
    private static void checkCallbacks() {
        Map<String, Set<String>> members = new HashMap<String, Set<String>>();
        int checked = 0;
        int wrong = 0;
        for (HookRegistry.Hook hook : HookRegistry.all()) {
            checked++;
            Set<String> known = members.get(hook.callbackOwner);
            if (known == null) {
                known = staticMembersOf(hook.callbackOwner);
                members.put(hook.callbackOwner, known);
            }
            if (known.isEmpty()) {
                wrong++;
                Log.line("BAD CALLBACK " + hook.callbackOwner + " is not in this jar");
                continue;
            }
            String wanted = hook.callbackName + hook.callbackDescriptor;
            // This jar's classes were rewritten to the game's naming at build
            // time, so the callback really in the file carries the remapped
            // descriptor. Comparing only against the table's MCP spelling
            // reported every callback that takes a game type as missing -- 15
            // of 31 on the first Badlion run, all of them present under the
            // name the game actually calls.
            String remapped = hook.callbackName
                    + MappingBridge.remapDescriptor(hook.callbackDescriptor);
            if (!known.contains(wanted) && !known.contains(remapped)) {
                wrong++;
                Log.line("BAD CALLBACK " + hook.owner + "." + hook.method + " -> "
                        + hook.callbackOwner.replace('/', '.') + '.' + wanted
                        + " is not a static method there; " + nearest(known, hook.callbackName));
            }
        }
        Log.line("callbacks checked: " + checked
                + (wrong == 0 ? ", all present" : ", " + wrong + " WRONG"));
    }

    private static String nearest(Set<String> known, String wanted) {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (String member : known) {
            if (!member.startsWith(wanted)) {
                continue;
            }
            out.append(shown == 0 ? "saw " : ", ").append(member);
            shown++;
        }
        return shown == 0 ? "nothing of that name" : out.toString();
    }

    /**
     * Static method signatures of one of our own classes, read out of the jar
     * rather than by loading it: loading {@code RuntimeCallbacks} would run its
     * static initialiser, and it is not meant to be touched until a hook fires.
     * It has no static state today, but relying on that is how this becomes a
     * bug later.
     */
    private static Set<String> staticMembersOf(String internalName) {
        Set<String> known = new HashSet<String>();
        byte[] bytes = readOwn(internalName + ".class");
        if (bytes == null) {
            return known;
        }
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                    | ClassReader.SKIP_FRAMES);
            for (Object raw : node.methods) {
                MethodNode method = (MethodNode) raw;
                if ((method.access & Opcodes.ACC_STATIC) != 0) {
                    known.add(method.name + method.desc);
                }
            }
        } catch (Throwable t) {
            Log.line("cannot read " + internalName + " out of our own jar: " + t);
        }
        return known;
    }


}
