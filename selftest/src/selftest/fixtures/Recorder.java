package selftest.fixtures;

/**
 * Stands in for a hook callback owner in the table, and records what was
 * called.
 *
 * These fixtures drive {@link xyz.daikirai.inject.HookTransformer} directly,
 * with a hook table pointing here, so the transformer's placement logic can be
 * checked without a game, a JVM tool interface, or a running Minecraft.
 */
public final class Recorder {

    private Recorder() {
    }

    public static void reset() {
        calls.setLength(0);
        lastString = null;
        lastFloat = Float.NaN;
        lastReturned = null;
    }

    private static final StringBuilder calls = new StringBuilder();

    public static String calls() {
        return calls.toString();
    }

    private static void note(String name) {
        if (calls.length() > 0) {
            calls.append(',');
        }
        calls.append(name);
    }

    /** Deliberately not static, so the callback check can be shown to skip it. */
    public String instanceMethod() {
        return "not a callback";
    }

    // --- the shapes the hook table asks for, one per position/flow pair ---

    /** HEAD, Flow.VOID */
    public static void head() {
        note("head");
    }

    /** BEFORE_INVOKE, Flow.VOID */
    public static void beforeInvoke() {
        note("beforeInvoke");
    }

    /** AFTER_INVOKE, Flow.VOID */
    public static void afterInvoke() {
        note("afterInvoke");
    }

    /** RETURN, Flow.VOID */
    public static void atReturn() {
        note("atReturn");
    }

    /** HEAD, Flow.CANCEL -- true stops the method. */
    public static boolean cancel(Object self) {
        note("cancel");
        return true;
    }

    /** HEAD, Flow.CANCEL on a value-returning method -- true returns 0. */
    public static boolean cancelWithValue(Object self) {
        note("cancelWithValue");
        return true;
    }

    /** HEAD, Flow.OVERRIDE -- an array forces the return value. */
    public static Object[] override(Object self) {
        note("override");
        return new Object[]{Integer.valueOf(42)};
    }

    /** HEAD, Flow.OVERRIDE -- null lets the method run. */
    public static Object[] overrideNull(Object self) {
        note("overrideNull");
        return null;
    }

    /** REPLACE_INVOKE, Flow.REPLACE */
    public static double replaceCall(Object self, double a, double b) {
        note("replaceCall");
        return 99.0D;
    }

    /** REPLACE_INVOKE, Flow.VOID -- the call happens, its result is dropped. */
    public static void replaceCallVoid(Object self, int value) {
        note("replaceCallVoid:" + value);
        lastReturned = Integer.valueOf(value);
    }

    /** REPLACE_FIELD on a read, Flow.REPLACE */
    public static float replaceFieldRead(Object self) {
        note("replaceFieldRead");
        lastFloat = 7.5F;
        return 7.5F;
    }

    /** REPLACE_FIELD_WITH on a write, Flow.VOID -- sees the value that was about to be stored. */
    public static float replaceFieldWrite(float value) {
        note("replaceFieldWrite:" + value);
        lastFloat = value;
        return value + 1.0F;
    }

    /** REPLACE_FIELD on a write, Flow.VOID -- the write does not happen. */
    public static void replaceFieldDroppedWrite(Object target, float value) {
        note("replaceFieldDroppedWrite:" + value);
        lastFloat = value;
    }
    /** MODIFY_CONSTANT, Flow.REPLACE */
    public static float modifyConstant(float value) {
        note("modifyConstant:" + value);
        return 21.0F;
    }

    /** MODIFY_STORE, Flow.REPLACE */
    public static float modifyStore(float value) {
        note("modifyStore:" + value);
        lastFloat = 3.5F;
        return 3.5F;
    }

    /** MODIFY_ARGUMENT, Flow.REPLACE */
    public static String modifyArgument(String value) {
        note("modifyArgument:" + value);
        lastString = value + "+patched";
        return lastString;
    }

    // --- what the callbacks saw ---

    private static String lastString;
    private static float lastFloat;
    private static Object lastReturned;

    public static String lastString() {
        return lastString;
    }

    public static float lastFloat() {
        return lastFloat;
    }

    public static Object lastReturned() {
        return lastReturned;
    }

    public static void remembered(Object value) {
        lastReturned = value;
    }
}
