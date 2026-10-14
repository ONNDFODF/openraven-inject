package xyz.daikirai.inject;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Rewrites game classes at load time, and already-loaded ones on request, using
 * the table in {@link HookRegistry}.
 *
 * The injected client has no Mixin at runtime: JVMTI's ClassFileLoadHook hands
 * over the bytes before the class is defined and this class edits them. What
 * Mixin does with a whole injection framework -- locating the injection point,
 * checking the callback against the target's descriptor, computing frames --
 * happens here explicitly, which is why a failure is a log line listing the
 * candidates rather than a build error.
 *
 * <p>Everything that differs between the two builds of this client lives in
 * the {@link HookRegistry} table, not in the client: {@code xyz.daikirai.hook}
 * and every module are the same code in both.
 *
 * <p>Frames are recomputed with {@link ClassWriter#COMPUTE_FRAMES}. The
 * alternative -- patching the existing StackMapTable by hand -- is faster and
 * was the first thing tried, but every conditional the cancel and override
 * flows introduce would need a correct frame, and getting one wrong produces a
 * VerifyError at class definition time with no useful location. Frames are
 * computed for a few dozen classes once, at load.
 */
public final class HookTransformer implements ClassFileTransformer {

    /** Internal name -> MCP owner, so a loaded class is recognised under either name. */
    private final Map<String, String> internalToMcp = new HashMap<String, String>();

    private final Set<String> appliedKeys = Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> patchedOwners = Collections.synchronizedSet(new HashSet<String>());
    private final List<String> failures = Collections.synchronizedList(new ArrayList<String>());
    private final ConcurrentLinkedQueue<String> log = new ConcurrentLinkedQueue<String>();

    /**
     * @param loaded classes the game already has. Not used for placement --
     *               retransformation asks for them again through
     *               {@link NativeBridge#retransformLoaded()} -- but knowing them
     *               lets this report which targets are still to come.
     */
    public HookTransformer(Class<?>[] loaded) {
        for (String mcp : HookRegistry.owners()) {
            for (String name : MappingBridge.ownerNames(mcp)) {
                internalToMcp.put(name, mcp);
            }
        }
        Set<String> found = new HashSet<String>();
        if (loaded != null) {
            for (Class<?> candidate : loaded) {
                if (candidate == null) {
                    continue;
                }
                String mcp = internalToMcp.get(candidate.getName().replace('.', '/'));
                if (mcp != null) {
                    found.add(mcp);
                }
            }
        }
        for (String mcp : HookRegistry.owners()) {
            if (!found.contains(mcp)) {
                log.offer("deferred " + mcp + " -- not loaded yet, will patch as it loads");
            }
        }
    }

    // ---------------------------------------------------------------- reports

    /** The internal names this transformer will accept, for diagnostics. */
    public Set<String> knownOwners() {
        return new HashSet<String>(internalToMcp.keySet());
    }

    public List<String> drain() {
        List<String> taken = new ArrayList<String>();
        for (String line = log.poll(); line != null; line = log.poll()) {
            taken.add(line);
        }
        return taken;
    }

    public boolean hasLog() {
        return !log.isEmpty();
    }

    public Set<String> appliedKeys() {
        synchronized (appliedKeys) {
            return new HashSet<String>(appliedKeys);
        }
    }

    public Set<String> patchedOwners() {
        synchronized (patchedOwners) {
            return new HashSet<String>(patchedOwners);
        }
    }

    public List<String> failures() {
        synchronized (failures) {
            return new ArrayList<String>(failures);
        }
    }

    private void fail(String message) {
        failures.add(message);
        log.offer(message);
    }

    // -------------------------------------------------------------- transform

    @Override
    public byte[] transform(ClassLoader loader, String internalName, Class<?> beingRedefined,
                            ProtectionDomain domain, byte[] original) {
        return patch(internalName, original);
    }

    /**
     * The whole of the rewriting, callable directly so it can be exercised
     * without a JVM tool interface. Returns null when nothing changed, which is
     * what both callers read as "leave it alone".
     */
    public byte[] patch(String internalName, byte[] original) {
        if (internalName == null || original == null) {
            return null;
        }
        String mcp = internalToMcp.get(internalName);
        if (mcp == null) {
            return null;
        }
        List<HookRegistry.Hook> hooks = HookRegistry.forOwner(mcp);
        if (hooks.isEmpty()) {
            return null;
        }
        try {
            ClassNode node = new ClassNode();
            // Frames are dropped on the way in: the writer recomputes them from
            // scratch (COMPUTE_FRAMES), and keeping the originals around leaves
            // the new branches this transformer inserts with a frame that
            // disagrees with the ones already in the method.
            new ClassReader(original).accept(node, ClassReader.SKIP_FRAMES);

            int installed = 0;
            // Kept so that a write failure can name the methods it is looking
            // at, rather than just the class.
            List<MethodNode> touched = new ArrayList<MethodNode>();
            for (HookRegistry.Hook hook : hooks) {
                if (isInitialiser(hook.method)) {
                    // Before super() returns, `this` is uninitialised and the
                    // JVM will not let Java code hold it.
                    log.offer("skipping " + mcp + "." + hook.method
                            + " -- nothing can call a hook before the constructor body");
                    continue;
                }
                MethodNode target = findMethod(node, mcp, hook);
                if (target == null) {
                    fail("no method " + mcp + "." + hook.method
                            + (hook.descriptor == null ? "" : hook.descriptor));
                    continue;
                }
                if (target.instructions == null || target.instructions.size() == 0) {
                    log.offer("skipping " + mcp + "." + hook.method + " -- abstract or native");
                    continue;
                }
                if (hook.flow == HookRegistry.Flow.CANCEL
                        && Type.getReturnType(target.desc).getSort() != Type.VOID) {
                    // A cancel needs a value to return, and pushing one means a
                    // label in the middle of the method's existing instructions,
                    // which splits a basic block. Use OVERRIDE for these: it
                    // needs no branch.
                    fail("REFUSING " + mcp + "." + hook.method
                            + ": cancelling a method that returns a value would need a frame the"
                            + " original class does not have -- use Flow.OVERRIDE instead");
                    continue;
                }
                int placed;
                try {
                    placed = insert(target, hook);
                } catch (RuntimeException bad) {
                    fail("FAILED " + mcp + "." + hook.method + " @" + hook.position + ": "
                            + bad + " at " + firstFrameOf(bad));
                    continue;
                }
                if (placed == 0) {
                    MethodNode delegate = delegateOf(node, target);
                    if (delegate != null) {
                        placed = insert(delegate, hook);
                        if (placed > 0) {
                            log.offer(mcp + "." + hook.method + " forwards to " + delegate.name
                                    + delegate.desc + " -- hooked there instead");
                        }
                    }
                }
                if (placed == 0) {
                    fail("no insertion point for " + mcp + "." + hook.method + " @"
                            + hook.position);
                    continue;
                }
                installed++;
                if (!touched.contains(target)) {
                    touched.add(target);
                }
                appliedKeys.add(hook.key());
            }
            if (installed == 0) {
                return null;
            }

            try {
                byte[] written = write(node, ClassWriter.COMPUTE_FRAMES);
                patchedOwners.add(mcp);
                log.offer("patched " + mcp + " (" + installed + "/" + hooks.size() + ")");
                return written;
            } catch (Throwable frameFailure) {
                // ASM's frame computation refuses some edits that are valid
                // bytecode -- a dropped field write, for instance, leaves it
                // merging an operand stack it cannot reconcile, and it reports
                // that as a negative array size from inside Frame.merge. The
                // same edit writes cleanly with only the stack sizes computed,
                // because every branch this transformer inserts carries its own
                // label and does not depend on the original frames.
                //
                // COMPUTE_FRAMES is still tried first: it is the one that
                // catches a genuinely unbalanced insert, which is the mistake
                // worth failing loudly on.
                try {
                    byte[] written = write(node, ClassWriter.COMPUTE_MAXS);
                    patchedOwners.add(mcp);
                    log.offer("patched " + mcp + " (" + installed + "/" + hooks.size()
                            + ") without recomputing frames -- " + frameFailure);
                    return written;
                } catch (Throwable alsoFailed) {
                    Log.line("cannot write " + mcp + ": " + frameFailure);
                    for (MethodNode method : touched) {
                        dumpMethod(method, frameFailure);
                    }
                    throw alsoFailed;
                }
            }
        } catch (Throwable t) {
            // The selftest reads this off the log; it is the only way to see a
            // failure that the writer raises without any location of its own.
            StringBuilder trace = new StringBuilder("FAILED ").append(mcp).append(": ")
                    .append(t).append(" at ").append(firstFrameOf(t));
            if (Boolean.getBoolean("openraven.trace")) {
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                for (StackTraceElement frame : root.getStackTrace()) {
                    trace.append(System.lineSeparator()).append("    at ").append(frame);
                }
            }
            fail(trace.toString());
            return null;
        }
    }

    /** Writes the tree out, so a failed attempt can be retried with other flags. */
    private byte[] write(ClassNode node, int flags) {
        ClassWriter writer = new ClassWriter(flags) {
            @Override
            protected ClassLoader getClassLoader() {
                // Frames are computed against the game's classes, which our
                // loader can see but the bootstrap loader cannot.
                ClassLoader ours = HookTransformer.class.getClassLoader();
                return ours == null ? super.getClassLoader() : ours;
            }
        };
        node.accept(writer);
        return writer.toByteArray();
    }

    private static String describe(MethodNode method) {        StringBuilder out = new StringBuilder();
        for (AbstractInsnNode insn = method.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) {
                out.append(insn.getOpcode()).append(':');
            }
            out.append(insn.getClass().getSimpleName().replace("InsnNode", "")).append(' ');
            if (insn instanceof MethodInsnNode) {
                out.append(((MethodInsnNode) insn).name)
                        .append(((MethodInsnNode) insn).desc).append(' ');
            }
        }
        return method.name + method.desc + " maxStack=" + method.maxStack
                + " maxLocals=" + method.maxLocals + " :: " + out;
    }

    private static String opcodeName(int opcode) {
        // Only the opcodes this transformer inserts or looks for, plus the
        // returns. A full table would be a dependency on asm-util for a
        // diagnostic string.
        switch (opcode) {
            case Opcodes.NOP: return "NOP";
            case Opcodes.ACONST_NULL: return "ACONST_NULL";
            case Opcodes.ICONST_M1: return "ICONST_M1";
            case Opcodes.ICONST_0: return "ICONST_0";
            case Opcodes.ICONST_1: return "ICONST_1";
            case Opcodes.ICONST_2: return "ICONST_2";
            case Opcodes.ICONST_3: return "ICONST_3";
            case Opcodes.ICONST_4: return "ICONST_4";
            case Opcodes.ICONST_5: return "ICONST_5";
            case Opcodes.LCONST_0: return "LCONST_0";
            case Opcodes.LCONST_1: return "LCONST_1";
            case Opcodes.FCONST_0: return "FCONST_0";
            case Opcodes.FCONST_1: return "FCONST_1";
            case Opcodes.FCONST_2: return "FCONST_2";
            case Opcodes.DCONST_0: return "DCONST_0";
            case Opcodes.DCONST_1: return "DCONST_1";
            case Opcodes.BIPUSH: return "BIPUSH";
            case Opcodes.SIPUSH: return "SIPUSH";
            case Opcodes.LDC: return "LDC";
            case Opcodes.ILOAD: return "ILOAD";
            case Opcodes.LLOAD: return "LLOAD";
            case Opcodes.FLOAD: return "FLOAD";
            case Opcodes.DLOAD: return "DLOAD";
            case Opcodes.ALOAD: return "ALOAD";
            case Opcodes.ISTORE: return "ISTORE";
            case Opcodes.LSTORE: return "LSTORE";
            case Opcodes.FSTORE: return "FSTORE";
            case Opcodes.DSTORE: return "DSTORE";
            case Opcodes.ASTORE: return "ASTORE";
            case Opcodes.POP: return "POP";
            case Opcodes.POP2: return "POP2";
            case Opcodes.DUP: return "DUP";
            case Opcodes.DUP2: return "DUP2";
            case Opcodes.SWAP: return "SWAP";
            case Opcodes.IADD: return "IADD";
            case Opcodes.IMUL: return "IMUL";
            case Opcodes.FMUL: return "FMUL";
            case Opcodes.DMUL: return "DMUL";
            case Opcodes.IRETURN: return "IRETURN";
            case Opcodes.LRETURN: return "LRETURN";
            case Opcodes.FRETURN: return "FRETURN";
            case Opcodes.DRETURN: return "DRETURN";
            case Opcodes.ARETURN: return "ARETURN";
            case Opcodes.RETURN: return "RETURN";
            case Opcodes.GETFIELD: return "GETFIELD";
            case Opcodes.PUTFIELD: return "PUTFIELD";
            case Opcodes.GETSTATIC: return "GETSTATIC";
            case Opcodes.PUTSTATIC: return "PUTSTATIC";
            case Opcodes.INVOKEVIRTUAL: return "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL: return "INVOKESPECIAL";
            case Opcodes.INVOKESTATIC: return "INVOKESTATIC";
            case Opcodes.INVOKEINTERFACE: return "INVOKEINTERFACE";
            case Opcodes.NEW: return "NEW";
            case Opcodes.CHECKCAST: return "CHECKCAST";
            case Opcodes.INSTANCEOF: return "INSTANCEOF";
            case Opcodes.ARRAYLENGTH: return "ARRAYLENGTH";
            case Opcodes.AALOAD: return "AALOAD";
            case Opcodes.IALOAD: return "IALOAD";
            case Opcodes.IFEQ: return "IFEQ";
            case Opcodes.IFNE: return "IFNE";
            case Opcodes.IFNULL: return "IFNULL";
            case Opcodes.IFNONNULL: return "IFNONNULL";
            case Opcodes.GOTO: return "GOTO";
            default: return "op" + opcode;
        }
    }

    private void dumpMethod(MethodNode method, Throwable error) {
        try {
            StringBuilder out = new StringBuilder();
            out.append("--- ").append(method.name).append(method.desc)
                    .append(" maxStack=").append(method.maxStack)
                    .append(" maxLocals=").append(method.maxLocals)
                    .append(" after ").append(error).append(" ---");
            int index = 0;
            for (AbstractInsnNode insn = method.instructions.getFirst();
                 insn != null; insn = insn.getNext()) {
                out.append(System.lineSeparator()).append("  ").append(index).append(": ");
                if (insn.getOpcode() >= 0) {
                    out.append(opcodeName(insn.getOpcode())).append(' ');
                }
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    out.append(call.owner).append('.').append(call.name).append(call.desc);
                } else if (insn instanceof FieldInsnNode) {
                    FieldInsnNode access = (FieldInsnNode) insn;
                    out.append(access.owner).append('.').append(access.name).append(' ')
                            .append(access.desc);
                } else if (insn instanceof VarInsnNode) {
                    out.append(((VarInsnNode) insn).var);
                } else if (insn instanceof JumpInsnNode) {
                    out.append("-> ").append(method.instructions.indexOf(
                            ((JumpInsnNode) insn).label));
                } else if (insn instanceof FrameNode) {
                    out.append("type=").append(((FrameNode) insn).type);
                } else if (insn instanceof LdcInsnNode) {
                    out.append(((LdcInsnNode) insn).cst);
                } else if (insn.getOpcode() < 0) {
                    out.append(insn.getClass().getSimpleName());
                } else {
                    out.append("var=").append(((VarInsnNode) insn).var);
                }
                index++;
            }
            Log.line(out.toString());
        } catch (Throwable ignored) {
            Log.line("could not dump " + method.name + ": " + ignored);
        }
    }

    private static boolean isInitialiser(String name) {
        return "<init>".equals(name) || "<clinit>".equals(name);
    }

    /**
     * The first stack frame inside this class, for a failure raised while
     * placing a hook. Without it a wrapped exception names the symptom and not
     * the line, and every symptom here looks like an index error somewhere.
     */
    private static String firstFrameOf(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (Boolean.getBoolean("openraven.trace")) {
            StringWriter buffer = new StringWriter();
            root.printStackTrace(new PrintWriter(buffer));
            return System.lineSeparator() + buffer;
        }
        for (StackTraceElement frame : root.getStackTrace()) {
            if (frame.getClassName().equals(HookTransformer.class.getName())) {
                return frame.getMethodName() + ":" + frame.getLineNumber();
            }
        }
        return "no frame in HookTransformer";
    }

    // ------------------------------------------------------------ method find

    private MethodNode findMethod(ClassNode node, String mcpOwner, HookRegistry.Hook hook) {
        String[] names = MappingBridge.methodNames(mcpOwner, hook.method);
        Set<String> descriptors = MappingBridge.descriptors(hook.descriptor);
        MethodNode byName = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            if (!matchesAny(names, method.name)) {
                continue;
            }
            if (hook.descriptor != null) {
                if (descriptors.contains(method.desc)) {
                    return method;
                }
                continue;
            }
            matches++;
            byName = method;
        }
        return matches == 1 ? byName : null;
    }

    private static boolean matchesAny(String[] candidates, String actual) {
        for (String candidate : candidates) {
            if (candidate != null && candidate.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an argSpec names an argument slot. {@code this} does not count:
     * slot 0 holds the receiver for the whole method, so it is safe to load
     * anywhere.
     */
    private static boolean readsSlots(String argSpec) {
        for (String piece : argSpec.split(",")) {
            piece = piece.trim();
            if (piece.length() > 0 && !"this".equals(piece)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A target that only forwards to another method of the same class is common
     * enough (overloads, bridge methods) that following one hop before giving
     * up is worth it.
     */
    private MethodNode delegateOf(ClassNode node, MethodNode forwarder) {
        MethodInsnNode call = null;
        for (AbstractInsnNode insn = forwarder.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() < 0) {
                continue;
            }
            if (insn instanceof MethodInsnNode) {
                if (call != null) {
                    return null;
                }
                call = (MethodInsnNode) insn;
                continue;
            }
            if (insn instanceof VarInsnNode || insn instanceof InsnNode
                    || insn instanceof IntInsnNode || insn instanceof LdcInsnNode
                    || insn instanceof TypeInsnNode || insn instanceof LabelNode
                    || insn instanceof LineNumberNode || insn instanceof FrameNode) {
                continue;
            }
            return null;
        }
        if (call == null || !call.owner.equals(node.name)) {
            return null;
        }
        for (MethodNode candidate : node.methods) {
            if (candidate.name.equals(call.name) && candidate.desc.equals(call.desc)
                    && candidate != forwarder) {
                return candidate;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- insert

    private int insert(MethodNode target, HookRegistry.Hook hook) {
        boolean readsArguments = hook.position == HookRegistry.Position.HEAD
                || hook.position == HookRegistry.Position.MODIFY_ARGUMENT;
        if (!readsArguments && !hook.replacesInstruction() && readsSlots(hook.argSpec)) {
            // Neither is guaranteed anywhere else: the slot may have been
            // overwritten, or hold something else entirely by then.
            log.offer("REFUSING " + hook.owner + "." + hook.method + " @" + hook.position
                    + ": only a head hook may read arguments");
            return 0;
        }
        switch (hook.position) {
            case HEAD:
                target.instructions.insert(callback(target, hook));
                return 1;
            case RETURN: {
                List<AbstractInsnNode> returns = returns(target);
                for (AbstractInsnNode insn : returns) {
                    target.instructions.insertBefore(insn, callback(target, hook));
                }
                return returns.size();
            }
            case BEFORE_INVOKE: {
                List<AbstractInsnNode> calls = findCalls(target, hook);
                for (AbstractInsnNode call : calls) {
                    target.instructions.insertBefore(call, callback(target, hook));
                }
                return calls.size();
            }
            case AFTER_INVOKE: {
                List<AbstractInsnNode> calls = findCalls(target, hook);
                for (AbstractInsnNode call : calls) {
                    target.instructions.insert(call, callback(target, hook));
                }
                return calls.size();
            }
            case REPLACE_INVOKE: {
                List<AbstractInsnNode> calls = findCalls(target, hook);
                for (AbstractInsnNode call : calls) {
                    requireVoidCallback(call instanceof MethodInsnNode
                            ? ((MethodInsnNode) call).desc : null, hook);
                    target.instructions.insertBefore(call, callback(target, hook));
                    consumeResult(target, call);
                }
                return calls.size();
            }
            case REPLACE_INVOKE_WITH: {
                List<AbstractInsnNode> calls = findCalls(target, hook);
                for (AbstractInsnNode call : calls) {
                    // The callback leaves the call's own return value on the
                    // stack, so the call itself just goes.
                    target.instructions.insertBefore(call, callback(target, hook));
                    target.instructions.remove(call);
                }
                return calls.size();
            }
            case BEFORE_FIELD: {
                List<AbstractInsnNode> accesses = findFieldAccesses(target, hook);
                for (AbstractInsnNode access : accesses) {
                    target.instructions.insertBefore(access, callback(target, hook));
                }
                return accesses.size();
            }
            case REPLACE_FIELD: {
                List<AbstractInsnNode> accesses = findFieldAccesses(target, hook, FIELD_WRITE);
                for (AbstractInsnNode access : accesses) {
                    dropFieldWrite(target, access, hook);
                }
                return accesses.size();
            }
            case REPLACE_FIELD_WITH: {
                int shape = fieldShape(hook);
                if (shape == FIELD_UNKNOWN) {
                    throw new IllegalStateException("REPLACE_FIELD_WITH on "
                            + hook.targetOwner + "." + hook.targetMember + " "
                            + hook.targetDescriptor + " has callback "
                            + hook.callbackDescriptor + ", which is neither a read shape"
                            + " (the owner in, the field out) nor a write shape (the value"
                            + " in, the value out), so there is no telling which access it"
                            + " stands for");
                }
                List<AbstractInsnNode> accesses = findFieldAccesses(target, hook, shape);
                for (AbstractInsnNode access : accesses) {
                    replaceFieldWith(target, access, hook);
                }
                return accesses.size();
            }
            case MODIFY_CONSTANT: {
                List<AbstractInsnNode> constants = findConstants(target, hook);
                for (AbstractInsnNode constant : constants) {
                    target.instructions.insert(constant, callback(target, hook));
                }
                return constants.size();
            }
            case MODIFY_STORE: {
                List<AbstractInsnNode> stores = findStores(target, hook);
                for (AbstractInsnNode store : stores) {
                    target.instructions.insertBefore(store, callback(target, hook));
                }
                return stores.size();
            }
            case MODIFY_ARGUMENT: {
                InsnList built = modifyArgument(target, hook);
                if (built.size() == 0) {
                    return 0;
                }
                target.instructions.insert(built);
                return 1;
            }
            default:
                return 0;
        }
    }

    /**
     * A replace that discards the original result inserts a pop for it, so a
     * callback that returns something as well would have its result popped too.
     * The mistake is caught here rather than silently swallowed in the game.
     *
     * <p>Only when the original instruction produced something: a void call has
     * nothing to discard, so its callback may return whatever it likes.
     */
    private void requireVoidCallback(String descriptor, HookRegistry.Hook hook) {
        if (descriptor == null || Type.getReturnType(descriptor).getSort() == Type.VOID) {
            return;
        }
        if (!"()V".equals(hook.callbackDescriptor)) {
            throw new IllegalStateException(hook.owner + "." + hook.method + " at "
                    + hook.position + " discards the call's result, so its callback must return"
                    + " void; use " + (hook.position == HookRegistry.Position.REPLACE_INVOKE
                    ? "REPLACE_INVOKE_WITH" : "REPLACE_FIELD_WITH")
                    + " to supply the replacement value instead");
        }
    }

    /**
     * Removes a call or field access that a replace was standing in for, and
     * balances the stack the removal leaves behind.
     *
     * What has to happen depends on what the original instruction consumed, not
     * only on what it produced:
     *
     * <ul>
     *   <li>{@code GETFIELD}/{@code GETSTATIC} read the owner and push the
     *       value. Removing one leaves nothing to clean up when the callback
     *       supplied the value, and a pop of the value's size when it did
     *       not.</li>
     *   <li>{@code PUTFIELD} consumes the owner <i>and</i> the value. A replace
     *       here is a dropped write: the callback consumed the owner itself, so
     *       the value is still on the stack and has to be popped, and the
     *       value's own width decides which pop.</li>
     *   <li>{@code PUTSTATIC} consumes only the value.</li>
     * </ul>
     *
     * <p>A method call is the same idea: its result is popped when the callback
     * did not supply one, and the call is simply dropped when it did.
     */
    private void consumeResult(MethodNode target, AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode) {
            Type produced = Type.getReturnType(((MethodInsnNode) insn).desc);
            if (produced.getSort() == Type.VOID) {
                target.instructions.remove(insn);
                return;
            }
            target.instructions.set(insn, popFor(produced));
            return;
        }
        if (!(insn instanceof FieldInsnNode)) {
            target.instructions.remove(insn);
            return;
        }
        FieldInsnNode access = (FieldInsnNode) insn;
        int opcode = access.getOpcode();
        Type value = Type.getType(access.desc);
        if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) {
            // The read pushed the value and nothing else is left over.
            target.instructions.set(insn, popFor(value));
            return;
        }
        // A dropped write. The callback took the owner off the stack itself, so
        // what remains is the value *and* the owner -- the field write would
        // have consumed both. A static write has no owner.
        InsnList pops = new InsnList();
        pops.add(popFor(value));
        if (opcode == Opcodes.PUTFIELD) {
            pops.add(new InsnNode(Opcodes.POP));
        }
        target.instructions.insertBefore(insn, pops);
        target.instructions.remove(insn);
    }

    /**
     * Replaces a field access with a callback's result.
     *
     * <p>A read: the access pushed the owner's field, the callback takes the
     * owner off the stack and returns what the read should produce, and
     * removing the access leaves exactly that.
     *
     * <p>A write needs no rearrangement at all, which is not obvious until the
     * stack is written out. {@code PUTFIELD} wants the value on top and the
     * owner underneath, and that is also what a one-argument callback wants, so
     * the callback simply runs where it stands and the write stores whatever it
     * returned.
     *
     * <p>The write is deliberately left in place rather than removed with its
     * operands popped. That version is shorter and reads better, but it leaves
     * ASM's frame computation merging an operand stack it cannot reconcile,
     * which surfaces as a negative array size from inside {@code Frame.merge}.
     */
    /** A field access of any kind; the default for hooks that do not replace one. */
    private static final int FIELD_ANY = 0;
    private static final int FIELD_READ = 1;
    private static final int FIELD_WRITE = 2;
    /** A REPLACE_FIELD_WITH callback that fits neither shape. */
    private static final int FIELD_UNKNOWN = -1;

    private static boolean isRead(int opcode) {
        return opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC;
    }

    private static boolean isWrite(int opcode) {
        return opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
    }

    /**
     * Whether a REPLACE_FIELD_WITH hook replaces a read or a write.
     *
     * <p>Read from the callback's own signature, because it is the only thing
     * that says which side of the access the callback is standing on:
     *
     * <ul>
     *   <li>the field's type in, the field's type out -- {@code (F)F} -- is a
     *       write: the callback is handed the value about to be stored and
     *       returns the one that is stored instead;</li>
     *   <li>anything else in, the field's type out -- {@code (Lnet/minecraft/
     *       client/renderer/ItemRenderer;)Lnet/minecraft/item/ItemStack;} -- is
     *       a read: the callback is handed the owner and returns what the read
     *       should produce.</li>
     * </ul>
     *
     * <p>A field whose own type is what its owner is -- {@code Entity.riddenByEntity}
     * -- has the same signature both ways, and that is read as a write. A read
     * hook written that way will not find its access and says so in the hook
     * report rather than patching the wrong one.
     */
    private int fieldShape(HookRegistry.Hook hook) {
        if (hook.targetDescriptor == null || hook.callbackDescriptor == null) {
            return FIELD_UNKNOWN;
        }
        Type value = Type.getType(hook.targetDescriptor);
        Type[] arguments = Type.getArgumentTypes(hook.callbackDescriptor);
        Type result = Type.getReturnType(hook.callbackDescriptor);
        if (arguments.length != 1 || value.getSort() == Type.VOID) {
            return FIELD_UNKNOWN;
        }
        if (arguments[0].equals(value) && result.equals(value)) {
            return FIELD_WRITE;
        }
        if (result.equals(value)) {
            return FIELD_READ;
        }
        return FIELD_UNKNOWN;
    }

    /**
     * Removes a field write, handing the callback the operands the write would
     * have consumed.
     *
     * <p>This is the one field hook whose point is that the write does
     * <i>not</i> happen, which no returned value can express: vanilla's
     * "slow while eating" multiplies {@code moveStrafe} by 0.2 and the client
     * wants that write gone, not replaced by a different number. A callback
     * that returned 0 would leave the player unable to move at all, which is
     * what this used to do.
     *
     * <p>The callback has to take exactly what the write took, because it
     * stands where the write stood: the owner and the value for a
     * {@code PUTFIELD} -- which is the shape a Mixin {@code @Redirect} handler
     * has -- and the value alone for a {@code PUTSTATIC}. Anything else is
     * refused rather than patched, since a mismatch here is a stack the
     * verifier will throw back at a class the game is already running.
     */
    private void dropFieldWrite(MethodNode target, AbstractInsnNode access,
                                HookRegistry.Hook hook) {
        FieldInsnNode write = (FieldInsnNode) access;
        Type value = Type.getType(write.desc);
        Type[] wanted = write.getOpcode() == Opcodes.PUTFIELD
                ? new Type[] { Type.getObjectType(write.owner), value }
                : new Type[] { value };
        // Both sides in the running game's naming: the callback's descriptor is
        // written in MCP names and the instruction in front of us is not --
        // on a Badlion client the owner is an obfuscated name -- so comparing
        // them as written refuses a hook that would have worked.
        Type[] given = Type.getArgumentTypes(MappingBridge.remapDescriptor(hook.callbackDescriptor));
        Type result = Type.getReturnType(MappingBridge.remapDescriptor(hook.callbackDescriptor));
        if (result.getSort() != Type.VOID || given.length != wanted.length) {
            throw new IllegalStateException("REPLACE_FIELD on a write needs a void callback taking "
                    + describe(wanted) + ", but " + hook.callbackName + " is "
                    + hook.callbackDescriptor);
        }
        for (int i = 0; i < wanted.length; i++) {
            // Object stands in for the owner wherever an owner type is wanted:
            // passing a subtype to a parameter declared Object is what the
            // verifier already allows, and a callback that does not care what
            // the owner is should not have to name it.
            if (given[i].equals(wanted[i]) || isObject(given[i])) {
                continue;
            }
            throw new IllegalStateException("REPLACE_FIELD on " + write.owner + "." + write.name
                    + " needs a void callback taking " + describe(wanted) + ", but "
                    + hook.callbackName + " is " + hook.callbackDescriptor);
        }
        target.instructions.insertBefore(write, callback(target, hook));
        target.instructions.remove(write);
    }

    private static boolean isObject(Type type) {
        return type.getSort() == Type.OBJECT && "java/lang/Object".equals(type.getInternalName());
    }
    private static String describe(Type[] types) {
        StringBuilder out = new StringBuilder("(");
        for (Type type : types) {
            out.append(type.getDescriptor());
        }
        return out.append(")V").toString();
    }
    private void replaceFieldWith(MethodNode target, AbstractInsnNode access,
                                  HookRegistry.Hook hook) {
        int opcode = access.getOpcode();
        target.instructions.insertBefore(access, callback(target, hook));
        if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) {
            target.instructions.remove(access);
        }
    }

    private AbstractInsnNode popFor(Type type) {
        switch (type.getSort()) {
            case Type.LONG:
            case Type.DOUBLE:
                return new InsnNode(Opcodes.POP2);
            default:
                return new InsnNode(Opcodes.POP);
        }
    }

    private List<AbstractInsnNode> returns(MethodNode target) {
        List<AbstractInsnNode> found = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode insn = target.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                found.add(insn);
            }
        }
        return found;
    }

    /**
     * Argument index to local slot. Only valid at a head hook, where no
     * argument can have been written yet.
     */
    private int slotOf(MethodNode target, int argument) {
        Type[] arguments = Type.getArgumentTypes(target.desc);
        if (argument < 0 || argument >= arguments.length) {
            throw new IllegalArgumentException("argument " + argument + " does not exist in "
                    + target.name + target.desc);
        }
        boolean isStatic = (target.access & Opcodes.ACC_STATIC) != 0;
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < argument; i++) {
            slot += arguments[i].getSize();
        }
        return slot;
    }

    private InsnList callback(MethodNode target, HookRegistry.Hook hook) {
        InsnList list = new InsnList();
        // A replace consumes what the original instruction would have read,
        // which is already on the stack, so its callback descriptor takes those
        // and nothing is loaded for them. An argSpec on a replace adds what the
        // call itself does not supply -- "this", typically -- and the load goes
        // on top of the stack the call left, so the callback names that
        // parameter last.
        Type[] arguments = Type.getArgumentTypes(target.desc);
        for (String piece : hook.argSpec.split(",")) {
            piece = piece.trim();
            if (piece.length() == 0) {
                continue;
            }
            if ("this".equals(piece)) {
                list.add(new VarInsnNode(Opcodes.ALOAD, 0));
                continue;
            }
            int index = Integer.parseInt(piece);
            Type type = arguments[index];
            list.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), slotOf(target, index)));
        }
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook.callbackOwner, hook.callbackName,
                MappingBridge.remapDescriptor(hook.callbackDescriptor), false));
        switch (hook.flow) {
            case CANCEL:
                cancelWithDefault(list, Type.getReturnType(target.desc));
                break;
            case OVERRIDE:
                overrideWithResult(list, Type.getReturnType(target.desc));
                break;
            case VOID:
            case REPLACE:
            default:
                break;
        }
        return list;
    }

    /**
     * A cancel on a void method: false (or 0) means "stop here", anything else
     * lets the method run.
     *
     * <p>Only void methods use a branch, and the branch is written as a jump
     * past a single return so that the fall-through path is still the original
     * first instruction. A value-returning method cannot be done this way -- a
     * default value has to be pushed before returning, which means a label
     * inside the method's existing instruction stream, and a label there splits
     * a basic block and produces a frame that disagrees with the original
     * StackMapTable. {@link HookRegistry.Builder#build()} rejects that
     * combination; use {@link HookRegistry.Flow#OVERRIDE} instead, which needs
     * no branch at all.
     */
    private void cancelWithDefault(InsnList list, Type returned) {
        LabelNode carryOn = new LabelNode();
        list.add(new JumpInsnNode(Opcodes.IFNE, carryOn));
        list.add(new InsnNode(Opcodes.RETURN));
        list.add(carryOn);
    }

    private AbstractInsnNode defaultReturn(Type returned) {
        switch (returned.getSort()) {
            case Type.VOID:
                return new InsnNode(Opcodes.RETURN);
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                return new InsnNode(Opcodes.ICONST_0);
            case Type.FLOAT:
                return new InsnNode(Opcodes.FCONST_0);
            case Type.LONG:
                return new InsnNode(Opcodes.LCONST_0);
            case Type.DOUBLE:
                return new InsnNode(Opcodes.DCONST_0);
            default:
                return new InsnNode(Opcodes.ACONST_NULL);
        }
    }

    /**
     * Object[] back from the callback: null lets the target run, otherwise
     * element 0 becomes the return value. An array rather than a sentinel value
     * so a hook can force a null or a zero return and mean it.
     */
    private void overrideWithResult(InsnList list, Type returned) {
        LabelNode carryOn = new LabelNode();
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new JumpInsnNode(Opcodes.IFNULL, carryOn));
        if (returned.getSort() == Type.VOID) {
            // A void method has nothing to unbox: a non-null answer only means
            // "do not run the body". Reading element 0 anyway leaves it on the
            // stack at RETURN, which is a verifier error -- and one a running
            // game reports as "the rewritten class does not verify" with no
            // further explanation. EntityPlayerSP.onUpdateWalkingPlayer is
            // exactly this case, and it was the last class refused on a live
            // client.
            list.add(new InsnNode(Opcodes.POP));
            list.add(new InsnNode(Opcodes.RETURN));
        } else {
            list.add(new InsnNode(Opcodes.ICONST_0));
            list.add(new InsnNode(Opcodes.AALOAD));
            list.add(unboxOrCast(returned));
            list.add(new InsnNode(returned.getOpcode(Opcodes.IRETURN)));
        }
        list.add(carryOn);
        list.add(new InsnNode(Opcodes.POP));
    }

    private InsnList unboxOrCast(Type returned) {
        InsnList list = new InsnList();
        String box;
        String method;
        switch (returned.getSort()) {
            case Type.BOOLEAN: box = "java/lang/Boolean";   method = "booleanValue"; break;
            case Type.CHAR:    box = "java/lang/Character"; method = "charValue";    break;
            case Type.BYTE:    box = "java/lang/Byte";      method = "byteValue";    break;
            case Type.SHORT:   box = "java/lang/Short";     method = "shortValue";   break;
            case Type.INT:     box = "java/lang/Integer";   method = "intValue";     break;
            case Type.FLOAT:   box = "java/lang/Float";     method = "floatValue";   break;
            case Type.LONG:    box = "java/lang/Long";      method = "longValue";    break;
            case Type.DOUBLE:  box = "java/lang/Double";    method = "doubleValue";  break;
            case Type.ARRAY:
            case Type.OBJECT:
                list.add(new TypeInsnNode(Opcodes.CHECKCAST,
                        returned.getSort() == Type.ARRAY
                                ? returned.getDescriptor()
                                : returned.getInternalName()));
                return list;
            default:
                return list;
        }
        list.add(new TypeInsnNode(Opcodes.CHECKCAST, box));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, box, method,
                "()" + returned.getDescriptor(), false));
        return list;
    }

    /**
     * MODIFY_ARGUMENT: load the argument, hand it to the callback, store the
     * result back. Inserted at the top of the method, before anything reads it.
     */
    private InsnList modifyArgument(MethodNode target, HookRegistry.Hook hook) {
        InsnList built = new InsnList();
        Type[] arguments = Type.getArgumentTypes(target.desc);
        if (hook.argument < 0 || hook.argument >= arguments.length) {
            log.offer("argument " + hook.argument + " does not exist in " + target.name
                    + target.desc);
            return built;
        }
        Type type = arguments[hook.argument];
        int slot = slotOf(target, hook.argument);
        built.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot));
        built.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook.callbackOwner, hook.callbackName,
                MappingBridge.remapDescriptor(hook.callbackDescriptor), false));
        built.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), slot));
        return built;
    }

    // -------------------------------------------------------------- searching

    private List<AbstractInsnNode> findCalls(MethodNode target, HookRegistry.Hook hook) {
        String[] names = MappingBridge.methodNames(hook.targetOwner, hook.targetMember);
        Set<String> owners = MappingBridge.ownerNames(hook.targetOwner);
        Set<String> descriptors = MappingBridge.descriptors(hook.targetDescriptor);
        List<AbstractInsnNode> found = new ArrayList<AbstractInsnNode>();
        int seen = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            if (!owners.contains(call.owner)) {
                continue;
            }
            if (hook.targetDescriptor != null && !descriptors.contains(call.desc)) {
                continue;
            }
            if (!matchesAny(names, call.name)) {
                continue;
            }
            if (hook.ordinal < 0 || seen == hook.ordinal) {
                found.add(call);
            }
            seen++;
        }
        if (found.isEmpty()) {
            log.offer("  wanted " + Arrays.toString(names)
                    + (hook.targetDescriptor == null ? "" : hook.targetDescriptor)
                    + " on any of " + owners + " in " + target.name + ", saw: " + callsIn(target));
        }
        return found;
    }

    private List<AbstractInsnNode> findFieldAccesses(MethodNode target, HookRegistry.Hook hook) {
        return findFieldAccesses(target, hook, FIELD_ANY);
    }

    /**
     * The field accesses the hook names.
     *
     * <p>{@code shape} narrows them to reads or to writes, and is what
     * {@code REPLACE_FIELD_WITH} needs: a callback that takes the value being
     * stored cannot stand in for a read, and one that takes the owner cannot
     * stand in for a write. Matching both is how this used to work, and it
     * produced a class that did not verify the moment a field was read and
     * written in the same method -- {@code onLivingUpdate} multiplies
     * {@code moveStrafe} by 0.2 rather than assigning to it, so the first access
     * is the read inside that multiplication, and the write-shaped callback was
     * handed a {@code MovementInput} where it wanted a float.
     *
     * <p>The ordinal counts within the narrowed set, which is what the Mixin
     * annotation this came from does: {@code ordinal = 0} on a PUTFIELD is the
     * first write, not the first access of any kind.
     */
    private List<AbstractInsnNode> findFieldAccesses(MethodNode target, HookRegistry.Hook hook,
                                                     int shape) {
        String[] names = MappingBridge.fieldNames(hook.targetOwner, hook.targetMember);
        Set<String> owners = MappingBridge.ownerNames(hook.targetOwner);
        Set<String> descriptors = MappingBridge.descriptors(hook.targetDescriptor);
        List<AbstractInsnNode> found = new ArrayList<AbstractInsnNode>();
        int seen = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (!(insn instanceof FieldInsnNode)) {
                continue;
            }
            FieldInsnNode access = (FieldInsnNode) insn;
            if (!owners.contains(access.owner)) {
                continue;
            }
            if (hook.targetDescriptor != null && !descriptors.contains(access.desc)) {
                continue;
            }
            if (!matchesAny(names, access.name)) {
                continue;
            }
            if (shape == FIELD_READ && !isRead(access.getOpcode())) {
                continue;
            }
            if (shape == FIELD_WRITE && !isWrite(access.getOpcode())) {
                continue;
            }
            if (hook.ordinal < 0 || seen == hook.ordinal) {
                found.add(access);
            }
            seen++;
        }
        if (found.isEmpty()) {
            log.offer("  wanted field " + Arrays.toString(names) + " "
                    + (hook.targetDescriptor == null ? "" : hook.targetDescriptor)
                    + " on any of " + owners + " in " + target.name + ", saw: "
                    + fieldsIn(target));
        }
        return found;
    }

    private List<AbstractInsnNode> findConstants(MethodNode target, HookRegistry.Hook hook) {
        List<AbstractInsnNode> found = new ArrayList<AbstractInsnNode>();
        int seen = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            Object value = constantValue(insn);
            if (value == null || !String.valueOf(value).equals(hook.constant)) {
                continue;
            }
            if (hook.ordinal < 0 || seen == hook.ordinal) {
                found.add(insn);
            }
            seen++;
        }
        if (found.isEmpty()) {
            log.offer("  wanted constant " + hook.constant + " (ordinal " + hook.ordinal + ") in "
                    + target.name + ", saw " + seen + " candidates");
        }
        return found;
    }

    private Object constantValue(AbstractInsnNode insn) {
        if (insn instanceof LdcInsnNode) {
            Object value = ((LdcInsnNode) insn).cst;
            return value instanceof String ? null : value;
        }
        switch (insn.getOpcode()) {
            case Opcodes.ICONST_M1: return Integer.valueOf(-1);
            case Opcodes.ICONST_0:  return Integer.valueOf(0);
            case Opcodes.ICONST_1:  return Integer.valueOf(1);
            case Opcodes.ICONST_2:  return Integer.valueOf(2);
            case Opcodes.ICONST_3:  return Integer.valueOf(3);
            case Opcodes.ICONST_4:  return Integer.valueOf(4);
            case Opcodes.ICONST_5:  return Integer.valueOf(5);
            case Opcodes.LCONST_0:  return Long.valueOf(0L);
            case Opcodes.LCONST_1:  return Long.valueOf(1L);
            case Opcodes.FCONST_0:  return Float.valueOf(0.0F);
            case Opcodes.FCONST_1:  return Float.valueOf(1.0F);
            case Opcodes.FCONST_2:  return Float.valueOf(2.0F);
            case Opcodes.DCONST_0:  return Double.valueOf(0.0D);
            case Opcodes.DCONST_1:  return Double.valueOf(1.0D);
            default: return null;
        }
    }

    private List<AbstractInsnNode> findStores(MethodNode target, HookRegistry.Hook hook) {
        int wanted = storeOpcode(hook.targetDescriptor);
        List<AbstractInsnNode> found = new ArrayList<AbstractInsnNode>();
        int seen = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != wanted) {
                continue;
            }
            if (hook.ordinal < 0 || seen == hook.ordinal) {
                found.add(insn);
            }
            seen++;
        }
        if (found.isEmpty()) {
            log.offer("  wanted store #" + hook.ordinal + " of " + hook.targetDescriptor + " in "
                    + target.name + ", saw " + seen);
        }
        return found;
    }

    private int storeOpcode(String descriptor) {
        if (descriptor == null || descriptor.length() == 0) {
            return Opcodes.ASTORE;
        }
        switch (descriptor.charAt(0)) {
            case 'Z':
            case 'B':
            case 'C':
            case 'S':
            case 'I': return Opcodes.ISTORE;
            case 'J': return Opcodes.LSTORE;
            case 'F': return Opcodes.FSTORE;
            case 'D': return Opcodes.DSTORE;
            default:  return Opcodes.ASTORE;
        }
    }

    private String callsIn(MethodNode target) {
        Set<String> distinct = new LinkedHashSet<String>();
        for (AbstractInsnNode insn = target.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                distinct.add(call.owner + "." + call.name + call.desc);
            }
        }
        return summarise(distinct);
    }

    private String fieldsIn(MethodNode target) {
        Set<String> distinct = new LinkedHashSet<String>();
        for (AbstractInsnNode insn = target.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode access = (FieldInsnNode) insn;
                distinct.add(access.owner + "." + access.name + " " + access.desc);
            }
        }
        return summarise(distinct);
    }

    private String summarise(Set<String> distinct) {
        if (distinct.isEmpty()) {
            return "nothing at all";
        }
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (String entry : distinct) {
            if (shown == 24) {
                out.append(" ... and ").append(distinct.size() - shown).append(" more");
                break;
            }
            out.append(shown == 0 ? "" : ", ").append(entry);
            shown++;
        }
        return out.toString();
    }
}
