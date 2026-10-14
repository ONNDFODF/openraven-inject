package xyz.daikirai.inject;

import java.io.BufferedReader;
import java.util.ArrayDeque;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Name translation between the MCP names this source is written in and the
 * names the running game actually uses.
 *
 * Three environments are in play:
 *
 * <ul>
 *   <li>a Forge development client -- MCP names, nothing to do;</li>
 *   <li>a Forge production client -- MCP classes, SRG members;</li>
 *   <li>a launcher that ships its own client -- MCP names for 1.8.9.</li>
 * </ul>
 *
 * So the usual case needs no table at all, and the table is only consulted
 * when a name does not resolve. {@link #prepare()} works that out by looking
 * for a class that only exists in one of the shapes, rather than by asking the
 * user which launcher they are on.
 *
 * The table is a TSV, one entry per line, tab separated:
 *
 * <pre>
 *   CLASS   &lt;mcp/dotted&gt;        &lt;environment/dotted&gt;
 *   METHOD  &lt;mcp owner&gt;  &lt;mcp name&gt;  &lt;mcp descriptor&gt;  &lt;environment name&gt;
 *   FIELD   &lt;mcp owner&gt;  &lt;mcp name&gt;  &lt;mcp descriptor&gt;  &lt;environment name&gt;
 * </pre>
 *
 * Descriptors are written in MCP form and are rewritten along with the names
 * they mention. Generate the table from the MCP config for the version being
 * targeted; see {@code tools/} and the README. An absent table is not an
 * error -- it means the names already match.
 */
public final class MappingBridge {

    private static final String TABLE_RESOURCE = "/openraven_mappings.tsv";

    private static final Map<String, String> CLASSES = new HashMap<String, String>();
    private static final Map<String, String> METHODS = new HashMap<String, String>();
    private static final Map<String, String> FIELDS = new HashMap<String, String>();
    /** Member name to environment name, for names that map the same everywhere. */
    private static final Map<String, String> METHODS_FLAT = new HashMap<String, String>();
    private static final Map<String, String> FIELDS_FLAT = new HashMap<String, String>();
    private static final Set<String> METHODS_AMBIGUOUS = new HashSet<String>();
    private static final Set<String> FIELDS_AMBIGUOUS = new HashSet<String>();
    private static final Set<String> FAILURES =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    private static volatile boolean prepared;
    private static volatile boolean needed;
    private static volatile boolean forced;

    /**
     * Makes the table load even though the classes on this class path are still
     * MCP-named. The build-time reobf calls this; a running game does not.
     */
    public static synchronized void forceRemapping() {
        forced = true;
    }

    /**
     * True when this jar was already rewritten at build time -- the way
     * InjectMyau ships its client reobfuscated -- and its classes are in the
     * game's naming before anything runs.
     */
    public static boolean ourClassesAlreadyNamed() {
        return named;
    }

    /**
     * True while the build-time reobf is running. It is allowed to walk the
     * hierarchy of any class -- our own included -- because nothing here is
     * being defined, where the running game must not: asking a class loader for
     * a class it is in the middle of defining is a duplicate definition.
     */
    private static boolean building() {
        return forced;
    }

    private static volatile boolean named;

    private MappingBridge() {
    }

    /**
     * Loads the table if the game does not already use MCP names.
     *
     * Called once, before anything is transformed. Safe to call twice.
     */
    public static synchronized void prepare() {
        if (prepared) {
            return;
        }
        prepared = true;
        String built = null;
        try {
            java.io.InputStream marker = MappingBridge.class
                    .getResourceAsStream("/openraven-naming.txt");
            if (marker != null) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(marker, "UTF-8"));
                String first = reader.readLine();
                reader.close();
                built = first == null ? null : first.trim().toLowerCase();
                named = "srg".equals(built) || "notch".equals(built);
            }
        } catch (Throwable ignored) {
            Log.swallowed(ignored);
        }
        if (built != null && !forced) {
            // The jar was built for exactly one naming and the agent picked it
            // by looking at the game before unpacking it, so this is the answer
            // and there is nothing to probe. Badlion is why: its game classes
            // have no net.minecraft.client.Minecraft to probe with, and a
            // probe that fails has to mean "notch", not "assume MCP".
            if ("mcp".equals(built)) {
                Log.line("the jar was built for MCP names -- no remapping needed");
                return;
            }
            needed = true;
        } else if (!forced && usesMcpNames()) {
            Log.line("the game is running MCP names -- no remapping needed");
            return;
        } else {
            needed = true;
        }
        try {
            InputStream in = MappingBridge.class.getResourceAsStream(TABLE_RESOURCE);
            if (in == null) {
                File beside = new File(Log.file().getParentFile(), "openraven_mappings.tsv");
                if (beside.isFile()) {
                    in = new FileInputStream(beside);
                }
            }
            if (in == null) {
                Log.line("the game does not use MCP names and no mapping table was found at "
                        + TABLE_RESOURCE);
                Log.line("hooks will only install on classes whose names happen to match");
                return;
            }
            int classes = 0;
            int methods = 0;
            int fields = 0;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            try {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (line.length() == 0 || line.charAt(0) == '#') {
                        continue;
                    }
                    // -1 keeps trailing empties, and there are plenty: the module
                    String[] parts = line.split("\t", -1);
                    if (parts.length < 2) {
                        continue;
                    }
                    String kind = parts[0];
                    if ("CLASS".equals(kind) && parts.length >= 3) {
                        String mcp = parts[1].replace('.', '/');
                        String environment = parts[2].replace('.', '/');
                        CLASSES.put(mcp, environment);
                        ENV_CLASSES.add(environment);
                        MCP_CLASSES.put(environment, mcp);
                        classes++;
                    } else if ("METHOD".equals(kind) && parts.length >= 5) {
                        METHODS.put(key(parts[1], parts[2], parts[3]), parts[4]);
                        remember(METHODS_FLAT, METHODS_AMBIGUOUS,
                                parts[2] + '\u0000' + parts[3], parts[4]);
                        methods++;
                    } else if ("FIELD".equals(kind) && parts.length >= 4) {
                        // Field lines carry an empty descriptor column, so a
                        // four-column line and a five-column one mean the same.
                        String descriptor = parts.length >= 5 ? parts[3] : "";
                        String environment = parts.length >= 5 ? parts[4] : parts[3];
                        FIELDS.put(key(parts[1], parts[2], descriptor), environment);
                        remember(FIELDS_FLAT, FIELDS_AMBIGUOUS, parts[2], environment);
                        fields++;
                    }
                }
            } finally {
                reader.close();
            }
            Log.line("mapping table loaded: " + classes + " classes, " + methods + " methods, "
                    + fields + " fields");
        } catch (Throwable t) {
            Log.throwable("could not read the mapping table", t);
        }
    }

    /**
     * True when the running game does not call its classes by MCP names, so
     * this client's own classes have to be rewritten as they load.
     */
    public static boolean needsRemapping() {
        prepare();
        return needed;
    }

    /**
     * Probes for a class that only exists under one naming scheme.
     *
     * {@code net.minecraft.client.Minecraft} is MCP; the SRG name is
     * {@code net.minecraft.client.Minecraft} as well, so the class name cannot
     * decide it. A method name can: MCP calls it {@code runTick}, SRG calls it
     * {@code func_71407_l}. If neither is present the game is not loaded yet
     * and nothing is loading us either, so assuming MCP is safe.
     */
    public static boolean usesMcpNames() {
        try {
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false,
                    MappingBridge.class.getClassLoader());
            // The SRG name for runTick. Forge's deobfuscation transformer renames
            // the game's own classes, so this is what the method is called in a
            // Forge client, and runTick is what it is called in a development
            // environment. Testing for the SRG name and calling that "not MCP" is
            // the point of this method; testing for runTick, as this used to, got
            // the answer backwards for exactly the case that matters.
            for (java.lang.reflect.Method method : minecraft.getDeclaredMethods()) {
                if ("func_71407_l".equals(method.getName())) {
                    return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** Environment name for an MCP class, or the input when there is no entry. */
    public static String mapClass(String mcpDotted) {
        prepare();
        if (!needed || mcpDotted == null) {
            return mcpDotted;
        }
        String mapped = CLASSES.get(mcpDotted.replace('.', '/'));
        return mapped == null ? mcpDotted : mapped.replace('/', '.');
    }

    /**
     * Environment name for one MCP method reference, or the input when the
     * table has no entry for it.
     *
     * <p>This is the direction a class of ours needs when it is rewritten as it
     * loads -- "what is this method called in the running game" -- where
     * {@link #methodNames} asks "which names should a hook try". The entry that
     * names the owner and the descriptor wins; the flat table, which only keeps
     * names that map the same way on every owner, covers a call javac wrote
     * against a subclass.
     */
    public static String mapMethodName(String ownerInternal, String name, String descriptor) {
        prepare();
        if (!needed || name == null) {
            return name;
        }
        String owner = ownerInternal == null ? "" : ownerInternal.replace('.', '/');
        // A member of the class being rewritten: it may be the implementation
        // of something the game declares, and then it has to carry the game's
        // name for that member.
        String current = REMAP_OWNER.get();
        if (current != null && current.equals(owner)) {
            Set<String> ancestors = REMAP_ANCESTORS.get();
            if (ancestors != null) {
                for (String ancestor : ancestors) {
                    String inherited = METHODS.get(key(ancestor, name, descriptor));
                    if (inherited != null) {
                        return inherited;
                    }
                }
            }
        }
        // The owner on a reference is the type the call was written against --
        // javac puts the receiver's static type there -- while the table is
        // keyed by the class that declares the method. Walking the hierarchy is
        // what catches every inherited call, which is most of the game's
        // surface, and it has to follow interfaces as well as superclasses:
        // IInventory declares none of the methods this client calls on it,
        // getName() among them, because they live on IWorldNameable above it.
        // Following only superclasses left those calls on MCP names, and the
        // game answered with NoSuchMethodError.
        //
        // Only game classes are walked. Loading one of our own classes by name
        // while it is being defined -- which is exactly where this runs -- is
        // an "attempted duplicate class definition" from the loader, and the
        // table has nothing to say about our classes anyway.
        for (String cls : hierarchy(owner)) {
            String mapped = member(METHODS, cls, name, descriptor);
            if (mapped == null) {
                mapped = member(METHODS, cls, name, null);
            }
            if (mapped != null) {
                return mapped;
            }
        }
        if (isRenamedClass(owner)) {
            String flat = METHODS_FLAT.get(name + '\u0000' + (descriptor == null ? "" : descriptor));
            if (flat != null) {
                return flat;
            }
        }
        return name;
    }

    /** The field-shaped twin of {@link #mapMethodName}. */
    public static String mapFieldName(String ownerInternal, String name, String descriptor) {
        prepare();
        if (!needed || name == null) {
            return name;
        }
        String owner = ownerInternal == null ? "" : ownerInternal.replace('.', '/');
        // Same walk as methods, and needed for the same reason: a field that
        // only exists on a superclass or an interface is still written against
        // the type that read it.
        for (String cls : hierarchy(owner)) {
            String mapped = member(FIELDS, cls, name, descriptor);
            if (mapped == null) {
                mapped = member(FIELDS, cls, name, null);
            }
            if (mapped != null) {
                return mapped;
            }
        }
        if (isRenamedClass(owner)) {
            String flat = FIELDS_FLAT.get(name);
            if (flat != null) {
                return flat;
            }
        }
        return name;
    }

    /**
     * The class currently being rewritten, and the game types it inherits.
     *
     * <p>A method that implements an interface method or overrides a game
     * method has to end up with the game's own name for it -- Java binds
     * overrides by name, and a class whose {@code getCommandName} stayed
     * {@code getCommandName} while the interface declares {@code func_71517_b}
     * is a class that does not implement the interface at all. The first live
     * start died on that: {@code AbstractMethodError} from inside
     * {@code CommandHandler}.
     */
    private static final ThreadLocal<String> REMAP_OWNER = new ThreadLocal<String>();
    private static final ThreadLocal<Set<String>> REMAP_ANCESTORS =
            new ThreadLocal<Set<String>>();

    static void beginClassRemap(String ownerInternal, Set<String> ancestors) {
        REMAP_OWNER.set(ownerInternal);
        REMAP_ANCESTORS.set(ancestors);
    }

    static void endClassRemap() {
        REMAP_OWNER.remove();
        REMAP_ANCESTORS.remove();
    }

    /** Immediate parents of a class in the running game, walked once per class. */
    private static final Map<String, List<String>> PARENTS = new HashMap<String, List<String>>();

    /**
     * The class's superclass and interfaces, or null when it cannot be reached.
     *
     * <p>Interfaces matter as much as superclasses do. {@code IInventory}
     * declares almost nothing itself: the methods this client calls on a chest
     * are on {@code IWorldNameable}, one interface up. A walk that only
     * followed {@code getSuperclass()} walked past every one of them.
     */
    private static List<String> parents(String internalDottedOrSlashed) {
        String mapped = mapClass(internalDottedOrSlashed.replace('/', '.'));
        synchronized (PARENTS) {
            List<String> cached = PARENTS.get(mapped);
            if (cached != null) {
                return cached;
            }
        }
        try {
            Class<?> type = load(mapped, internalDottedOrSlashed.replace('/', '.'));
            if (type == null) {
                return null;
            }
            List<String> found = new ArrayList<String>();
            Class<?> parent = type.getSuperclass();
            if (parent != null && parent != Object.class) {
                found.add(parent.getName().replace('.', '/'));
            }
            for (Class<?> face : type.getInterfaces()) {
                found.add(face.getName().replace('.', '/'));
            }
            synchronized (PARENTS) {
                PARENTS.put(mapped, found);
            }
            return found;
        } catch (Throwable notLoadedYet) {
            // The class is not reachable right now. Do not remember that: it
            // may simply be a class that has not been defined yet, and the
            // answer has to be right the next time this is asked.
            return null;
        }
    }

    /**
     * Loads a class under either of its names.
     *
     * <p>Which one exists depends on where this runs. Rewriting a notch build
     * happens with the MCP jars on the class path, so the mapped name is the
     * one that is <i>not</i> there; inside a Badlion game it is the other way
     * round, and the MCP name is the one missing. Asking for both costs one
     * exception on the first lookup of each class and makes the walk work in
     * either place -- which matters, because walking the hierarchy is how an
     * inherited member is found at all.
     */
    private static Class<?> load(String first, String second) {
        ClassLoader loader = MappingBridge.class.getClassLoader();
        try {
            return Class.forName(first, false, loader);
        } catch (Throwable notThisOne) {
            try {
                return Class.forName(second, false, loader);
            } catch (Throwable neither) {
                return null;
            }
        }
    }

    /**
     * A class and everything above it, nearest first.
     *
     * <p>Breadth first, so that a member declared on the class itself always
     * wins over one with the same name higher up, and interfaces are visited
     * beside superclasses rather than after them. The walk leaves the game's
     * packages behind on purpose: {@code java.lang.Object} has no table
     * entries and loading a client class by name mid-definition is a duplicate
     * definition waiting to happen.
     */
    private static List<String> hierarchy(String ownerInternal) {
        List<String> order = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        ArrayDeque<String> queue = new ArrayDeque<String>();
        if (ownerInternal != null) {
            queue.add(ownerInternal);
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current == null || !seen.add(current)) {
                continue;
            }
            order.add(current);
            if (!isGameClass(current) && !building()) {
                continue;
            }
            List<String> next = parents(current);
            if (next != null) {
                queue.addAll(next);
            }
        }
        return order;
    }

    /**
     * Whether a class belongs to the game's renamed namespace, or inherits
     * from something that does.
     *
     * <p>{@code net.minecraftforge} counts even though Forge's own names are
     * never remapped: a call written against {@code ClientCommandHandler} is
     * really a call to {@code registerCommand} on the {@code CommandHandler}
     * it extends, and only the walk finds it. Leaving it out is a
     * {@code NoSuchMethodError} the moment the client starts.
     */
    private static boolean isGameClass(String ownerInternal) {
        return CLASSES.containsKey(ownerInternal)
                || ENV_CLASSES.contains(ownerInternal)
                || ownerInternal.startsWith("net/minecraft/")
                || ownerInternal.startsWith("net/minecraftforge/");
    }

    /** The environment names of the classes in the table, for the walk. */
    private static final Set<String> ENV_CLASSES = new HashSet<String>();

    /** The MCP name of an environment class, or the input when unknown. */
    static String mcpName(String environmentInternal) {
        if (environmentInternal == null) {
            return null;
        }
        String known = MCP_CLASSES.get(environmentInternal);
        return known == null ? environmentInternal : known;
    }

    /**
     * The MCP name of a class that is in hand, for callers that hold the
     * {@code Class} and not its name.
     *
     * <p>Forge's reflection helper is the caller: it is handed the class the
     * client source named, which on an obfuscated client is the class the game
     * renamed, and it needs the MCP spelling to ask the table what the member is
     * called there.
     */
    public static String mcpClassName(Class<?> type) {
        if (type == null) {
            return null;
        }
        return mcpName(type.getName().replace('.', '/'));
    }

    private static final Map<String, String> MCP_CLASSES = new HashMap<String, String>();

    /**
     * True for a class whose members the table actually renames: Minecraft's
     * own.
     *
     * <p>Deliberately narrower than {@link #isGameClass}. Forge's classes count
     * as game classes for the superclass walk -- a call written against a Forge
     * class is often a call to a Minecraft method it inherits, and only the walk
     * finds it -- but Forge's own members are never renamed. Renaming them is
     * what put a {@code NoSuchFieldError: field_78223_m} on
     * {@code RenderWorldLastEvent.partialTicks}: the flat table holds
     * {@code partialTicks -> field_78223_m} from {@code ModelDragon}, the
     * fallback asked it about a Forge class, and the game threw once per frame
     * for as long as it was open.
     */
    private static boolean isRenamedClass(String ownerInternal) {
        return CLASSES.containsKey(ownerInternal)
                || ENV_CLASSES.contains(ownerInternal)
                || ownerInternal.startsWith("net/minecraft/");
    }

    /**
     * Keeps the flat table honest: a name that maps to two different things
     * is dropped rather than guessed at.
     */
    private static void remember(Map<String, String> flat, Set<String> ambiguous,
                                 String key, String value) {
        if (ambiguous.contains(key)) {
            return;
        }
        String previous = flat.put(key, value);
        if (previous != null && !previous.equals(value)) {
            flat.remove(key);
            ambiguous.add(key);
        }
    }
    /**
     * Every name the target method might be under: the MCP name and whatever
     * the table says. Returning a list rather than one answer means a hook
     * still lands when the table is incomplete but the name is not.
     */
    public static String[] methodNames(String mcpOwner, String mcpName) {
        return names(METHODS, METHODS_FLAT, mcpOwner, mcpName, null);
    }

    public static String[] fieldNames(String mcpOwner, String mcpName) {
        return names(FIELDS, FIELDS_FLAT, mcpOwner, mcpName, null);
    }

    private static String[] names(Map<String, String> table, Map<String, String> flat,
                                  String owner, String name, String descriptor) {
        prepare();
        Set<String> found = new LinkedHashSet<String>();
        found.add(name);
        if (needed) {
            String ownerKey = owner == null ? "" : owner.replace('.', '/');
            collect(table, ownerKey, name, descriptor, found);
            // A hook names the class that receives the call; the table is keyed
            // by the class that declares the member. Missing that is what left
            // four of five EntityPlayerSP hooks on the floor of a live game:
            // onUpdate and friends live one class up, and some of them live on
            // an interface above that.
            for (String parent : hierarchy(ownerKey)) {
                if (!parent.equals(ownerKey)) {
                    collect(table, parent, name, descriptor, found);
                }
            }
            if (found.size() == 1) {
                // Nothing but the MCP name so far. The flat table holds the
                // names that mean the same thing on every owner, and it is the
                // last resort: with an owner that could not be walked it is
                // still better than missing the hook entirely.
                if (descriptor == null) {
                    String prefix = name + '\u0000';
                    for (Map.Entry<String, String> entry : flat.entrySet()) {
                        if (entry.getKey().startsWith(prefix)) {
                            found.add(entry.getValue());
                        }
                    }
                    String direct = flat.get(name);
                    if (direct != null) {
                        found.add(direct);
                    }
                } else {
                    String direct = flat.get(name + '\u0000' + descriptor);
                    if (direct != null) {
                        found.add(direct);
                    }
                }
            }
        }
        return found.toArray(new String[found.size()]);
    }

    /**
     * One class's entry for a member, under either of the class's names.
     *
     * <p>The table is keyed by MCP names because that is what the client source
     * is written in, but the running game can hand this an environment name --
     * a hierarchy walked inside a Badlion client comes back with {@code wn}, not
     * {@code EntityPlayer}. Looking up both is what makes that walk useful.
     */
    private static String member(Map<String, String> table, String ownerKey, String name,
                                 String descriptor) {
        String direct = table.get(key(ownerKey, name, descriptor));
        if (direct != null) {
            return direct;
        }
        String mcp = mcpName(ownerKey);
        if (!mcp.equals(ownerKey)) {
            direct = table.get(key(mcp, name, descriptor));
        }
        return direct;
    }

    /** One class's entries for a member, exact descriptor first then by name. */
    private static void collect(Map<String, String> table, String ownerKey, String name,
                                String descriptor, Set<String> found) {
        String direct = member(table, ownerKey, name, descriptor);
        if (direct != null) {
            found.add(direct);
        }
        if (descriptor == null) {
            scan(table, ownerKey, name, found);
            // The hierarchy walk hands this environment names -- a Badlion walk
            // comes back with "pk", not "Entity" -- while the table is keyed by
            // MCP names. member() above has always tried both; the by-name scan
            // did not, so a member declared on an ancestor was only found when
            // the walk happened to reach it under its MCP name. That is every
            // hook the game reaches through a superclass: EntityPlayerSP.onUpdate
            // is Entity.onUpdate, and its notch name t_ was never a candidate,
            // which read as "no method onUpdate" on a class that has it.
            String mcp = mcpName(ownerKey);
            if (!mcp.equals(ownerKey)) {
                scan(table, mcp, name, found);
            }
        }
    }

    /** Every notch name the table gives this owner and member name. */
    private static void scan(Map<String, String> table, String ownerKey, String name,
                             Set<String> found) {
        String prefix = ownerKey + '\u0000' + name + '\u0000';
        for (Map.Entry<String, String> entry : table.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                found.add(entry.getValue());
            }
        }
    }

    /**
     * Descriptors as candidate sets, because the environment's descriptor and
     * ours can differ in ways that matter ({@code Lnet/minecraft/...;} does not
     * change, but a nested type or an array can).
     */
    public static Set<String> descriptors(String mcpDescriptor) {
        Set<String> found = new LinkedHashSet<String>();
        if (mcpDescriptor == null) {
            return found;
        }
        found.add(mcpDescriptor);
        String remapped = remapDescriptor(mcpDescriptor);
        if (!remapped.equals(mcpDescriptor)) {
            found.add(remapped);
        }
        return found;
    }

    /** Rewrites every class mentioned in a descriptor. */
    public static String remapDescriptor(String descriptor) {
        if (descriptor == null) {
            return null;
        }
        prepare();
        if (!needed || descriptor.indexOf('L') < 0) {
            return descriptor;
        }
        StringBuilder out = new StringBuilder(descriptor.length() + 16);
        int index = 0;
        while (index < descriptor.length()) {
            char c = descriptor.charAt(index);
            if (c != 'L') {
                out.append(c);
                index++;
                continue;
            }
            int end = descriptor.indexOf(';', index);
            if (end < 0) {
                out.append(descriptor.substring(index));
                break;
            }
            String internal = descriptor.substring(index + 1, end);
            String mapped = CLASSES.get(internal);
            out.append('L').append(mapped == null ? internal : mapped).append(';');
            index = end + 1;
        }
        return out.toString();
    }

    /** Owner names to accept for a hooked class: MCP and the environment's. */
    public static Set<String> ownerNames(String mcpDotted) {
        Set<String> found = new LinkedHashSet<String>();
        found.add(mcpDotted.replace('.', '/'));
        String mapped = mapClass(mcpDotted);
        if (mapped != null) {
            found.add(mapped.replace('.', '/'));
        }
        return found;
    }

    /** Entries that were asked for and not found, for the end-of-run report. */
    public static List<String> failures() {
        synchronized (FAILURES) {
            return new ArrayList<String>(FAILURES);
        }
    }

    static void failed(String what) {
        FAILURES.add(what);
    }

    private static String key(String owner, String name, String descriptor) {
        return (owner == null ? "" : owner.replace('.', '/')) + '\u0000' + name + '\u0000'
                + (descriptor == null ? "" : descriptor);
    }

    /**
     * Self-check for the descriptor rewriter, which is the one part of this
     * class with logic rather than lookups in it.
     */
    static void selfCheck() {
        CLASSES.put("net/minecraft/client/Minecraft", "ave");
        CLASSES.put("net/minecraft/entity/Entity", "pk");
        needed = true;
        check("()V", "()V");
        check("(Lnet/minecraft/entity/Entity;)V", "(Lpk;)V");
        check("(DDLnet/minecraft/entity/Entity;I)Lnet/minecraft/client/Minecraft;",
                "(DDLpk;I)Lave;");
        check("[Lnet/minecraft/entity/Entity;", "[Lpk;");
        check("(Ljava/lang/String;[[Lnet/minecraft/entity/Entity;)V",
                "(Ljava/lang/String;[[Lpk;)V");
        check("(Lnet/minecraft/entity/Entity", "(Lnet/minecraft/entity/Entity");
        needed = false;
        CLASSES.clear();
    }

    private static void check(String input, String expected) {
        String actual = remapDescriptorKeepingState(input);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("remap(" + input + ") = " + actual + ", wanted "
                    + expected);
        }
    }

    private static String remapDescriptorKeepingState(String descriptor) {
        boolean saved = needed;
        needed = true;
        try {
            return remapDescriptor(descriptor);
        } finally {
            needed = saved;
        }
    }
}
