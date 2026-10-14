package net.minecraftforge.client.event;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.eventhandler.Event;

/** The block outline the game is about to draw. Cancelling it hides the outline. */
public class DrawBlockHighlightEvent extends Event {

    public final EntityPlayer player;
    public final MovingObjectPosition target;
    public final int subID;
    public final float partialTicks;

    public DrawBlockHighlightEvent(EntityPlayer player, MovingObjectPosition target,
                                   int subID, float partialTicks) {
        this.player = player;
        this.target = target;
        this.subID = subID;
        this.partialTicks = partialTicks;
    }

    public BlockPos getTarget() {
        return target == null ? null : target.getBlockPos();
    }
}