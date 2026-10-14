package selftest;

/** Runs the fixtures with the full stack trace on stderr, which a bare main does not. */
public final class RunFixtures {

    private RunFixtures() {
    }

    public static void main(String[] args) {
        try {
            TransformerFixtures.main(args);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.err.flush();
            System.exit(2);
        }
    }
}
