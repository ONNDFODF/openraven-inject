package xyz.daikirai.inject;

import xyz.daikirai.shadow.asm.ClassReader;
import xyz.daikirai.shadow.asm.Opcodes;
import xyz.daikirai.shadow.asm.tree.AbstractInsnNode;
import xyz.daikirai.shadow.asm.tree.ClassNode;
import xyz.daikirai.shadow.asm.tree.FieldInsnNode;
import xyz.daikirai.shadow.asm.tree.FrameNode;
import xyz.daikirai.shadow.asm.tree.IntInsnNode;
import xyz.daikirai.shadow.asm.tree.LabelNode;
import xyz.daikirai.shadow.asm.tree.LdcInsnNode;
import xyz.daikirai.shadow.asm.tree.LineNumberNode;
import xyz.daikirai.shadow.asm.tree.MethodInsnNode;
import xyz.daikirai.shadow.asm.tree.MethodNode;
import xyz.daikirai.shadow.asm.tree.TypeInsnNode;
import xyz.daikirai.shadow.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runs the build's own hook table over the game classes a build targets, and
 * says where every hook landed.
 *
 * <p>{@code ClassDump} answers "will the game accept this class" for one class file
 * file at a time; this answers the question that comes first -- "is the hook
 * table still pointing at anything" -- for all of them at once, offline, with
 * the game's real bytecode in hand. A hook that lands on the wrong method is a
 * module that quietly does nothing, and the only place that shows up otherwise
 * is a live run.
 *
 * <p>It is the guard for the rule the README states first: a rename is only
 * correct if the class it lands on has the member. {@code tools/checkrefs}
 * checks the client's own references; this checks the table's injection points,
 * which are strings that no compiler type-checks.
 *
 * <p>Usage, with the client jar, the shaded ASM inside it, and the game jars on
 * the class path:
 * <pre>
 *   java -cp "build/bytecode;dist/OpenRaven-Inject-notch.jar" \
 *        xyz.daikirai.inject.BytecodeCheck dist/OpenRaven-Inject-notch.jar \
 *        --game "&lt;vanilla 1.8.9 jar&gt;"
 * </pre>
 *
 * <p>One naming per run: the client jar decides it, and the game jar has to be
 * the one that client will actually run against -- the mapped jar for mcp, the
 * srg jar for srg, the vanilla jar for notch.
 *
 * <p>Exits non-zero when a <i>required</i> hook could not be placed, so a build
 * can gate on it. An optional hook that misses is reported and not fatal --
 * {@code renderHand} used to be one of those, and it was a real defect hiding
 * behind the word "optional".
 */
public final class BytecodeCheck {

    /** Methods of a class, by name+descriptor, in the jar's order. */
    private static Map<String, MethodNode> methodsOf(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        Map<String, MethodNode> methods = new LinkedHashMap<String, MethodNode>();
        for (MethodNode method : node.methods) {
            methods.put(method.name + method.desc, method);
        }
        return methods;
    }

    /** The callback class, as the reobf left it. */
    private static String callbackOwner() {
        return RuntimeCallbacks.class.getName().replace('.', '/');
    }

