package net.minecraftforge.fml.common.gameevent;

import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * Keyboard and mouse input, fired per event rather than per tick.
 *
 * <p>Nothing in this shim fires them yet: the client's listeners read the
 * current LWJGL state rather than the event, so the events carry no data. They
 * exist so the classes that subscribe to them load.
 */
public class InputEvent extends Event {

    public static class KeyInputEvent extends InputEvent {
    }

    public static class MouseInputEvent extends InputEvent {
    }
}