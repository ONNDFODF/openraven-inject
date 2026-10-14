package net.minecraftforge.common;

import net.minecraftforge.fml.common.eventhandler.EventBus;

/**
 * The one static the client reaches for: {@code MinecraftForge.EVENT_BUS}.
 *
 * <p>{@link #SHIM} is how the rest of the client tells this apart from Forge's
 * own class. On a Forge client the real one is loaded and this field is not
 * there, so the code that fires events from hooks stands down -- Forge already
 * fires them.
 */
public final class MinecraftForge {

    public static final EventBus EVENT_BUS = new EventBus();

    /** Present only when this class is the shim rather than Forge's. */
    public static final boolean SHIM = true;

    private MinecraftForge() {
    }
}