    public static void main(String[] args) throws Exception {
        File clientJar = null;
        List<File> gameJars = new ArrayList<File>();
        Set<String> only = new LinkedHashSet<String>();
        for (int i = 0; i < args.length; i++) {
            if ("--game".equals(args[i]) && i + 1 < args.length) {
                for (String jar : args[++i].split(";")) {
                    if (jar.trim().length() > 0) {
                        gameJars.add(new File(jar.trim()));
                    }
                }
            } else if ("--only".equals(args[i]) && i + 1 < args.length) {
                only.add(args[++i].replace('.', '/'));
            } else if (clientJar == null) {
                clientJar = new File(args[i]);
            } else {
                System.err.println("unexpected argument: " + args[i]);
                usage();
            }
        }
        if (clientJar == null || gameJars.isEmpty()) {
            usage();
        }
        for (File jar : gameJars) {
            if (!jar.isFile()) {
                System.err.println("no game jar at " + jar);
                System.exit(2);
            }
        }
        if (!clientJar.isFile()) {
            System.err.println("no client jar at " + clientJar);
            System.exit(2);
        }

        InjectHooks.register();
        MappingBridge.prepare();
        System.out.println("hook table: " + HookRegistry.size() + " hooks on "
                + HookRegistry.owners().size() + " game classes");
        System.out.println("naming: " + naming());
        System.out.println("client jar: " + clientJar.getName());
        StringBuilder jarList = new StringBuilder();
        for (File jar : gameJars) {
            jarList.append(jarList.length() == 0 ? "" : ", ").append(jar.getName());
        }
        System.out.println("game jars: " + jarList);
        System.out.println();

        HookTransformer transformer = new HookTransformer(new Class<?>[0]);

        int seen = 0;
        int missing = 0;
        int patchedClasses = 0;
        int unreadable = 0;
        List<String> lines = new ArrayList<String>();

        List<ZipFile> open = new ArrayList<ZipFile>();
        try {
            for (File jar : gameJars) {
                open.add(new ZipFile(jar));
            }
            for (String mcpOwner : HookRegistry.owners()) {
                // The owner in the table is the MCP name, whatever naming the
                // jar was built for: the reobf does not rewrite a string
                // constant, and MappingBridge is what turns one into the other.
                // The game class itself is read from the game's own jar, under
                // whichever of the two spellings is in there.
                byte[] bytes = null;
                String internalName = null;
                for (String candidate : MappingBridge.ownerNames(mcpOwner)) {
                    if (!only.isEmpty() && !only.contains(candidate)
                            && !only.contains(mcpOwner.replace('.', '/'))) {
                        continue;
                    }
                    bytes = read(open, candidate);
                    if (bytes != null) {
                        internalName = candidate;
                        break;
                    }
                }
                if (internalName == null && only.isEmpty()) {
                    lines.add("  ?? " + mcpOwner + " -- not in any game jar given");
                    unreadable++;
                    continue;
                }
                if (internalName == null) {
                    continue;
                }
                seen++;
                byte[] patched = transformer.patch(internalName, bytes);
                String header = internalName.equals(mcpOwner.replace('.', '/'))
                        ? internalName
                        : internalName + "  (MCP: " + mcpOwner + ")";
                if (patched == null) {
                    lines.add("  " + header + ": nothing applied");
                    continue;
                }
                patchedClasses++;
                List<HookRegistry.Hook> hooks = HookRegistry.forOwner(mcpOwner);
                Set<String> applied = transformer.appliedKeys();
                int landed = 0;
                for (HookRegistry.Hook hook : hooks) {
                    if (applied.contains(hook.key())) {
                        landed++;
                    } else if (hook.required && hook.visible) {
                        lines.add("  !! REQUIRED MISS " + hook.key());
                        missing++;
                    }
                }
                lines.add("  " + header + ": " + landed + "/" + hooks.size()
                        + " hooks placed, " + (bytes.length - patched.length) + " bytes net");
                lines.addAll(placement(bytes, patched));
            }
        } finally {
            for (ZipFile jar : open) {
                jar.close();
            }
        }

        for (String line : lines) {
            System.out.println(line);
        }
        System.out.println();
        System.out.println("classes with hooks: " + seen + ", patched: " + patchedClasses
                + ", not found: " + unreadable + ", required hooks missing: " + missing);
        for (String failure : transformer.failures()) {
            System.out.println("  failure: " + failure);
        }
        for (String failure : MappingBridge.failures()) {
            System.out.println("  unresolved: " + failure);
        }
        System.exit(missing == 0 && unreadable == 0 ? 0 : 1);
    }

    /** The first game jar that has this class. */
    private static byte[] read(List<ZipFile> jars, String internalName) throws Exception {
        for (ZipFile jar : jars) {
            ZipEntry entry = jar.getEntry(internalName + ".class");
            if (entry != null) {
                return read(jar.getInputStream(entry));
            }
        }
        return null;
    }

    private static void usage() {
        System.err.println("usage: BytecodeCheck <client jar> --game <jars> [--only <class>]...");
        System.err.println("  <jars> is a ;-separated list of the game jars for the client's");
        System.err.println("  naming -- minecraft-mapped for mcp, minecraft-srg for srg, the");
        System.err.println("  vanilla 1.8.9 jar for notch.");
        System.exit(2);
    }

