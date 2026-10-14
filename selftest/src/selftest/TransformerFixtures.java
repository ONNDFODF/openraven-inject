package selftest;

import selftest.fixtures.Recorder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;
import xyz.daikirai.inject.HookRegistry;
import xyz.daikirai.inject.HookTransformer;
import xyz.daikirai.inject.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.List;

import static xyz.daikirai.inject.HookRegistry.Flow;
import static xyz.daikirai.inject.HookRegistry.Position;
import static xyz.daikirai.inject.HookRegistry.hook;

/**
 * Drives HookTransformer over compiled fixtures and checks what the bytecode
 * does when it runs.
 *
 * Each case: register one hook, patch the fixture, verify the patched class,
 * load it, call a method, and assert on both the recorder's log and the value
 * that came back. Verifying the class is the part that has to happen -- the
 * failure mode this guards against is a VerifyError at class definition time,
 * which the transformer itself cannot see.
 */
public final class TransformerFixtures {

    private static final String TARGET = "selftest.fixtures.Target";
    private static final String TARGET_PATH = "selftest/fixtures/Target.class";
    private static final String RECORDER = "selftest.fixtures.Recorder";
    /** What the patched fixture is renamed to before it is defined. */
    private static final String PATCHED = "selftest.patched.Target";

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Log.line("selftest start");
        System.out.println("openraven-inject transformer fixtures");

        head();
        beforeInvoke();
        afterInvoke();
        atReturn();
        cancel();
        cancelKeepsDefaultForValueReturning();
        overrideReturnsCancelled();
        override();
        overrideNullLetsItRun();
        replaceInvokeReturnsCallbackValue();
        replaceInvokeVoidDiscardsResult();
        replaceFieldRead();
        replaceFieldWrite();
        replaceFieldWriteOnReadWriteField();
        replaceFieldDropsWrite();
        replaceFieldRefusesValueShape();
        modifyConstant();
        modifyStore();
        modifyArgument();
        headArgumentReadRefusedElsewhere();
        noMethodReportsFailure();
        noInsertionPointReportsCandidateList();
        missingCallbackIsFound();

        System.out.println();
        System.out.println("checks: " + checks + ", failures: " + failures);
        System.out.println("log: " + Log.file());
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------- the cases

    private static void head() throws Exception {
        run("HEAD inserts before the first instruction",
                hook(TARGET, "entry").at(Position.HEAD)
                        .call(RECORDER, "head", "()V", Flow.VOID).build(),
                "entry", new Class<?>[0], new Object[0],
                "head,head");
    }

    private static void beforeInvoke() throws Exception {
        run("BEFORE_INVOKE lands before the named call",
                hook(TARGET, "callsOther").desc("()V").at(Position.BEFORE_INVOKE)
                        .of(TARGET, "other", "(DD)D")
                        .call(RECORDER, "beforeInvoke", "()V", Flow.VOID).build(),
                "callsOther", new Class<?>[0], new Object[0],
                "beforeInvoke");
    }

    private static void afterInvoke() throws Exception {
        run("AFTER_INVOKE lands after the named call",
                hook(TARGET, "callsOtherAfter").desc("()V").at(Position.AFTER_INVOKE)
                        .of(TARGET, "other", "(DD)D")
                        .call(RECORDER, "afterInvoke", "()V", Flow.VOID).build(),
                "callsOtherAfter", new Class<?>[0], new Object[0],
                "afterInvoke");
    }

    private static void atReturn() throws Exception {
        run("RETURN lands before every return",
                hook(TARGET, "entry").desc("()V").at(Position.RETURN)
                        .call(RECORDER, "atReturn", "()V", Flow.VOID).build(),
                "entry", new Class<?>[0], new Object[0],
                "head,atReturn");
    }

