package net.minecraftforge.fml.client.config;

import net.minecraft.client.gui.GuiButton;

/**
 * Forge's themed button. The client only builds them and puts them in a
 * screen's button list, so the vanilla button with the same constructor is
 * enough.
 */
public class GuiButtonExt extends GuiButton {

    public GuiButtonExt(int id, int x, int y, int width, int height, String text) {
        super(id, x, y, width, height, text);
    }
}