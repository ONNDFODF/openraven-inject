package net.minecraftforge.client.event;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraftforge.fml.common.eventhandler.Event;

/** Around a player's render, which is where the client's cape layer hangs off. */
public class RenderPlayerEvent extends Event {

    public final AbstractClientPlayer entity;
    public final RenderPlayer renderer;
    public final double x;
    public final double y;
    public final double z;
    public final float partialTicks;

    public RenderPlayerEvent(AbstractClientPlayer entity, RenderPlayer renderer,
                             double x, double y, double z, float partialTicks) {
        this.entity = entity;
        this.renderer = renderer;
        this.x = x;
        this.y = y;
        this.z = z;
        this.partialTicks = partialTicks;
    }

    public static class Pre extends RenderPlayerEvent {
        public Pre(AbstractClientPlayer entity, RenderPlayer renderer,
                   double x, double y, double z, float partialTicks) {
            super(entity, renderer, x, y, z, partialTicks);
        }
    }

    public static class Post extends RenderPlayerEvent {
        public Post(AbstractClientPlayer entity, RenderPlayer renderer,
                    double x, double y, double z, float partialTicks) {
            super(entity, renderer, x, y, z, partialTicks);
        }
    }
}