    private static void cancel() throws Exception {
        // The callback returns true, so the body must not run -- which shows up
        // as the Recorder having no "ran" entry. Checking its lastReturned would
        // not work: the body's own recording is what overwrites it.
        run("CANCEL stops the method when the callback says so",
                hook(TARGET, "cancels").desc("()V").at(Position.HEAD)
                        .call(RECORDER, "cancel", "(Ljava/lang/Object;)Z", Flow.CANCEL)
                        .args("this").build(),
                "cancels", new Class<?>[0], new Object[0], "cancel");
    }

    private static void cancelKeepsDefaultForValueReturning() throws Exception {
        // A cancel on a value-returning method has to push a default value
        // before returning, which cannot be done without splitting a basic
        // block, so the transformer must refuse it and point at OVERRIDE.
        HookRegistry.clear();
        HookRegistry.add(hook(TARGET, "valueReturningOverridden").desc("()I").at(Position.HEAD)
                .call(RECORDER, "cancelWithValue", "(Ljava/lang/Object;)Z", Flow.CANCEL)
                .args("this").build());
        Check check = patchAndLoad();
        expect("the cancel was refused", 0, check.applied);
        expectContaining("the refusal points at OVERRIDE", check.failures.toString(),
                "use Flow.OVERRIDE");
    }

    private static void overrideReturnsCancelled() throws Exception {
        // The same hook as OVERRIDE, which is what the table uses for a method
        // that returns a value. A null array here means "let it run".
        Object[] result = run("OVERRIDE with a null array lets a value-returning method run",
                hook(TARGET, "valueReturningOverridden").desc("()I").at(Position.HEAD)
                        .call(RECORDER, "overrideNull", "(Ljava/lang/Object;)[Ljava/lang/Object;",
                                Flow.OVERRIDE)
                        .args("this").build(),
                "valueReturningOverridden", new Class<?>[0], new Object[0], "overrideNull");
        expect("the method still returned its own 5", Integer.valueOf(5), result[0]);
    }

    private static void override() throws Exception {
        Object[] result = run("OVERRIDE forces the return value from the callback",
                hook(TARGET, "valueReturningOverridden").at(Position.HEAD)
                        .call(RECORDER, "override", "(Ljava/lang/Object;)[Ljava/lang/Object;",
                                Flow.OVERRIDE)
                        .args("this").build(),
                "valueReturningOverridden", new Class<?>[0], new Object[0], "override");
        expect("overridden int return is 42", Integer.valueOf(42), result[0]);
    }

    private static void overrideNullLetsItRun() throws Exception {
        Object[] result = run("OVERRIDE with a null array lets the method run",
                hook(TARGET, "valueReturningUntouched").at(Position.HEAD)
                        .call(RECORDER, "overrideNull", "(Ljava/lang/Object;)[Ljava/lang/Object;",
                                Flow.OVERRIDE)
                        .args("this").build(),
                "valueReturningUntouched", new Class<?>[0], new Object[0], "overrideNull");
        expect("untouched int return is still 5", Integer.valueOf(5), result[0]);
    }

    private static void replaceInvokeReturnsCallbackValue() throws Exception {
        // callsOther passes 1.5 and 2.5 to other, which would return 4.0. The
        // descriptor matters: callsOtherAfter calls the same method with the
        // same arguments, so without it both would be rewritten.
        run("REPLACE_INVOKE_WITH returns the callback's value instead",
                hook(TARGET, "callsOther").desc("()V").at(Position.REPLACE_INVOKE_WITH)
                        .of(TARGET, "other", "(DD)D")
                        .call(RECORDER, "replaceCall", "(Ljava/lang/Object;DD)D", Flow.VOID)
                        .build(),
                "callsOther", new Class<?>[0], new Object[0], "replaceCall");

        // The sibling method makes the same call and must be left alone: it
        // still adds 1.5 and 2.5, and no callback fires.
        Object[] sibling = run("REPLACE_INVOKE leaves the same call in another method alone",
                hook(TARGET, "callsOther").desc("()V").at(Position.REPLACE_INVOKE_WITH)
                        .of(TARGET, "other", "(DD)D")
                        .call(RECORDER, "replaceCall", "(Ljava/lang/Object;DD)D", Flow.VOID)
                        .build(),
                "other", new Class<?>[]{double.class, double.class},
                new Object[]{Double.valueOf(1.5D), Double.valueOf(2.5D)}, "");
        expect("the sibling call still adds the two arguments", Double.valueOf(4.0D), sibling[0]);
    }

