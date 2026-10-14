package net.minecraftforge.client.event;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * The end of a world render pass, which is where the client draws its own
 * overlays -- ESP, nametags, tracers.
 *
 * <p>The constructor takes what Forge's takes, signature for signature: the
 * client builds this event from a hook, and the same source has to compile
 * against this class and against Forge's.
 */
public class RenderWorldLastEvent extends Event {

    public final RenderGlobal context;
    public final float partialTicks;

    public RenderWorldLastEvent(RenderGlobal context, float partialTicks) {
        this.context = context;
        this.partialTicks = partialTicks;
    }
}