    /**
     * Every callback the transformer added to the class, with the instruction it
     * was inserted in front of.
     *
     * <p>This is the part {@code ClassDump} cannot say: "patched 7/7" is a
     * count, and a hook in the wrong method is still a hook that installed. The
     * neighbour is what tells the two apart -- a render hook whose next
     * instruction is not the call it anchors on is a hook that will fire at the
     * wrong moment, or never.
     */
    private static List<String> placement(byte[] original, byte[] patched) {
        List<String> out = new ArrayList<String>();
        Map<String, MethodNode> before = methodsOf(original);
        Map<String, MethodNode> after = methodsOf(patched);
        String owner = callbackOwner();
        for (Map.Entry<String, MethodNode> entry : after.entrySet()) {
            MethodNode was = before.get(entry.getKey());
            if (was == null) {
                continue;
            }
            Set<String> oldCalls = callsTo(was, owner);
            for (AbstractInsnNode insn = entry.getValue().instructions.getFirst();
                 insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!owner.equals(call.owner)) {
                    continue;
                }
                if (oldCalls.contains(call.name)) {
                    // The same callback was already in the original bytes: this
                    // is a second installation, not a new one.
                    oldCalls.remove(call.name);
                    continue;
                }
                out.add("      " + entry.getKey() + " -> " + call.name
                        + "   before: " + describe(next(insn)));
            }
        }
        return out;
    }

    /** The callback names the method already called, so a re-run can be told apart. */
    private static Set<String> callsTo(MethodNode method, String owner) {
        Set<String> names = new LinkedHashSet<String>();
        for (AbstractInsnNode insn = method.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (owner.equals(call.owner)) {
                    names.add(call.name);
                }
            }
        }
        return names;
    }

    /** The next instruction that does something, skipping labels and line numbers. */
    private static AbstractInsnNode next(AbstractInsnNode insn) {
        for (AbstractInsnNode candidate = insn.getNext(); candidate != null;
             candidate = candidate.getNext()) {
            if (candidate instanceof LabelNode || candidate instanceof LineNumberNode
                    || candidate instanceof FrameNode) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static String describe(AbstractInsnNode insn) {
        if (insn == null) {
            return "(end of method)";
        }
        switch (insn.getType()) {
            case AbstractInsnNode.METHOD_INSN: {
                MethodInsnNode call = (MethodInsnNode) insn;
                return call.owner + "." + call.name + call.desc;
            }
            case AbstractInsnNode.FIELD_INSN: {
                FieldInsnNode field = (FieldInsnNode) insn;
                return (insn.getOpcode() == Opcodes.GETFIELD || insn.getOpcode() == Opcodes.GETSTATIC
                        ? "read " : "write ") + field.owner + "." + field.name + " " + field.desc;
            }
            case AbstractInsnNode.VAR_INSN:
                return "local " + ((VarInsnNode) insn).var;
            case AbstractInsnNode.INT_INSN:
                return "push " + ((IntInsnNode) insn).operand;
            case AbstractInsnNode.LDC_INSN:
                return "ldc " + ((LdcInsnNode) insn).cst;
            case AbstractInsnNode.TYPE_INSN:
                return "new/checkcast " + ((TypeInsnNode) insn).desc;
            default:
                return opcodeName(insn.getOpcode());
        }
    }

    private static String opcodeName(int opcode) {
        for (java.lang.reflect.Field field : Opcodes.class.getFields()) {
            try {
                if (field.getType() == int.class && field.getInt(null) == opcode) {
                    return field.getName();
                }
            } catch (IllegalAccessException ignored) {
                // Not reachable: these are public constants.
            }
        }
        return "opcode " + opcode;
    }

    /** Which of the three namings the jar in hand is built for. */
    private static String naming() {
        if (MappingBridge.ourClassesAlreadyNamed()) {
            return "the jar is already in the game's names";
        }
        return "the jar needs remapping as it loads";
    }

    private static byte[] read(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = in.read(chunk)) > 0) {
                out.write(chunk, 0, count);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private BytecodeCheck() {
    }
}