    private static void replaceInvokeVoidDiscardsResult() throws Exception {
        // takesInt is void, so the call pushes nothing and the callback has
        // nothing to discard. Its argument is on the stack, so the callback
        // takes it.
        Object[] result = run("REPLACE_INVOKE on a void call hands the callback its argument",
                hook(TARGET, "callsTakesInt").desc("()V").at(Position.REPLACE_INVOKE)
                        .of(TARGET, "takesInt", "(I)V")
                        .call(RECORDER, "replaceCallVoid", "(Ljava/lang/Object;I)V", Flow.VOID)
                        .build(),
                "callsTakesInt", new Class<?>[0], new Object[0], "replaceCallVoid:11");
        Object remembered = result[1];
        expect("the callback saw the call's argument", Integer.valueOf(11), remembered);
    }

    private static void replaceFieldRead() throws Exception {
        // Target.readsField returns the field; the hook replaces the read.
        Object[] result = run("REPLACE_FIELD_WITH on a read returns the callback's value",
                hook(TARGET, "readsField").desc("()F").at(Position.REPLACE_FIELD_WITH)
                        .of(TARGET, "value", "F")
                        .call(RECORDER, "replaceFieldRead", "(Ljava/lang/Object;)F", Flow.VOID)
                        .build(),
                "readsField", new Class<?>[0], new Object[0], "replaceFieldRead");
        expect("replaced field read returned 7.5", Float.valueOf(7.5F), result[0]);
    }

    /**
     * The read-then-write shape, and the one that made a live class fail to
     * verify: onLivingUpdate multiplies moveStrafe by 0.2, so the field is read
     * before it is written and the first access of any kind is that read. A
     * write-shaped callback placed there is handed the MovementInput where it
     * wants a float, and the class that comes out does not verify -- silently,
     * because nothing loads it until a world does.
     */
    /**
     * The write is removed, not replaced: the field keeps what it had, and the
     * callback still sees the value vanilla was about to store. This is the
     * shape {@code onLivingUpdate}'s slowdown redirect has, and the reason a
     * returned value cannot express it.
     */
    private static void replaceFieldDropsWrite() throws Exception {
        Object[] result = run("REPLACE_FIELD drops the write",
                hook(TARGET, "multipliesField").desc("()F").at(Position.REPLACE_FIELD)
                        .of(TARGET, "value", "F")
                        .ordinal(0)
                        .call(RECORDER, "replaceFieldDroppedWrite",
                                "(Ljava/lang/Object;F)V", Flow.VOID)
                        .build(),
                "multipliesField", new Class<?>[0], new Object[0],
                "replaceFieldDroppedWrite:8.0");
        expect("the method reads the field it kept", Float.valueOf(2.0F), result[0]);
        Object instance = result[2];
        if (instance == null) {
            return;
        }
        java.lang.reflect.Field field = instance.getClass().getField("value");
        expect("the write did not happen", Float.valueOf(2.0F), field.get(instance));
    }

