package xyz.daikirai.inject;

import xyz.daikirai.shadow.asm.ClassReader;
import xyz.daikirai.shadow.asm.util.CheckClassAdapter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Runs the client's transformer over a class file, offline.
 *
 * <p>Written for the one question the game cannot answer: a class that is
 * already loaded when the agent arrives is retransformed, and
 * {@code RetransformClasses} can refuse the result with "the rewritten class
 * does not verify" -- with the class itself nowhere in the log. OptiFine
 * transforms {@code EntityPlayerSP} on the way in, so the bytes the JVM hands
 * the hook are not the ones in any jar, and the only way to look at them is to
 * keep them (the transformer dumps them next to its log) and run them through
 * here.
 *
 * <p>What it prints is the whole answer: whether the input verifies, whether
 * the output does, which hooks landed, and where the patched bytes were left.
 *
 * <p>The verifier is the selftest's: ASM straight from its own jars, not the
 * copy the client shades, because the shaded one's CheckClassAdapter refuses to
 * run outside a preview-enabled JVM. The two copies live in different packages
 * and do not see each other.
 *
 * <p>Usage, with the client jar, the game's jars, the selftest's ASM jars and
 * the probe on the class path:
 * <pre>
 *   java -cp "build/probe;dist/OpenRaven-Inject.jar;&lt;srg jars&gt;;&lt;asm jars&gt;" \
 *        xyz.daikirai.inject.ClassDump &lt;class file&gt; [&lt;class path&gt;]
 * </pre>
 */
public final class ClassDump {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: ClassDump <class file> [class path for verification]");
            System.exit(2);
        }
        File input = new File(args[0]);
        byte[] bytes = read(input);
        String internalName = new ClassReader(bytes).getClassName();
        System.out.println("class: " + internalName + ", " + bytes.length + " bytes");

        Log.to(new File(input.getParentFile(), "classdump.log"));
        InjectHooks.register();
        MappingBridge.prepare();
        System.out.println("hooks registered: " + HookRegistry.size()
                + " on " + HookRegistry.owners().size() + " classes");
        System.out.println("remapping needed: " + MappingBridge.needsRemapping()
                + " (jar already named: " + MappingBridge.ourClassesAlreadyNamed() + ")");

        ClassLoader loader = loader(args.length > 1 ? args[1] : null);
        verify(bytes, loader, "input");

        HookTransformer transformer = new HookTransformer(new Class<?>[0]);
        byte[] patched = transformer.patch(internalName, bytes);
        if (patched == null) {
            System.out.println("the transformer left the class alone");
        } else {
            System.out.println("patched: " + patched.length + " bytes");
        }
        for (String line : transformer.drain()) {
            System.out.println("  " + line);
        }
        for (String failure : transformer.failures()) {
            System.out.println("  failure: " + failure);
        }
        for (String failure : MappingBridge.failures()) {
            System.out.println("  unresolved: " + failure);
        }

        if (patched != null) {
            File out = new File(input.getPath() + ".patched");
            write(out, patched);
            System.out.println("wrote " + out.getAbsolutePath());
            verify(patched, loader, "patched");
        }
    }

    private static ClassLoader loader(String classPath) {
        ClassLoader parent = ClassDump.class.getClassLoader();
        if (classPath == null || classPath.length() == 0) {
            return parent;
        }
        try {
            String[] parts = classPath.split(";");
            URL[] urls = new URL[parts.length];
            for (int i = 0; i < parts.length; i++) {
                urls[i] = new File(parts[i]).toURI().toURL();
            }
            return new URLClassLoader(urls, parent);
        } catch (Throwable failed) {
            System.out.println("cannot build the verification class loader: " + failed);
            return parent;
        }
    }

    private static void verify(byte[] bytes, ClassLoader loader, String label) {
        StringWriter buffer = new StringWriter();
        PrintWriter printer = new PrintWriter(buffer);
        try {
            CheckClassAdapter.verify(new ClassReader(bytes), loader, false, printer);
        } catch (Throwable failed) {
            printer.println("the verifier threw: " + failed);
        }
        printer.flush();
        String report = buffer.toString().trim();
        if (report.length() == 0) {
            System.out.println(label + ": verifies clean");
            return;
        }
        String[] lines = report.split("\r?\n");
        System.out.println(label + ": " + lines.length + " verifier complaint(s)");
        for (int i = 0; i < lines.length && i < 12; i++) {
            System.out.println("  " + lines[i]);
        }
    }

    private static byte[] read(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
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

    private static void write(File file, byte[] bytes) throws Exception {
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    private ClassDump() {
    }
}