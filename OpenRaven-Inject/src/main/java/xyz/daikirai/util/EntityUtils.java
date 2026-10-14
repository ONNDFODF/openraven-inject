package xyz.daikirai.util;

import net.minecraft.entity.Entity;

import java.lang.reflect.Method;

/**
 * The one Entity method this client uses that vanilla does not have.
 *
 * <p>{@code canRiderInteract} is Forge's: a Forge client answers it for real,
 * and everything else -- a Badlion client, a plain vanilla one -- answers the
 * same thing Forge's default does. Looking it up once and remembering the
 * answer keeps both honest without a second build of this class.
 */
public final class EntityUtils {

    private static Method canRiderInteract;
    private static boolean lookedUp;

    private EntityUtils() {
    }

    public static boolean canRiderInteract(Entity entity) {
        if (entity == null) {
            return true;
        }
        if (!lookedUp) {
            lookedUp = true;
            try {
                canRiderInteract = entity.getClass().getMethod("canRiderInteract");
            } catch (Throwable absent) {
                canRiderInteract = null;
            }
        }
        if (canRiderInteract == null) {
            return true;
        }
        try {
            Object answer = canRiderInteract.invoke(entity);
            return answer instanceof Boolean ? (Boolean) answer : true;
        } catch (Throwable failed) {
            return true;
        }
    }
}