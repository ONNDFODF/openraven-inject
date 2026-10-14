import xyz.daikirai.inject.MappingBridge;
import xyz.daikirai.inject.NativeBridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Rewrites the client's own classes from MCP names to the names the game uses,
 * at build time.
 *
 * <p>This is InjectMyau's model: their jar arrives already reobfuscated, so a
 * running game never has to touch the client's bytecode at all. This build
 * started out doing that rewrite inside the running game -- during class
 * definition, inside the JVMTI hook -- and every problem it had came from that:
 * a class half-renamed while the render thread called into it, an interface
 * implementation whose name did not change with the interface, an
 * {@code AbstractMethodError} from inside {@code CommandHandler}. Doing it here
 * means the jar on disk is already in the game's language, and the only thing
 * left for the runtime is finding hook targets.
 *
 * <p>The hierarchy the renames need comes from the mapped Minecraft and Forge
 * jars on the class path -- the same ones the client was compiled against.
 *
 * <p>The Forge-shaped shim in the non-Forge jars is rewritten too. It is not a
 * library here: its constructors and methods name game types, so it has to end
 * up in the same naming as the client that calls it.
 *
 * Usage: Reobf &lt;staging-directory&gt;
 */
public final class Reobf {

    private Reobf() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        MappingBridge.forceRemapping();

        List<Path> classes;
        try (Stream<Path> walk = Files.walk(root)) {
            classes = walk.filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        // The client itself, minus the shaded ASM.
                        if (relative.startsWith("xyz/daikirai/")) {
                            return !relative.startsWith("xyz/daikirai/shadow/");
                        }
                        // The Forge-shaped shim that the two non-Forge jars
                        // carry. It is part of the client here, not a library:
                        // its constructors and methods name game types
                        // (RenderWorldLastEvent takes a RenderGlobal), so it
                        // has to end up in the same naming as the client that
                        // calls it. Left in MCP names while the client went to
                        // notch, the first frame of the first Badlion world
                        // died on NoSuchMethodError:
                        // RenderWorldLastEvent.<init>(bfr, float).
                        return relative.startsWith("net/minecraftforge/");
                    })
                    .collect(Collectors.toList());
        }

        int rewritten = 0;
        int changed = 0;
        for (Path path : classes) {
            String relative = root.relativize(path).toString().replace('\\', '/');
            String internal = relative.substring(0, relative.length() - ".class".length());
            byte[] before = Files.readAllBytes(path);
            byte[] after = NativeBridge.remapForBuild(internal, before);
            if (after != null && after != before) {
                Files.write(path, after);
                changed++;
            }
            rewritten++;
        }
        System.out.println("reobf: " + rewritten + " client classes rewritten to the game's naming"
                + (changed > 0 ? "" : " (nothing needed it)"));
    }
}