    /**
     * A value-shaped callback cannot stand where a write was: the owner is on
     * the stack where it wants the value. Refusing beats a class the game's
     * verifier throws back at a running world.
     */
    private static void replaceFieldRefusesValueShape() throws Exception {
        HookRegistry.clear();
        HookRegistry.add(hook(TARGET, "multipliesField").desc("()F").at(Position.REPLACE_FIELD)
                .of(TARGET, "value", "F").ordinal(0)
                .call(RECORDER, "replaceFieldWrite", "(F)F", Flow.VOID).build());
        Check check = patchAndLoad();
        expect("the hook was refused", 0, check.applied);
        expectContaining("the refusal explains why", check.log, "needs a void callback taking");
    }
    private static void replaceFieldWriteOnReadWriteField() throws Exception {
        Object[] result = run("REPLACE_FIELD_WITH lands on the write when the field is read first",
                hook(TARGET, "multipliesField").desc("()F").at(Position.REPLACE_FIELD_WITH)
                        .of(TARGET, "value", "F")
                        .ordinal(0)
                        .call(RECORDER, "replaceFieldWrite", "(F)F", Flow.VOID)
                        .build(),
                "multipliesField", new Class<?>[0], new Object[0], "replaceFieldWrite:8.0");
        expect("the callback saw the multiplied value",
                Float.valueOf(8.0F), Float.valueOf(Recorder.lastFloat()));
        expect("the method returns what the callback stored", Float.valueOf(9.0F), result[0]);
        Object instance = result[2];
        if (instance == null) {
            return;
        }
        java.lang.reflect.Field field = instance.getClass().getField("value");
        expect("the field holds what the callback returned", Float.valueOf(9.0F), field.get(instance));
    }
    private static void replaceFieldWrite() throws Exception {
        // A replaced write: the callback sees the value vanilla was about to
        // store, and the field gets what the callback returned. The write still
        // happens -- dropping it is what leaves ASM's frame computation with an
        // operand stack it cannot reconcile.
        Object[] result = run("REPLACE_FIELD_WITH on a write rewrites the stored value",
                hook(TARGET, "writesField").desc("()V").at(Position.REPLACE_FIELD_WITH)
                        .of(TARGET, "value", "F")
                        .call(RECORDER, "replaceFieldWrite", "(F)F", Flow.VOID)
                        .build(),
                "writesField", new Class<?>[0], new Object[0], "replaceFieldWrite:4.25");
        expect("the callback saw the value about to be stored",
                Float.valueOf(4.25F), Float.valueOf(Recorder.lastFloat()));
        Object instance = result[2];
        if (instance == null) {
            return;
        }
        java.lang.reflect.Field field = instance.getClass().getField("value");
        expect("the field holds what the callback returned", Float.valueOf(5.25F),
                field.get(instance));
    }

    private static void modifyConstant() throws Exception {
        Object[] result = run("MODIFY_CONSTANT replaces the matched constant",
                hook(TARGET, "constant").at(Position.MODIFY_CONSTANT)
                        .constant("14.0").ordinal(0)
                        .call(RECORDER, "modifyConstant", "(F)F", Flow.REPLACE).build(),
                "constant", new Class<?>[0], new Object[0], "modifyConstant:14.0");
        expect("constant became 21.0", Float.valueOf(21.0F), result[0]);
    }

    private static void modifyStore() throws Exception {
        // storesLocal does `float f = 1.5F; return f * 2.0F;`. Replacing the
        // store's value makes it 3.5 * 2.0.
        Object[] result = run("MODIFY_STORE replaces the value being stored",
                hook(TARGET, "storesLocal").at(Position.MODIFY_STORE)
                        .of(TARGET, "value", "F").ordinal(0)
                        .call(RECORDER, "modifyStore", "(F)F", Flow.REPLACE).build(),
                "storesLocal", new Class<?>[0], new Object[0], "modifyStore:1.5");
        expect("the stored value was 3.5, result 7.0", Float.valueOf(7.0F), result[0]);
    }

    private static void modifyArgument() throws Exception {
        Object[] result = run("MODIFY_ARGUMENT rewrites the argument",
                hook(TARGET, "takesString").at(Position.MODIFY_ARGUMENT)
                        .argument(0)
                        .call(RECORDER, "modifyArgument",
                                "(Ljava/lang/String;)Ljava/lang/String;", Flow.REPLACE).build(),
                "takesString", new Class<?>[]{String.class}, new Object[]{"in"},
                "modifyArgument:in");
        expect("the method saw the rewritten argument", "saw:in+patched", result[0]);
    }

    private static void headArgumentReadRefusedElsewhere() throws Exception {
        // A RETURN hook that reads an argument slot has no guarantee the slot
        // still holds it, so the transformer must refuse rather than guess.
        HookRegistry.clear();
        HookRegistry.add(hook(TARGET, "takesString").at(Position.RETURN)
                .call(RECORDER, "head", "()V", Flow.VOID)
                .args("0").build());
        Check check = patchAndLoad();
        expect("the hook was refused", 0, check.applied);
        expectContaining("the refusal explains why", check.log, "only a head hook may read");
    }

