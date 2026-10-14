package net.minecraftforge.event;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

/**
 * Forge's event factory. The client calls it once, after a stack is used up;
 * with no listeners in this shim the call is the vanilla behaviour, which is
 * nothing.
 */
public final class ForgeEventFactory {

    private ForgeEventFactory() {
    }

    public static ItemStack onPlayerDestroyItem(EntityPlayer player, ItemStack stack) {
        return stack;
    }
}