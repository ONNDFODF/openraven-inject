import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Checks the reobfuscated client against the jars the game actually runs.
 *
 * <p>A rename is only correct if the class it lands on really has that member.
 * The mapping table cannot see that: it is keyed by the member's declaring
 * class, and the flat fallback answers for any class at all when the owner has
 * no entry. That is how {@code RenderWorldLastEvent.partialTicks} -- a field of
 * a Forge class, which is never renamed -- became
 * {@code partialTicks -> field_78223_m} from {@code ModelDragon}, and the game
 * threw {@code NoSuchFieldError} once per frame.
 *
 * <p>This walks every reference in every class of the built client and asks the
 * game's own jars whether the owner (or anything it inherits from) has it. A
 * reference to a class that is not in those jars -- the JDK, LWJGL, ASM, the
 * client itself -- is not checked, because the game is not the authority on it.
 *
 * <p>Usage: {@code CheckRefs <classes-dir-or-jar> <game-jar>[;<game-jar>...]}
 * Exits non-zero if anything does not resolve, and prints what and where.
 */
public final class CheckRefs {

    private static final class Info {
        String name;
        String superName;
        List<String> interfaces = new ArrayList<String>();
        Set<String> methods = new HashSet<String>();
        Set<String> fields = new HashSet<String>();
    }

