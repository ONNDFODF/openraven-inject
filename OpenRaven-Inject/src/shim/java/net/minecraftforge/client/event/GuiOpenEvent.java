package net.minecraftforge.client.event;

import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.eventhandler.Event;

/** A screen being opened; {@code gui} is null when one is being closed. */
public class GuiOpenEvent extends Event {

    public GuiScreen gui;

    public GuiOpenEvent(GuiScreen gui) {
        this.gui = gui;
    }
}