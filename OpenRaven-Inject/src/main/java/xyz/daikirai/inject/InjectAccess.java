package xyz.daikirai.inject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Timer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * The injected build's replacement for the {@code @Accessor} and
 * {@code @Invoker} interfaces ({@code IEntityPlayerSP}, {@code IMinecraft},
 * {@code IPlayerControllerMP}).
 *
 * Those interfaces work because Mixin makes the game class implement them at
 * load time. There is no Mixin here, and no way to make a class implement an
 * interface after it has been defined, so the access goes through reflection
 * instead.
 *
 * <p>Reflection is not the fast path, but these are read a few dozen times per
 * tick, not per frame per entity, and each lookup is done once and cached. If
 * that ever shows up in a profile, the alternative is to have
 * {@link HookTransformer} emit real accessor methods into the target class --
 * more code, same result, and not worth writing before something measures
 * slow.
 *
 * <p>{@link #selfCheck()} resolves every name against the running game and
 * throws on the first miss. {@link NativeBridge} calls it at install time: a
 * field renamed by a mapping change should be an error at startup, not an
 * NPE two minutes into a game.
 */
public final class InjectAccess {

    private static final Map<String, Field> FIELDS = new HashMap<String, Field>();
    private static final Map<String, Method> METHODS = new HashMap<String, Method>();

    private InjectAccess() {
    }

    // --------------------------------------------------- EntityPlayerSP fields

    /** Field names on {@code EntityPlayerSP}, in MCP form. */
    private static final String[] PLAYER_FIELDS = {
            "serverSprintState",
            "serverSneakState",
            "lastReportedPosX",
            "lastReportedPosY",
            "lastReportedPosZ",
            "lastReportedYaw",
            "lastReportedPitch",
            "positionUpdateTicks",
            "sprintToggleTimer"
    };

    public static boolean serverSprintState(EntityPlayerSP player) {
        return readBoolean(playerField("serverSprintState"), player);
    }

    public static void serverSprintState(EntityPlayerSP player, boolean value) {
        set(playerField("serverSprintState"), player, Boolean.valueOf(value));
    }

    public static boolean serverSneakState(EntityPlayerSP player) {
        return readBoolean(playerField("serverSneakState"), player);
    }

    public static void serverSneakState(EntityPlayerSP player, boolean value) {
        set(playerField("serverSneakState"), player, Boolean.valueOf(value));
    }

    public static double lastReportedPosX(EntityPlayerSP player) {
        return readDouble(playerField("lastReportedPosX"), player);
    }

    public static void lastReportedPosX(EntityPlayerSP player, double value) {
        set(playerField("lastReportedPosX"), player, Double.valueOf(value));
    }

    public static double lastReportedPosY(EntityPlayerSP player) {
        return readDouble(playerField("lastReportedPosY"), player);
    }

    public static void lastReportedPosY(EntityPlayerSP player, double value) {
        set(playerField("lastReportedPosY"), player, Double.valueOf(value));
    }

    public static double lastReportedPosZ(EntityPlayerSP player) {
        return readDouble(playerField("lastReportedPosZ"), player);
    }

    public static void lastReportedPosZ(EntityPlayerSP player, double value) {
        set(playerField("lastReportedPosZ"), player, Double.valueOf(value));
    }

    public static float lastReportedYaw(EntityPlayerSP player) {
        return readFloat(playerField("lastReportedYaw"), player);
    }

    public static void lastReportedYaw(EntityPlayerSP player, float value) {
        set(playerField("lastReportedYaw"), player, Float.valueOf(value));
    }

    public static float lastReportedPitch(EntityPlayerSP player) {
        return readFloat(playerField("lastReportedPitch"), player);
    }

    public static void lastReportedPitch(EntityPlayerSP player, float value) {
        set(playerField("lastReportedPitch"), player, Float.valueOf(value));
    }

    public static int positionUpdateTicks(EntityPlayerSP player) {
        return readInt(playerField("positionUpdateTicks"), player);
    }

    public static void positionUpdateTicks(EntityPlayerSP player, int value) {
        set(playerField("positionUpdateTicks"), player, Integer.valueOf(value));
    }

    public static void sprintToggleTimer(EntityPlayerSP player, int value) {
        set(playerField("sprintToggleTimer"), player, Integer.valueOf(value));
    }

    // ------------------------------------------------------- Minecraft members

    public static Timer timer(Minecraft minecraft) {
        return (Timer) read(minecraftField("timer"), minecraft);
    }

    public static void clickMouse(Minecraft minecraft) {
        invoke(minecraftMethod("clickMouse"), minecraft);
    }

    public static void rightClickMouse(Minecraft minecraft) {
        invoke(minecraftMethod("rightClickMouse"), minecraft);
    }

    // ------------------------------------------------------ PlayerControllerMP

    public static void syncCurrentPlayItem(Object playerController) {
        invoke(method(playerController.getClass(), "syncCurrentPlayItem"), playerController);
    }

    // ------------------------------------------------------ ItemRenderer field

    public static net.minecraft.item.ItemStack itemToRender(
            net.minecraft.client.renderer.ItemRenderer renderer) {
        return (net.minecraft.item.ItemStack) field(
                net.minecraft.client.renderer.ItemRenderer.class, "itemToRender", renderer);
    }

    /**
     * {@code EntityPlayer.itemInUseCount}, which the game keeps private. The
     * Mixin build reads it directly because its handler is merged into the
     * class; here it is one more reflected name, and one the mapping table has
     * to translate.
     */
    public static int itemInUseCount(EntityPlayer player) {
        return readInt(field(EntityPlayer.class, "itemInUseCount"), player);
    }

    // ------------------------------------------------------------- plumbing

    private static Field playerField(String name) {
        return field(EntityPlayerSP.class, name);
    }

    private static Field minecraftField(String name) {
        return field(Minecraft.class, name);
    }

    /**
     * The names to try are the MCP one and whatever the mapping table says the
     * game calls it. The string in the source cannot be renamed with the class
     * it sits in -- a string is a string -- so a renamed field has to be found
     * by its environment name here or not at all.
     */
    private static Field field(Class<?> owner, String name) {
        String key = owner.getName() + '#' + name;
        Field cached = FIELDS.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> level : hierarchyOf(owner)) {
            for (String candidate : MappingBridge.fieldNames(level.getName(), name)) {
                try {
                    Field found = level.getDeclaredField(candidate);
                    found.setAccessible(true);
                    FIELDS.put(key, found);
                    return found;
                } catch (NoSuchFieldException missing) {
                    // the next candidate, if there is one
                }
            }
        }
        throw new IllegalStateException("no field " + owner.getName() + '.' + name
                + " -- the mapping table does not match this game build");
    }

    /**
     * The class and everything it inherits from, nearest first.
     *
     * <p>The walk is the point. The class named here is the one the game is
     * running, not the one the member was written against: {@code
     * EntityPlayerSP} inherits {@code itemInUseCount} from {@code
     * EntityLivingBase}, and with OptiFine installed the player controller is
     * {@code net.optifine.override.PlayerControllerOF}, which inherits {@code
     * syncCurrentPlayItem} from {@code PlayerControllerMP} and declares none of
     * it. Asking the leaf class for a declared member is how Scaffold turned
     * into a crash report: the lookup threw while a tick was in flight.
     */
    private static List<Class<?>> hierarchyOf(Class<?> owner) {
        List<Class<?>> order = new ArrayList<Class<?>>();
        Set<Class<?>> seen = new HashSet<Class<?>>();
        ArrayDeque<Class<?>> queue = new ArrayDeque<Class<?>>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            if (current == null || current == Object.class || !seen.add(current)) {
                continue;
            }
            order.add(current);
            if (current.getSuperclass() != null) {
                queue.add(current.getSuperclass());
            }
            for (Class<?> face : current.getInterfaces()) {
                queue.add(face);
            }
        }
        return order;
    }

    private static Method minecraftMethod(String name) {
        return method(Minecraft.class, name);
    }

    private static Method method(Class<?> owner, String name) {
        String key = owner.getName() + '#' + name + "()";
        Method cached = METHODS.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> level : hierarchyOf(owner)) {
            for (String candidate : MappingBridge.methodNames(level.getName(), name)) {
                try {
                    Method found = level.getDeclaredMethod(candidate);
                    found.setAccessible(true);
                    METHODS.put(key, found);
                    return found;
                } catch (NoSuchMethodException missing) {
                    // the next candidate, if there is one
                }
            }
        }
        throw new IllegalStateException("no method " + owner.getName() + '.' + name
                + "() -- the mapping table does not match this game build");
    }

    private static Object field(Class<?> owner, String name, Object target) {
        try {
            return field(owner, name).get(target);
        } catch (IllegalAccessException refused) {
            throw new IllegalStateException("cannot read " + owner.getName() + '.' + name, refused);
        }
    }

    private static Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException refused) {
            throw new IllegalStateException("cannot read " + field, refused);
        }
    }

    private static boolean readBoolean(Field field, Object target) {
        try {
            return field.getBoolean(target);
        } catch (IllegalAccessException refused) {
            throw new IllegalStateException("cannot read " + field, refused);
        }
    }

    private static int readInt(Field field, Object target) {
        try {
            return field.getInt(target);
        } catch (IllegalAccessException refused) {
            throw new IllegalStateException("cannot read " + field, refused);
        }
    }

    private static float readFloat(Field field, Object target) {
        try {
            return field.getFloat(target);
        } catch (IllegalAccessException refused) {
            throw new IllegalStateException("cannot read " + field, refused);
        }
    }

    private static double readDouble(Field field, Object target) {
        try {
            return field.getDouble(target);
        } catch (IllegalAccessException refused) {
            throw new IllegalStateException("cannot read " + field, refused);
        }
    }

    private static void set(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException refused) {
            throw new IllegalStateException("cannot write " + field, refused);
        }
    }

    private static void invoke(Method method, Object target) {
        try {
            method.invoke(target);
        } catch (ReflectiveOperationException failed) {
            throw new IllegalStateException("cannot call " + method, failed);
        }
    }

    /**
     * Resolves every name this class uses against the running game.
     *
     * Run at install time so a mapping mismatch is reported once, by name,
     * before anything depends on it. Throws rather than returning a list: the
     * caller is a starting client, and there is nothing useful to do with a
     * half-resolved set.
     */
    public static void selfCheck() {
        StringBuilder missing = new StringBuilder();
        for (String name : PLAYER_FIELDS) {
            try {
                field(EntityPlayerSP.class, name);
            } catch (RuntimeException failed) {
                append(missing, failed);
            }
        }
        check(missing, Minecraft.class, "timer", false);
        check(missing, EntityPlayer.class, "itemInUseCount", false);
        check(missing, Minecraft.class, "clickMouse", true);
        check(missing, Minecraft.class, "rightClickMouse", true);
        check(missing, net.minecraft.client.multiplayer.PlayerControllerMP.class,
                "syncCurrentPlayItem", true);
        check(missing, net.minecraft.client.renderer.ItemRenderer.class, "itemToRender", false);
        if (missing.length() > 0) {
            throw new IllegalStateException("accessor names that do not resolve in this game:"
                    + missing);
        }
        Log.line("accessor names all resolve (" + FIELDS.size() + " fields, "
                + METHODS.size() + " methods)");
    }

    private static void check(StringBuilder missing, Class<?> owner, String name,
                              boolean isMethod) {
        try {
            if (isMethod) {
                method(owner, name);
            } else {
                field(owner, name);
            }
        } catch (RuntimeException failed) {
            append(missing, failed);
        }
    }

    private static void append(StringBuilder missing, RuntimeException failed) {
        missing.append(System.lineSeparator()).append("  ").append(failed.getMessage());
    }
}
