package net.minecraftforge.fml.common.gameevent;

import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * The tick events, fired by {@code xyz.daikirai.inject.ForgeBridge} from the
 * hooks that are already installed.
 *
 * <p>{@code phase} and {@code renderTickTime} are the two things the client
 * reads. Forge fires START and END around each phase of the tick; the bridge
 * fires both in order, which is what a listener comparing against
 * {@code Phase.END} expects.
 */
public class TickEvent extends Event {

    public enum Phase {
        START,
        END
    }

    public enum Type {
        CLIENT,
        RENDER,
        SERVER,
        WORLD
    }

    public final Type type;
    public final Phase phase;

    public TickEvent(Type type, Phase phase) {
        this.type = type;
        this.phase = phase;
    }

    public static class ClientTickEvent extends TickEvent {
        public ClientTickEvent(Phase phase) {
            super(Type.CLIENT, phase);
        }
    }

    public static class RenderTickEvent extends TickEvent {
        /** How far into the current frame the render is, in ticks. */
        public final float renderTickTime;

        public RenderTickEvent(Phase phase, float renderTickTime) {
            super(Type.RENDER, phase);
            this.renderTickTime = renderTickTime;
        }
    }
}