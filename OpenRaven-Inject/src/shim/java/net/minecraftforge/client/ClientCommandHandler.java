package net.minecraftforge.client;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge's client command registry, which the client registers one command in
 * and runs its own commands through.
 *
 * <p>Enough of the real thing to answer both: a command that names it is the
 * one that runs, and the return value says whether anything ran.
 */
public final class ClientCommandHandler {

    public static final ClientCommandHandler instance = new ClientCommandHandler();

    private final List<ICommand> commands = new ArrayList<ICommand>();

    private ClientCommandHandler() {
    }

    public void registerCommand(ICommand command) {
        if (command != null && !commands.contains(command)) {
            commands.add(command);
        }
    }

    /** Runs the command the name belongs to, and answers 1 when one did. */
    public int executeCommand(ICommandSender sender, String text) {
        String line = text.startsWith("/") ? text.substring(1) : text;
        int space = line.indexOf(' ');
        String name = space < 0 ? line : line.substring(0, space);
        String arguments = space < 0 ? "" : line.substring(space + 1);
        for (ICommand command : commands) {
            if (!command.getCommandName().equalsIgnoreCase(name)) {
                continue;
            }
            try {
                if (command.canCommandSenderUseCommand(sender)) {
                    command.processCommand(sender, arguments.split(" "));
                    return 1;
                }
            } catch (Throwable failed) {
                return 0;
            }
        }
        return 0;
    }

    public List<ICommand> commands() {
        return commands;
    }
}