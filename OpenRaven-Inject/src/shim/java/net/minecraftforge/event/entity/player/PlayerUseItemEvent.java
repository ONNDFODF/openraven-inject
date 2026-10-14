package net.minecraftforge.event.entity.player;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

/** An item being used, around the use. */
public class PlayerUseItemEvent extends PlayerEvent {

    public final ItemStack item;
    public final int duration;

    public PlayerUseItemEvent(EntityPlayer player, ItemStack item, int duration) {
        super(player);
        this.item = item;
        this.duration = duration;
    }

    public static class Start extends PlayerUseItemEvent {
        public Start(EntityPlayer player, ItemStack item, int duration) {
            super(player, item, duration);
        }
    }

    public static class Stop extends PlayerUseItemEvent {
        public Stop(EntityPlayer player, ItemStack item, int duration) {
            super(player, item, duration);
        }
    }

    public static class Finish extends PlayerUseItemEvent {
        public Finish(EntityPlayer player, ItemStack item, int duration) {
            super(player, item, duration);
        }
    }

    public static class Tick extends PlayerUseItemEvent {
        public Tick(EntityPlayer player, ItemStack item, int duration) {
            super(player, item, duration);
        }
    }
}