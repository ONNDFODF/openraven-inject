package net.minecraftforge.client.event;

import net.minecraft.util.IChatComponent;
import net.minecraftforge.fml.common.eventhandler.Event;

/** A chat line arriving from the server. {@code type} 2 is the action bar. */
public class ClientChatReceivedEvent extends Event {

    public final byte type;
    public final IChatComponent message;

    public ClientChatReceivedEvent(byte type, IChatComponent message) {
        this.type = type;
        this.message = message;
    }
}