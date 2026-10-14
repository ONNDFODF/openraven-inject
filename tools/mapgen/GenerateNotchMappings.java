import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the MCP to notch table for the Badlion build.
 *
 * <p>Badlion's game classes are the vanilla obfuscated ones -- Minecraft is
 * {@code ave}, {@code runTick} is {@code s} -- and the client source is written
 * in MCP names, so something has to say what each name is called there.
 *
 * <p>Two sources, because neither is complete on its own. Loom's
 * {@code mappings-srg.tiny} has every vanilla member with its obfuscated name,
 * and names the ones Mojang's mapping named; that gives the MCP name and the
 * obfuscated name in one line. Our own {@code openraven_mappings.tsv} has MCP
 * to SRG for the whole MCP jar, including members the mapping leaves unnamed,
 * and a join through the tiny file's SRG column reaches those. Members that are
 * in neither -- Forge additions, which vanilla and Badlion have never had -- are
 * left out on purpose: leaving them out is what lets CheckRefs name a reference
 * the game cannot resolve, rather than the reobf inventing a name for it.
 *
 * <p>Usage: GenerateNotchMappings &lt;mappings-srg.tiny&gt;
 * &lt;openraven_mappings.tsv&gt; &lt;out.tsv&gt;
 */
public final class GenerateNotchMappings {

    private static final Map<String, String> MCP_CLASSES = new HashMap<String, String>();
    private static final Map<String, String> CLASSES = new LinkedHashMap<String, String>();
    private static final Map<String, String> BY_SRG = new HashMap<String, String>();
    private static final Map<String, String> BY_NAME = new LinkedHashMap<String, String>();

    private GenerateNotchMappings() {
    }

    public static void main(String[] args) throws Exception {
        readClasses(args[0]);
        readMembers(args[0]);
        readMappings(args[1]);

        int classes = 0;
        int methods = 0;
        int fields = 0;
        PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(args[2]), StandardCharsets.UTF_8));
        try {
            out.println("# generated from mappings-srg.tiny and openraven_mappings.tsv; do not edit");
            out.println("# CLASS<TAB>mcp class<TAB>notch class");
            out.println("# METHOD/FIELD<TAB>mcp owner<TAB>mcp name<TAB>mcp desc<TAB>notch name");
            for (Map.Entry<String, String> entry : CLASSES.entrySet()) {
                out.println("CLASS\t" + entry.getKey() + "\t" + entry.getValue());
                classes++;
            }
            for (Map.Entry<String, String> entry : BY_NAME.entrySet()) {
                out.println(entry.getValue());
                if (entry.getValue().startsWith("METHOD")) {
                    methods++;
                } else {
                    fields++;
                }
            }
        } finally {
            out.close();
        }
        System.out.println("notch mappings: " + classes + " classes, " + methods
                + " methods, " + fields + " fields");
    }

    /**
     * The class lines, before any member is read.
     *
     * <p>Members are read in a second pass because their descriptors are
     * written in obfuscated names and have to be translated through this table,
     * which is only complete once every class line has been seen -- a class
     * early in the file happily refers to one at the end of it.
     */
    private static void readClasses(String path) throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader(path));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("c\t")) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 5) {
                    continue;
                }
                MCP_CLASSES.put(parts[1], parts[2]);
                CLASSES.put(parts[2].replace('/', '.'), parts[1].replace('/', '.'));
            }
        } finally {
            reader.close();
        }
    }

    /** Everything the mapping names itself: MCP name and obfuscated name together. */
    private static void readMembers(String path) throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader(path));
        try {
            String current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("c\t")) {
                    String[] parts = line.split("\t", -1);
                    current = parts.length < 5 ? null : parts[2];
                    continue;
                }
                if (current == null) {
                    continue;
                }
                String trimmed = line.trim();
                if (!trimmed.startsWith("m\t") && !trimmed.startsWith("f\t")) {
                    continue;
                }
                String[] parts = trimmed.split("\t", -1);
                if (parts.length < 6) {
                    continue;
                }
                // kind, descriptor, official, srg, intermediary, named
                BY_SRG.put(current + '\u0000' + parts[3] + '\u0000' + translate(parts[1]),
                        parts[2]);
                if (parts[5].length() > 0) {
                    String descriptor = translate(parts[1]);
                    if (descriptor != null) {
                        BY_NAME.put(current.replace('/', '.') + '\u0000' + parts[5] + '\u0000'
                                        + descriptor + '\u0000' + parts[0],
                                (parts[0].equals("m") ? "METHOD" : "FIELD") + "\t"
                                        + current.replace('/', '.') + "\t" + parts[5] + "\t"
                                        + descriptor + "\t" + parts[2]);
                    }
                }
            }
        } finally {
            reader.close();
        }
    }

    /** Our own MCP to SRG table, joined onto the mapping's SRG names. */
    private static void readMappings(String path) throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader(path));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() == 0 || line.charAt(0) == '#') {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 4) {
                    continue;
                }
                boolean method = "METHOD".equals(parts[0]);
                if (!method && !"FIELD".equals(parts[0])) {
                    continue;
                }
                String owner = parts[1].replace('.', '/');
                String descriptor = parts[3];
                String srg = parts.length >= 5 ? parts[4] : parts[3];
                String notch = BY_SRG.get(owner + '\u0000' + srg + '\u0000' + descriptor);
                if (notch == null || notch.length() == 0) {
                    continue;
                }
                BY_NAME.put(owner.replace('/', '.') + '\u0000' + parts[2] + '\u0000' + descriptor
                                + '\u0000' + (method ? "m" : "f"),
                        (method ? "METHOD" : "FIELD") + "\t" + parts[1] + "\t" + parts[2] + "\t"
                                + descriptor + "\t" + notch);
            }
        } finally {
            reader.close();
        }
    }

    /**
     * Rewrites a descriptor's obfuscated class names to MCP ones.
     *
     * <p>Anything the class table does not name is not a game class -- a JDK
     * type, LWJGL -- and keeps the name it has.
     */
    private static String translate(String descriptor) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < descriptor.length()) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0) {
                    return null;
                }
                String notch = descriptor.substring(i + 1, end);
                String mcp = MCP_CLASSES.get(notch);
                out.append('L').append(mcp == null ? notch : mcp).append(';');
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}