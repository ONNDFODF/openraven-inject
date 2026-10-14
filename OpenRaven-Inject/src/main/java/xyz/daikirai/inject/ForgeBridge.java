package xyz.daikirai.inject;

import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;

/**
 * Fires the Forge events the client listens to -- but only when the client
 * brought its own Forge, which is what the shim in the mcp and notch jars is.
 *
 * <p>On a Forge client the game fires all of these itself from
 * {@code FMLCommonHandler} and {@code ForgeHooksClient}, and firing them a
 * second time would tick every module twice. The two are told apart by
 * {@code MinecraftForge.SHIM}, a field only the shim's class has; the real
 * class answers NoSuchFieldException and this class stands down.
 *
 * <p>The hooks that call in here are the ones the client already installs, so
 * the events arrive on the game thread at the point in the frame where Forge
 * would have posted them -- a tick for the tick events, the end of the world
 * render pass for {@code RenderWorldLastEvent}.
 */
public final class ForgeBridge {

    private static final boolean SHIM = detect();

    private ForgeBridge() {
    }

    private static boolean detect() {
        try {
            Field marker = MinecraftForge.class.getField("SHIM");
            return marker.getBoolean(null);
        } catch (Throwable realForge) {
            return false;
        }
    }

    private static float partialTicks() {
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getMinecraft();
            net.minecraft.util.Timer timer = InjectAccess.timer(minecraft);
            return timer == null ? 1.0F : timer.renderPartialTicks;
        } catch (Throwable failed) {
            return 1.0F;
        }
    }

    /** The partial ticks of the frame being rendered, kept from the hook that
     *  can read them: a RETURN hook cannot name its method's arguments. */
    private static volatile float framePartialTicks = 1.0F;

    /** True once this frame's overlays have been posted: two anchors, one event. */
    private static volatile boolean worldFired;

    /**
     * The end of an entity's living update, once per entity per tick.
     *
     * <p>Forge fires {@code LivingUpdateEvent} from its patch on the same
     * method, so this is the shim standing in for that patch -- and standing
     * down when the real Forge is on the class path, or every listener would
     * run twice.
     */
    public static void livingUpdate(net.minecraft.entity.EntityLivingBase entity) {
        if (!SHIM || entity == null) {
            return;
        }
        MinecraftForge.EVENT_BUS.post(
                new net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent(entity));
    }

    /** Once per client tick, from the {@code runTick} hook's return. */
    public static void clientTick() {
        if (!SHIM) {
            return;
        }
        MinecraftForge.EVENT_BUS.post(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        MinecraftForge.EVENT_BUS.post(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
    }

    // ------------------------------------------------------------- packets

    /**
     * The frame being rendered: start, then end.
     *
     * <p>Where these are fired is the whole point. The client draws its HUD on
     * {@code Phase.END}, so that has to be after the frame -- firing it at the
     * start of the tick draws a HUD the world then paints over, and a HUD that
     * does not show is exactly what that looks like.
     */
    /**
     * The head of the frame: it only remembers the frame, it does not announce
     * it. The render tick's START is where the client rebuilds its frustum, and
     * a frustum built from the matrices at the head of the frame -- which are
     * still the previous frame's GUI -- culls the whole world away.
     */
    public static void frameStart(float partialTicks) {
        if (!SHIM) {
            return;
        }
        framePartialTicks = partialTicks;
        worldFired = false;
    }

    /**
     * The render tick's START, announced from the head of the last world pass:
     * the matrices there are the world's, which is what a frustum has to be
     * built from.
     */
    public static void renderTickStart() {
        if (!SHIM) {
            return;
        }
        MinecraftForge.EVENT_BUS.post(new TickEvent.RenderTickEvent(TickEvent.Phase.START, framePartialTicks));
    }

    public static void renderTickEnd() {
        if (!SHIM) {
            return;
        }
        MinecraftForge.EVENT_BUS.post(new TickEvent.RenderTickEvent(TickEvent.Phase.END, framePartialTicks));
    }


    /** The pass the game is in, and the partial ticks it was given. */
    private static volatile int worldPass = -1;

    public static void captureWorldPass(int pass, float partialTicks) {
        worldPass = pass;
        framePartialTicks = partialTicks;
        if (pass == 2) {
            captureMatrices();
            renderTickStart();
        }
    }

    /**
     * The world's projection and modelview, kept while the rest of the frame
     * changes them.
     *
     * <p>The overlays are drawn with whatever matrices are current, and by the
     * end of the frame the game has drawn the hand, which sets up a projection
     * of its own. That is what an ESP that lines up one frame and is off the
     * next is: the same overlay drawn with a matrix that is sometimes the
     * world's and sometimes the hand's. So the world's matrices are read at the
     * head of the last pass, and put back before the overlay is drawn.
     */
    private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);
    private static volatile boolean matricesHeld;

    private static void captureMatrices() {
        // clear() and never flip(): LWJGL 2's glGetFloat writes where the buffer
        // already is and does not move the position. Flipping after it makes the
        // limit zero, and the next frame's glGetFloat throws -- "Number of
        // remaining buffer elements is 0" -- from inside a render pass, which is
        // a client that dies the moment a world is loaded.
        PROJECTION.clear();
        MODELVIEW.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
        PROJECTION.rewind();
        MODELVIEW.rewind();
        matricesHeld = true;
    }

    private static void restoreMatrices() {
        if (!matricesHeld) {
            return;
        }
        PROJECTION.rewind();
        MODELVIEW.rewind();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadMatrix(PROJECTION);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadMatrix(MODELVIEW);
    }

    /** Fires from the last world pass's return, for clients without the hand call. */
    public static void fallbackWorldRender() {
        if (worldPass != 2) {
            return;
        }
        restoreMatrices();
        renderWorld(framePartialTicks);
    }
    /**
     * The overlays, drawn where Forge draws them: once a frame, just before the
     * hand, with the world's matrices still on the stack. The partial ticks are
     * the ones this frame rendered with, which is what every position the
     * overlays compute is interpolated by.
     */
    public static void renderWorld(float partialTicks) {
        if (!SHIM || worldFired) {
            return;
        }
        worldFired = true;
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getMinecraft();
        MinecraftForge.EVENT_BUS.post(new RenderWorldLastEvent(
                minecraft == null ? null : minecraft.renderGlobal, partialTicks));
    }
}