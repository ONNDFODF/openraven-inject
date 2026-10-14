package net.minecraftforge.common;

import net.minecraft.entity.EntityLivingBase;

/**
 * Forge's gameplay hooks. The one the client calls is the jump hook, which the
 * client already answers through its own event: this is the vanilla half, and
 * vanilla does nothing here.
 */
public final class ForgeHooks {

    private ForgeHooks() {
    }

    public static void onLivingJump(EntityLivingBase entity) {
    }
}