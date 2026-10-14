import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Builds openraven_mappings.tsv by pairing the two Minecraft jars Loom leaves
 * in its cache: the mapped one (MCP names) and the srg one (the names the game
 * actually runs with).
 *
 * <p>This replaces a table that had been converted from
 * mappings-srg-named.srg, which turned out not to be the whole mapping:
 * Frustum.setPosition has no entry there, so the client kept calling
 * setPosition and the running game had only func_78547_a. Pairing the jars is
 * exact by construction -- the two are the same class files with different
 * member names, in the same order -- and covers every member the game has.
 *
 * Usage: GenerateMappings &lt;mapped.jar&gt; &lt;srg.jar&gt; &lt;out.tsv&gt;
 */
public final class GenerateMappings {

    private GenerateMappings() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> methods = new LinkedHashMap<String, String>();
        Map<String, String> fields = new LinkedHashMap<String, String>();

        try (JarFile mapped = new JarFile(args[0]);
             JarFile srg = new JarFile(args[1])) {
            for (Enumeration<JarEntry> entries = mapped.entries(); entries.hasMoreElements(); ) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                JarEntry peer = srg.getJarEntry(entry.getName());
                if (peer == null) {
                    continue;
                }
                ClassNode a = read(mapped, entry);
                ClassNode b = read(srg, peer);
                String owner = a.name;
                pairMethods(a, b, owner, methods);
                pairFields(a, b, owner, fields);
            }
        }

        PrintWriter out = new PrintWriter(Files.newBufferedWriter(
                Paths.get(args[2]), StandardCharsets.UTF_8));
        try {
            out.println("# generated from the mapped and srg Minecraft jars; do not edit");
            out.println("# METHOD<TAB>owner<TAB>mcp name<TAB>mcp desc<TAB>srg name");
            out.println("# FIELD<TAB>owner<TAB>mcp name<TAB>mcp desc<TAB>srg name");
            for (Map.Entry<String, String> entry : methods.entrySet()) {
                out.println("METHOD\t" + entry.getKey() + "\t" + entry.getValue());
            }
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                out.println("FIELD\t" + entry.getKey() + "\t" + entry.getValue());
            }
        } finally {
            out.close();
        }
        System.out.println("mapgen: " + methods.size() + " methods, " + fields.size() + " fields");
    }

    private static ClassNode read(JarFile jar, JarEntry entry) throws Exception {
        ClassNode node = new ClassNode();
        try (java.io.InputStream in = jar.getInputStream(entry)) {
            new ClassReader(in).accept(node,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    /** Both jars hold the same members in the same order; descriptors confirm it. */
    private static void pairMethods(ClassNode a, ClassNode b, String owner,
                                    Map<String, String> out) {
        List<MethodNode> left = new ArrayList<MethodNode>(a.methods);
        List<MethodNode> right = new ArrayList<MethodNode>(b.methods);
        int j = 0;
        for (MethodNode method : left) {
            MethodNode match = null;
            for (int k = j; k < right.size(); k++) {
                if (right.get(k).desc.equals(method.desc)) {
                    match = right.get(k);
                    j = k + 1;
                    break;
                }
            }
            if (match != null) {
                out.put(owner + "\t" + method.name + "\t" + method.desc, match.name);
            }
        }
    }

    private static void pairFields(ClassNode a, ClassNode b, String owner,
                                   Map<String, String> out) {
        List<FieldNode> left = new ArrayList<FieldNode>(a.fields);
        List<FieldNode> right = new ArrayList<FieldNode>(b.fields);
        int j = 0;
        for (FieldNode field : left) {
            FieldNode match = null;
            for (int k = j; k < right.size(); k++) {
                if (right.get(k).desc.equals(field.desc)) {
                    match = right.get(k);
                    j = k + 1;
                    break;
                }
            }
            if (match != null) {
                out.put(owner + "\t" + field.name + "\t" + field.desc, match.name);
            }
        }
    }
}