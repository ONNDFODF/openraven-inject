package selftest.fixtures;

/**
 * Shape of the classes the hook table points at.
 *
 * The field is what makes a replacement land: a getter and a setter with the
 * same name and no others, so a hook that names {@code value} unambiguously
 * picks one of them out.
 */
public class Target {

    public float value;

    /**
     * Seeded, so that the read-before-write shape has something to multiply.
     * {@code multipliesField} would not tell a working hook from a hook that
     * landed on the wrong access if the field started at zero.
     */
    public Target() {
        value = 2.0F;
    }

    public void entry() {
        Recorder.head();
    }

    public void callsOther() {
        other(1.5D, 2.5D);
    }

    public void callsOtherAfter() {
        other(1.5D, 2.5D);
    }

    public double other(double a, double b) {
        return a + b;
    }

    public int valueReturning() {
        return 5;
    }

    public int valueReturningOverridden() {
        return 5;
    }

    public int valueReturningUntouched() {
        return 5;
    }

    public void cancels() {
        Recorder.remembered("ran");
    }

    public void cancelsNot() {
        Recorder.remembered("ran");
    }

    /** A void call taking an argument, so REPLACE_INVOKE with void can be checked. */
    public void takesInt(int value) {
        Recorder.remembered(Integer.valueOf(value));
    }

    public void callsTakesInt() {
        takesInt(11);
    }

    public float readsField() {
        return value;
    }

    public void writesField() {
        value = 4.25F;
    }

    /**
     * The shape {@code EntityPlayerSP.onLivingUpdate} has: the field is read
     * before it is written, because the vanilla code multiplies it rather than
     * assigning to it. A hook that replaces the write has to land on the
     * PUTFIELD, not on the read inside the multiplication.
     */
    public float multipliesField() {
        value *= 4.0F;
        return value;
    }

    public float constant() {
        return 14.0F;
    }

    /** The value store a ModifyVariable-shaped hook latches onto. */
    public float storesLocal() {
        float f = 1.5F;
        return f * 2.0F;
    }

    public String takesString(String input) {
        return "saw:" + input;
    }
}
