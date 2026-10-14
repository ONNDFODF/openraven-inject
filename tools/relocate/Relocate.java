import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Moves a shaded copy of ASM out of {@code org.objectweb.asm} and into a
 * package only this client uses.
 *
 * <p>The injected build ran into exactly what this avoids: the game's own
 * class path already carries an ASM (Forge bundles one), that copy is what the
 * system loader serves for the {@code org.objectweb.asm} package, and our
 * jar -- appended to the same loader -- then supplied the classes the old copy
 * did not have. A nine-point-whatever {@code ClassRemapper} handing its API
 * version to a five-point-whatever {@code ClassVisitor} is an
 * {@code IllegalArgumentException} from inside {@code ClassVisitor.<init>},
 * which is what the remap failure looked like on a live game.
 *
 * <p>So: rewrite every class in the staging directory, ASM included, so that
 * ASM lives under a package nothing else on the class path can supply, and the
 * only ASM those classes can ever see is the one in this jar. InjectMyau does
 * the same with its {@code myau/shadow} package.
 *
 * Usage: Relocate <staging-directory>
 */
public final class Relocate {

    private static final String FROM = "org/objectweb/asm/";
    private static final String TO = "xyz/daikirai/shadow/asm/";

    private Relocate() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                return internalName.startsWith(FROM)
                        ? TO + internalName.substring(FROM.length())
                        : internalName;
            }
        };

        List<Path> classes;
        try (Stream<Path> walk = Files.walk(root)) {
            classes = walk.filter(path -> path.toString().endsWith(".class"))
                    .collect(Collectors.toList());
        }

        int moved = 0;
        int rewritten = 0;
        for (Path path : classes) {
            String relative = root.relativize(path).toString().replace('\\', '/');
            String internal = relative.substring(0, relative.length() - ".class".length());
            if ("module-info".equals(internal)) {
                Files.delete(path);
                continue;
            }
            byte[] bytes = Files.readAllBytes(path);
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassRemapper(writer, remapper), 0);
            byte[] rewrittenBytes = writer.toByteArray();

            String mapped = internal.startsWith(FROM)
                    ? TO + internal.substring(FROM.length())
                    : internal;
            Path target = root.resolve(mapped + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, rewrittenBytes);
            if (!target.equals(path)) {
                Files.delete(path);
                moved++;
            } else {
                rewritten++;
            }
        }
        System.out.println("relocated: " + moved + " asm classes, rewrote " + rewritten
                + " client classes");
    }
}