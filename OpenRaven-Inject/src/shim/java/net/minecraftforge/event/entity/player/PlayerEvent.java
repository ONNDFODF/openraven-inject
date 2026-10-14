package net.minecraftforge.event.entity.player;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.living.LivingEvent;

/** Base class for the player events. */
public class PlayerEvent extends LivingEvent {

    /** Forge's name for it, and the one the client reads. */
    public final EntityPlayer entityPlayer;

    public PlayerEvent(EntityPlayer player) {
        super(player);
        this.entityPlayer = player;
    }
}