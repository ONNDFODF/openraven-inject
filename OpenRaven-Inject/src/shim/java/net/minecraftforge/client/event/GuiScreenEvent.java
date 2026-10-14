package net.minecraftforge.client.event;

import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * Input arriving at a screen, before and after the screen handles it. The
 * client listens to both halves: the "pre" events can cancel a click before the
 * game sees it, the "post" ones cannot.
 */
public class GuiScreenEvent extends Event {

    public final GuiScreen gui;

    public GuiScreenEvent(GuiScreen gui) {
        this.gui = gui;
    }

    public static class MouseInputEvent extends GuiScreenEvent {
        public MouseInputEvent(GuiScreen gui) {
            super(gui);
        }

        public static class Pre extends MouseInputEvent {
            public Pre(GuiScreen gui) {
                super(gui);
            }
        }

        public static class Post extends MouseInputEvent {
            public Post(GuiScreen gui) {
                super(gui);
            }
        }
    }

    public static class KeyboardInputEvent extends GuiScreenEvent {
        public KeyboardInputEvent(GuiScreen gui) {
            super(gui);
        }

        public static class Pre extends KeyboardInputEvent {
            public Pre(GuiScreen gui) {
                super(gui);
            }
        }

        public static class Post extends KeyboardInputEvent {
            public Post(GuiScreen gui) {
                super(gui);
            }
        }
    }
}