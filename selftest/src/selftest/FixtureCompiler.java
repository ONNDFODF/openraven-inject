package selftest;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Loads patched fixtures under a name of their own, so a patched class and its
 * original can both be in play at once.
 *
 * Frame computation resolves the fixtures' hierarchy through this loader, which
 * is why it is a top-level class loaded once by the application loader: as a
 * nested class it would exist twice, once for each copy of its outer class, and
 * the transformer would compute frames against the wrong lineage.
 */
final class FixtureCompiler extends ClassLoader {

    /**
     * A fresh loader per case. A class name can only be defined once per
     * loader, and every case wants its own copy of the patched fixture, so
     * sharing one would make the second case fail on a duplicate definition.
     */
    static FixtureCompiler fresh() {
        return new FixtureCompiler();
    }

    private FixtureCompiler() {
        super(TransformerFixtures.class.getClassLoader());
    }

    Class<?> define(String name, byte[] bytes) {
        return defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Recorder is deliberately excluded: the fixture compiler has to see the same
        // Recorder the patched class does, and it is the parent's.
        boolean ownFixtures = name.startsWith("selftest.fixtures.")
                || name.startsWith("selftest.patched.");
        if (ownFixtures && !name.endsWith("Recorder")) {
            Class<?> existing = findLoadedClass(name);
            if (existing != null) {
                return existing;
            }
            // A patched class is defined from bytes handed in; only the
            // fixtures themselves are read off the classpath, and those are
            // already compiled under these names.
            byte[] bytes = read(name.replace('.', '/') + ".class");
            Class<?> defined = defineClass(name, bytes, 0, bytes.length);
            if (resolve) {
                resolveClass(defined);
            }
            return defined;
        }
        return super.loadClass(name, resolve);
    }

    private static byte[] read(String path) {
        InputStream in = FixtureCompiler.class.getClassLoader().getResourceAsStream(path);
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
        } catch (java.io.IOException failed) {
            throw new IllegalStateException("cannot read fixture " + path, failed);
        } finally {
            try {
                in.close();
            } catch (java.io.IOException ignored) {
                // Nothing useful to do about a failed close on a classpath resource.
            }
        }
    }
}
