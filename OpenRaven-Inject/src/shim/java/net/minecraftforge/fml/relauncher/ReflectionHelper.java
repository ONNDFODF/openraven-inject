package net.minecraftforge.fml.relauncher;

import xyz.daikirai.inject.MappingBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Forge's reflection helper, which the client uses to reach private members by
 * both the MCP name and the SRG one.
 *
 * <p>Those two names are the ones a production Forge client has: Forge renames
 * every member to its SRG name and leaves the MCP name in the source. On a
 * client whose classes are still obfuscated -- Badlion, a vanilla release --
 * neither name is the one the member has, and every lookup here used to answer
 * null. The client does not treat null as fatal, so the modules that use this
 * went quietly dead, and one of them -- ShadersModule -- kept going until the
 * ClickGui asked a null setting for its value and took the game down with it.
 *
 * <p>So each name asked for is also translated through the client's own mapping
 * table and the environment spelling tried. That is the same rule as everywhere
 * else in this build: a name is only correct if the class it lands on really
 * has that member, and the table is what knows. The member is searched up the
 * hierarchy too, because a caller names the type it believes declares it, and
 * with OptiFine or a launcher in the way that is not always the class that
 * does.
 *
 * <p>On an MCP client the translation is the identity and this behaves exactly
 * as Forge's does.
 */
public final class ReflectionHelper {

    private ReflectionHelper() {
    }

    public static Field findField(Class<?> owner, String... names) {
        if (owner == null || names == null) {
            return null;
        }
        for (String name : names) {
            for (String candidate : candidates(owner, name, true)) {
                Field field = declaredField(owner, candidate);
                if (field != null) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        return null;
    }

    public static <E> Method findMethod(Class<? super E> owner, E instance, String[] names,
                                        Class<?>... parameterTypes) {
        if (owner == null || names == null) {
            return null;
        }
        for (String name : names) {
            for (String candidate : candidates(owner, name, false)) {
                Method method = declaredMethod(owner, candidate, parameterTypes);
                if (method != null) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    public static Method findMethod(Class<?> owner, String[] names, Class<?>... parameterTypes) {
        return findMethod(owner, null, names, parameterTypes);
    }

    public static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        return findMethod(owner, null, new String[]{name}, parameterTypes);
    }

    @SuppressWarnings("unchecked")
    public static <T, E> T getPrivateValue(Class<? super E> owner, E instance, String... names) {
        Field field = findField(owner, names);
        if (field == null) {
            return null;
        }
        try {
            return (T) field.get(instance);
        } catch (Throwable unreadable) {
            return null;
        }
    }

    public static <T, E> T getPrivateValue(Class<? super E> owner, E instance, int index) {
        if (owner == null) {
            return null;
        }
        try {
            Field field = owner.getDeclaredFields()[index];
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            T value = (T) field.get(instance);
            return value;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    public static <T, E> void setPrivateValue(Class<? super T> owner, T instance, E value,
                                              String... names) {
        Field field = findField(owner, names);
        if (field == null) {
            return;
        }
        try {
            field.set(instance, value);
        } catch (Throwable unwritable) {
            // nothing to do but leave the field as it was
        }
    }

    public static <T, E> void setPrivateValue(Class<? super T> owner, T instance, E value,
                                              int index) {
        if (owner == null) {
            return;
        }
        try {
            Field field = owner.getDeclaredFields()[index];
            field.setAccessible(true);
            field.set(instance, value);
        } catch (Throwable unwritable) {
            // nothing to do but leave the field as it was
        }
    }

    public static Class<? super Object> getClass(ClassLoader loader, String... names) {
        for (String name : names) {
            try {
                return (Class<? super Object>) Class.forName(name, false, loader);
            } catch (Throwable missing) {
                // the next name
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- lookup

    /** The names to try: what the caller asked for, then the table's spelling. */
    private static Set<String> candidates(Class<?> owner, String name, boolean field) {
        Set<String> found = new LinkedHashSet<String>();
        found.add(name);
        try {
            String mcpOwner = MappingBridge.mcpClassName(owner);
            String[] translated = field
                    ? MappingBridge.fieldNames(mcpOwner, name)
                    : MappingBridge.methodNames(mcpOwner, name);
            for (String candidate : translated) {
                found.add(candidate);
            }
        } catch (Throwable noTable) {
            // No mapping table, or a class that is not in it: the names the
            // caller wrote are all there is, which is what Forge does.
        }
        return found;
    }

    private static Field declaredField(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null && type != Object.class;
                type = type.getSuperclass()) {
            try {
                return type.getDeclaredField(name);
            } catch (Throwable missing) {
                // the next class up
            }
        }
        return null;
    }

    private static Method declaredMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        for (Class<?> type = owner; type != null && type != Object.class;
                type = type.getSuperclass()) {
            try {
                return type.getDeclaredMethod(name, parameterTypes);
            } catch (Throwable missing) {
                // the interfaces, then the next class up
            }
            for (Class<?> face : type.getInterfaces()) {
                try {
                    return face.getDeclaredMethod(name, parameterTypes);
                } catch (Throwable alsoMissing) {
                    // the next interface
                }
            }
        }
        return null;
    }
}