    private static void noMethodReportsFailure() throws Exception {
        HookRegistry.clear();
        HookRegistry.add(hook(TARGET, "methodThatIsNotThere").at(Position.HEAD)
                .call(RECORDER, "head", "()V", Flow.VOID).build());
        Check check = patchAndLoad();
        expectContaining("the missing method is named", check.failures.toString(),
                "no method selftest.fixtures.Target.methodThatIsNotThere");
    }

    private static void noInsertionPointReportsCandidateList() throws Exception {
        HookRegistry.clear();
        HookRegistry.add(hook(TARGET, "callsOther").at(Position.BEFORE_INVOKE)
                .of(TARGET, "aMethodThatIsNeverCalled", "(DD)D")
                .call(RECORDER, "head", "()V", Flow.VOID).build());
        Check check = patchAndLoad();
        expectContaining("the miss lists what the method does call", check.log,
                "selftest/fixtures/Target.other(DD)D");
    }

    private static void missingCallbackIsFound() throws Exception {
        // The table names a callback that does not exist. NativeBridge's check
        // reads the owner's static members out of the jar and compares, so this
        // is the code path that turns a typo into one log line instead of a
        // NoSuchMethodError thrown from inside the render loop. The reflection
        // equivalent is used here because NativeBridge cannot be compiled
        // without the game.
        HookRegistry.clear();
        HookRegistry.add(hook(TARGET, "entry").at(Position.HEAD)
                .call(RECORDER, "headThatWasRenamed", "()V", Flow.VOID).build());
        java.util.Set<String> present = staticMembersOf(Recorder.class);
        expect("the renamed callback is not among the owner's static methods",
                Boolean.FALSE, Boolean.valueOf(present.contains("headThatWasRenamed()V")));
        expect("the real one is", Boolean.TRUE, Boolean.valueOf(present.contains("head()V")));
        expect("a non-static method is not offered as a callback",
                Boolean.FALSE,
                Boolean.valueOf(present.contains("instanceMethod()Ljava/lang/String;")));
    }

    /** Same membership test NativeBridge.checkCallbacks does, over a loaded class. */
    private static java.util.Set<String> staticMembersOf(Class<?> owner) {
        java.util.Set<String> known = new java.util.HashSet<String>();
        for (Method method : owner.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            StringBuilder descriptor = new StringBuilder("(");
            for (Class<?> parameter : method.getParameterTypes()) {
                descriptor.append(org.objectweb.asm.Type.getDescriptor(parameter));
            }
            descriptor.append(')').append(org.objectweb.asm.Type.getDescriptor(
                    method.getReturnType()));
            known.add(method.getName() + descriptor);
        }
        return known;
    }

    // --------------------------------------------------------------- plumbing

    /**
     * Registers nothing, patches the fixture with the given hook already in the
     * registry, checks the result, loads it, and calls the method.
     *
     * @return {returned value, Recorder's last remembered value, the instance}
     */
    private static Object[] run(String title, HookRegistry.Hook hook, String method,
                                Class<?>[] parameterTypes, Object[] arguments,
                                String expectedCalls) throws Exception {
        HookRegistry.clear();
        HookRegistry.add(hook);
        Check check = patchAndLoad();
        if (check.patched == null) {
            report(title, false, "the fixture was not patched at all: " + check.log);
            return new Object[]{null, null, null};
        }
        expect(title + " -- the class verifies", Boolean.TRUE, Boolean.valueOf(check.verified));

        Class<?> loaded = check.loaded;
        Method target = loaded.getMethod(method, parameterTypes);

        // Fresh recorder, then one call, then read what it saw -- before
        // anything else touches the recorder.
        Recorder.reset();
        Object instance = loaded.getDeclaredConstructor().newInstance();
        Object returned = target.invoke(instance, arguments);
        String actualCalls = Recorder.calls();
        Object remembered = Recorder.lastReturned();

        boolean callsOk = expectedCalls.equals(actualCalls);
        report(title, callsOk,
                "calls: expected [" + expectedCalls + "] got [" + actualCalls + "]");
        return new Object[]{returned, remembered, instance};
    }

