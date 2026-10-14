package net.minecraftforge.client.event;

import net.minecraftforge.fml.common.eventhandler.Event;

import org.lwjgl.input.Mouse;

/**
 * A mouse event, reported the way Forge reports one: the constructor reads the
 * state LWJGL is holding right now, so a listener that builds one gets the
 * event that is being handled rather than an empty object.
 *
 * <p>Cancelling it takes the click away from the game, which is what the
 * client's ClickUtils does for its own binds.
 */
public class MouseEvent extends Event {

    public final int x;
    public final int y;
    public final int dx;
    public final int dy;
    public final int dwheel;
    public final int button;
    public final boolean buttonstate;
    public final long nanoseconds;

    public MouseEvent() {
        this.x = Mouse.getEventX();
        this.y = Mouse.getEventY();
        this.dx = Mouse.getEventDX();
        this.dy = Mouse.getEventDY();
        this.dwheel = Mouse.getEventDWheel();
        this.button = Mouse.getEventButton();
        this.buttonstate = Mouse.getEventButtonState();
        this.nanoseconds = System.nanoTime();
    }
}