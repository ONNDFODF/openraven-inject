package xyz.daikirai.inject;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Brings the client up once the game is running, in place of the
 * {@code FMLInitializationEvent} that a mod would be handed.
 *
 * Two reasons this is not done inside the injection call itself:
 *
 * <ul>
 *   <li>The injection runs while a game class is being defined. Starting the
 *       client touches dozens of classes, some of them the one currently being
 *       defined, and the class loader is not reentrant for that.</li>
 *   <li>The client wants a world and a player to exist. Loading at the main
 *       menu and letting it start on the first tick that has both is the same
 *       thing the reader of a log would expect to see.</li>
 * </ul>
 *
 * <p>The listener is registered from {@link NativeBridge#install()} through a
 * {@code Class.forName} with initialisation on, so this class is loaded before
 * the first class is transformed and its own static initialiser is not itself
 * being patched at the time it runs.
 */
public final class Bootstrap {

    private static final int STARTS_AFTER_TICKS = 2;

    private static int ticks;
    private static boolean requested;
    private static boolean started;

    private Bootstrap() {
    }

    /** Called by the DLL, through {@link NativeBridge#start()}. */
    public static void requestStart() {
        if (requested) {
            Log.line("client start already queued");
            return;
        }
        requested = true;
        MinecraftForge.EVENT_BUS.register(new Listener());
        Log.line("client start queued for the first tick with a world");
    }

    private static void tick() {
        if (started || !requested) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.thePlayer == null || minecraft.theWorld == null) {
            return;
        }
        if (++ticks < STARTS_AFTER_TICKS) {
            return;
        }
        started = true;
        try {
            // Named by string so that a mapping change to ClickGuiMod is a log
            // line here rather than a class that will not load at all.
            Class<?> mod = Class.forName("xyz.daikirai.ClickGuiMod", true,
                    Bootstrap.class.getClassLoader());
            mod.getMethod("bootstrap").invoke(null);
        } catch (Throwable t) {
            Log.throwable("could not start the client", t);
            return;
        }
        NativeBridge.flushTransformLog();
        NativeBridge.reportUnapplied("after start");
        Log.line("---- client started ----");
    }

    /** Forge's event bus wants a concrete listener, and this is the only event. */
    public static final class Listener {
        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                Bootstrap.tick();
            }
        }
    }
}
