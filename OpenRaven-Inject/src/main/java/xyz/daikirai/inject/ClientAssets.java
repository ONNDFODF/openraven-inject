package xyz.daikirai.inject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.ResourcePackRepository;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Puts this jar's assets where the game can read them.
 *
 * <p>A mod's jar is read by the game's resource manager because Forge knows the
 * jar is a mod: it has a mod container, and the container's resource pack is
 * part of the pack list. This jar is not a mod -- nothing loaded it but
 * {@code LaunchClassLoader} -- so every texture it ships resolves to nothing.
 * The symptom is a single line in the game's log ("Failed to load texture:
 * keystrokesmod:textures/capes/rvn_red.png") and a cosmetic feature that draws
 * an empty cape, which reads as a broken module rather than a missing file.
 *
 * <p>So the assets are copied into a resource pack folder of their own, in the
 * game's {@code resourcepacks} directory, and that pack is turned on. The
 * folder is a normal resource pack after that: it can be turned off in the
 * game's options, and it is left on disk, so a later run without the injector
 * still has the textures. {@code openraven-inject} is the pack's name and its
 * description both, because the name the repository reports for a folder pack
 * is one or the other depending on the version.
 *
 * <p>Called from the client's own start, not from the injection: a resource
 * reload while the game is still loading is a reload of something that has not
 * finished building yet.
 */
public final class ClientAssets {

    private static final String PACK = "openraven-inject";

    private ClientAssets() {
    }

    /** Once per JVM: the first call installs the pack and reloads, the rest are
     *  no-ops. There are two call sites on purpose -- the first tick, and the
     *  client's own start -- and only one of them is early enough on every
     *  client. */
    private static boolean installed;

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft == null || minecraft.mcDataDir == null) {
                Log.line("no game directory to put the client's assets in");
                return;
            }
            File jar = NativeBridge.jarFile();
            if (jar == null || !jar.isFile()) {
                Log.line("no client jar to copy assets out of");
                return;
            }
            File pack = new File(new File(minecraft.mcDataDir, "resourcepacks"), PACK);
            int copied = copy(jar, pack);
            String name = enable(minecraft);
            if (name == null) {
                Log.line("copied " + copied + " client asset(s) to " + pack
                        + ", but the game does not list the pack yet");
            } else {
                Log.line("copied " + copied + " client asset(s) to " + pack
                        + "; the pack is on as " + name);
            }
        } catch (Throwable failed) {
            // A client without its textures is worse than one with them, but
            // far better than one that does not start.
            Log.throwable("could not put the client's assets where the game looks", failed);
        }
    }

    /** Copies everything under {@code assets/} out of the jar, plus a pack.mcmeta. */
    private static int copy(File jar, File pack) throws IOException {
        int written = 0;
        JarFile file = new JarFile(jar);
        try {
            byte[] buffer = new byte[8192];
            for (Enumeration<JarEntry> entries = file.entries(); entries.hasMoreElements(); ) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("assets/")) {
                    continue;
                }
                File out = new File(pack, name);
                if (out.isFile() && out.length() == entry.getSize()) {
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("cannot create " + parent);
                }
                InputStream in = file.getInputStream(entry);
                try {
                    OutputStream stream = new FileOutputStream(out);
                    try {
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            stream.write(buffer, 0, read);
                        }
                    } finally {
                        stream.close();
                    }
                } finally {
                    in.close();
                }
                written++;
            }
        } finally {
            file.close();
        }
        File meta = new File(pack, "pack.mcmeta");
        if (!meta.isFile()) {
            write(meta, "{\"pack\":{\"pack_format\":1,\"description\":\"" + PACK + "\"}}");
            written++;
        }
        return written;
    }

    private static void write(File file, String text) throws IOException {
        OutputStream stream = new FileOutputStream(file);
        try {
            stream.write(text.getBytes("UTF-8"));
        } finally {
            stream.close();
        }
    }

    /**
     * Turns the pack on, and returns the name the game knows it by.
     *
     * <p>The selection is the game's own list of enabled pack names, which is
     * also what the options screen edits and what {@code options.txt} keeps, so
     * going through it is what makes the pack load now and stay loaded.
     */
    private static String enable(Minecraft minecraft) {
        ResourcePackRepository repository = minecraft.getResourcePackRepository();
        repository.updateRepositoryEntriesAll();
        String name = null;
        for (ResourcePackRepository.Entry entry : repository.getRepositoryEntriesAll()) {
            String candidate = entry.getResourcePackName();
            if (candidate != null && candidate.toLowerCase().contains(PACK)) {
                name = candidate;
                break;
            }
        }
        if (name == null) {
            return null;
        }
        // The pack is enabled whether or not the name is already in the list,
        // and the resources are reloaded either way. The name is kept in
        // options.txt, so on the second run it is already there while this
        // session's resource manager has never heard of the pack -- which is
        // the state a missing cape texture comes from.
        if (!minecraft.gameSettings.resourcePacks.contains(name)) {
            minecraft.gameSettings.resourcePacks.add(name);
        }
        minecraft.refreshResources();
        return name;
    }
}