    private static final class Check {
        byte[] patched;
        boolean verified;
        String log = "";
        String failures = "";
        Class<?> loaded;
        FixtureCompiler loader;
        int applied;
    }

    private static Check patchAndLoad() throws Exception {
        Check check = new Check();
        check.loader = FixtureCompiler.fresh();
        // The hook is already in the registry: HookTransformer reads the table
        // in its constructor to build its owner map, so registration has to
        // happen first. That ordering is the same one NativeBridge.install
        // relies on.
        HookTransformer transformer = new HookTransformer(new Class<?>[0]);
        byte[] original = read(TARGET_PATH);
        // patch() takes the internal name, which is what the JVM hands the
        // class file load hook -- not the dotted one the hook table is written
        // in.
        check.patched = transformer.patch(TARGET.replace('.', '/'), original);
        check.log = join(transformer.drain());
        check.failures = join(transformer.failures());
        check.applied = transformer.appliedKeys().size();
        if (check.patched == null) {
            check.log = "registry=" + HookRegistry.size()
                    + " known=" + transformer.knownOwners()
                    + " applied=" + check.applied
                    + (check.log.isEmpty() ? "" : " | " + check.log)
                    + (check.failures.isEmpty() ? "" : " | failures=" + check.failures);
            return check;
        }
        try {
            StringWriter diagnostics = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(check.patched),
                    check.loader, false, new PrintWriter(diagnostics));
            check.verified = diagnostics.getBuffer().length() == 0;
            if (!check.verified) {
                check.log = check.log + " | verify: " + diagnostics;
            }
        } catch (Throwable t) {
            check.verified = false;
            StringWriter trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            check.log = check.log + " | verify threw:" + System.lineSeparator() + trace;
        }
        try {
            // The transformer keeps the class's own name, so the patched copy
            // has to be renamed before it can be defined alongside the
            // original.
            check.loaded = check.loader.define(PATCHED, rename(check.patched));
        } catch (VerifyError rejected) {
            check.verified = false;
            StringWriter trace = new StringWriter();
            rejected.printStackTrace(new PrintWriter(trace));
            check.log = check.log + " | definition rejected:" + System.lineSeparator() + trace;
        }
        return check;
    }

    /** Gives a patched class the name the loader is about to define it under. */
    private static byte[] rename(byte[] classfile) {
        ClassReader reader = new ClassReader(classfile);
        ClassWriter writer = new ClassWriter(0);
        final String from = TARGET.replace('.', '/');
        final String to = PATCHED.replace('.', '/');
        reader.accept(new org.objectweb.asm.commons.ClassRemapper(writer,
                new org.objectweb.asm.commons.Remapper() {
                    @Override
                    public String map(String internalName) {
                        return from.equals(internalName) ? to : internalName;
                    }
                }), 0);
        return writer.toByteArray();
    }

    private static byte[] read(String path) throws Exception {
        InputStream in = TransformerFixtures.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("fixture not on the classpath: " + path);
        }
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

    private static String join(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) {
                out.append(" | ");
            }
            out.append(line);
        }
        return out.toString();
    }

    // ------------------------------------------------------------ assertions

    private static void expect(String what, Object expected, Object actual) {
        checks++;
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what
                + (ok ? "" : " (expected " + expected + ", got " + actual + ")"));
    }

    private static void expectContaining(String what, String haystack, String needle) {
        checks++;
        boolean ok = haystack != null && haystack.contains(needle);
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what
                + (ok ? "" : "\n         wanted to find: " + needle + "\n         in: " + haystack));
    }

    private static void report(String title, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + title);
        if (!ok) {
            System.out.println("         " + detail);
        }
    }
}