    private static final Map<String, Info> GAME = new HashMap<String, Info>();
    private static int unresolved;
    private static int reported;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: CheckRefs <classes-dir-or-jar> <game-jar>[;<game-jar>...]");
            System.exit(2);
        }
        for (String jar : args[1].split(";")) {
            if (jar.length() > 0) {
                index(new File(jar));
            }
        }
        System.out.println("references checked against " + GAME.size() + " game classes");

        File target = new File(args[0]);
        if (target.isDirectory()) {
            scanDirectory(target);
        } else {
            scanJar(target);
        }

        if (unresolved > 0) {
            System.out.println("unresolved references: " + unresolved
                    + (reported < unresolved ? " (" + reported + " shown)" : ""));
            System.exit(1);
        }
        System.out.println("all references resolve");
    }

    private static void index(File jar) throws Exception {
        JarFile file = new JarFile(jar);
        try {
            java.util.Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                InputStream in = file.getInputStream(entry);
                try {
                    ClassReader reader = new ClassReader(in);
                    ClassNode node = new ClassNode();
                    reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                            | ClassReader.SKIP_FRAMES);
                    Info info = new Info();
                    info.name = node.name;
                    info.superName = node.superName;
                    if (node.interfaces != null) {
                        info.interfaces.addAll(node.interfaces);
                    }
                    for (MethodNode method : node.methods) {
                        info.methods.add(method.name + method.desc);
                    }
                    for (FieldNode field : node.fields) {
                        info.fields.add(field.name + ":" + field.desc);
                    }
                    GAME.put(node.name, info);
                } finally {
                    in.close();
                }
            }
        } finally {
            file.close();
        }
    }

    private static void scanDirectory(File directory) throws Exception {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                scanDirectory(child);
            } else if (child.getName().endsWith(".class")) {
                InputStream in = new java.io.FileInputStream(child);
                try {
                    check(new ClassReader(in), child.getPath());
                } finally {
                    in.close();
                }
            }
        }
    }

    private static void scanJar(File jar) throws Exception {
        JarFile file = new JarFile(jar);
        try {
            java.util.Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                InputStream in = file.getInputStream(entry);
                try {
                    check(new ClassReader(in), entry.getName());
                } finally {
                    in.close();
                }
            }
        } finally {
            file.close();
        }
    }

    private static void check(ClassReader reader, String where) {
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (MethodNode method : node.methods) {
            for (java.util.ListIterator<org.objectweb.asm.tree.AbstractInsnNode> it =
                         method.instructions.iterator(); it.hasNext(); ) {
                org.objectweb.asm.tree.AbstractInsnNode insn = it.next();
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    require(call.owner, call.name + call.desc, true,
                            node.name + "." + method.name + "  (method " + call.owner + "."
                                    + call.name + call.desc + ")");
                } else if (insn instanceof FieldInsnNode) {
                    FieldInsnNode access = (FieldInsnNode) insn;
                    require(access.owner, access.name + ":" + access.desc, false,
                            node.name + "." + method.name + "  (field " + access.owner + "."
                                    + access.name + ":" + access.desc + ")");
                }
            }
        }
    }

    /** Anything whose owner is not in the game's jars is not ours to judge. */
    private static void require(String owner, String member, boolean method, String where) {
        if (lookup(owner) == null) {
            return;
        }
        // A JDK class is only ever consulted as an ancestor, for the members
        // every object has. Its own references are not this tool's business,
        // and some of them cannot be checked at all: MethodHandle.invoke is
        // signature polymorphic, so the descriptor at the call site is not the
        // one the class declares.
        boolean report = !isJdk(owner);
        Set<String> visited = new HashSet<String>();
        ArrayDeque<String> queue = new ArrayDeque<String>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current == null || !visited.add(current)) {
                continue;
            }
            Info info = lookup(current);
            if (info == null) {
                // The hierarchy leaves everything that can be looked up: a
                // library, or a class the game does not ship. Nothing here can
                // prove the member is absent, so it is not reported -- false
                // alarms are worse than a missed corner.
                return;
            }
            if ((method ? info.methods : info.fields).contains(member)) {
                return;
            }
            if (info.superName != null) {
                queue.add(info.superName);
            }
            queue.addAll(info.interfaces);
        }
        if (!report) {
            return;
        }
        unresolved++;
        if (reported < 60) {
            reported++;
            System.out.println("  no such " + (method ? "method" : "field") + ": " + where);
        }
    }

    /**
     * What is known about a class: the game's jars first, then the JDK this
     * checker runs on.
     *
     * <p>The JDK half is not decoration. Every class in the game reaches
     * {@code java/lang/Object} at the top, so without it every walk ends in
     * unknown territory and nothing is ever reported -- which is exactly how
     * the first version of this tool passed a jar it should have failed.
     */
    private static Info lookup(String internalName) {
        if (internalName == null) {
            return null;
        }
        Info known = GAME.get(internalName);
        if (known != null) {
            return known;
        }
        if (!isJdk(internalName)) {
            return null;
        }
        Info reflected = new Info();
        reflected.name = internalName;
        try {
            Class<?> type = Class.forName(internalName.replace('/', '.'), false,
                    ClassLoader.getSystemClassLoader());
            reflected.superName = type.getSuperclass() == null
                    ? null : type.getSuperclass().getName().replace('.', '/');
            for (Class<?> face : type.getInterfaces()) {
                reflected.interfaces.add(face.getName().replace('.', '/'));
            }
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                reflected.methods.add(method.getName() + descriptor(method));
            }
            // Constructors are referenced like methods -- java/lang/Object.<init>
            // is the first instruction of every constructor in the client -- and
            // they are not in getDeclaredMethods, which is how the first version
            // of this reported 1880 of them as errors.
            for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
                reflected.methods.add("<init>" + descriptor(constructor));
            }
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                reflected.fields.add(field.getName() + ":" + descriptor(field.getType()));
            }
        } catch (Throwable notHere) {
            return null;
        }
        return reflected;
    }

    private static boolean isJdk(String internalName) {
        return internalName.startsWith("java/") || internalName.startsWith("javax/")
                || internalName.startsWith("jdk/") || internalName.startsWith("sun/")
                || internalName.startsWith("com/sun/");
    }

    private static String descriptor(java.lang.reflect.Method method) {
        return descriptor(method.getParameterTypes(), method.getReturnType());
    }

    private static String descriptor(java.lang.reflect.Constructor<?> constructor) {
        return descriptor(constructor.getParameterTypes(), void.class);
    }

    private static String descriptor(Class<?>[] parameters, Class<?> result) {
        StringBuilder out = new StringBuilder("(");
        for (Class<?> parameter : parameters) {
            out.append(descriptor(parameter));
        }
        return out.append(')').append(descriptor(result)).toString();
    }

    private static String descriptor(Class<?> type) {
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

    private CheckRefs() {
    }
}
