package xyz.daikirai.inject;

import xyz.daikirai.hook.Hooks;
import xyz.daikirai.module.Module;
import xyz.daikirai.module.ModuleManager;
import xyz.daikirai.util.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.util.MovementInput;

/**
 * The static callbacks {@link InjectHooks} inserts into game classes.
 *
 * Every method here is a fixed shape: bytecode calls it, so its descriptor is
 * an interface and cannot change without changing the hook that names it. The
 * methods must be static and must not be renamed. {@link NativeBridge} checks
 * that each one exists before anything is transformed, because the failure mode
 * otherwise is a NoSuchMethodError somewhere inside the render loop.
 *
 * <p>They deliberately do nothing except forward to {@link Hooks}. That class
 * is where the client's behaviour lives and it is shared verbatim with the
 * Mixin build; logic that grew here would be logic the Mixin build does not
 * have, which is the drift this split exists to prevent.
 */
public final class RuntimeCallbacks {

    private RuntimeCallbacks() {
    }

    // ------------------------------------------------------------- movement

    /** Head of {@code EntityPlayerSP.onUpdate}, before the super call. */
    public static void onUpdate() {
        Hooks.onUpdate();
    }

    /**
     * Head of {@code EntityPlayerSP.onUpdateWalkingPlayer}. A non-null array
     * skips the vanilla method entirely: the client sends the position packet
     * itself, and the method returns void, so the array's element is ignored.
     */
    public static Object[] onUpdateWalkingPlayer() {
        Hooks.onUpdateWalkingPlayer();
        return EMPTY_RESULT;
    }

    /**
     * A forced return with nothing in it. Allocated once: these run on the game
     * thread every tick, and a cancel that allocated an array per call would be
     * garbage for no reason.
     */
    private static final Object[] EMPTY_RESULT = new Object[1];

    /** End of {@code EntityPlayerSP.updateEntityActionState}. */
    public static void onUpdateEntityActionState() {
        Hooks.onUpdateEntityActionState();
    }

    /** End of {@code MovementInputFromOptions.updatePlayerMoveState}. */
    public static void postPlayerInput() {
        Hooks.postPlayerInput();
    }

    /**
     * The vanilla strafe write, dropped.
     *
     * <p>Nothing happens here on purpose. {@link #onPlayerSlowdownForward} is
     * the one that calls {@code Hooks.onPlayerSlowdown()}, and that call
     * reapplies <i>both</i> movement axes -- so calling it here as well would
     * square the 0.2 and leave a player who can barely move while eating. This
     * is the Mixin build's split: its strafe handler is empty and its forward
     * handler does the work.
     */
    public static void onPlayerSlowdownStrafe(MovementInput input, float value) {
    }

    /**
     * The vanilla forward write, dropped, with the decision handed to the
     * client: {@code Hooks.onPlayerSlowdown} puts the slowdown back unless the
     * NoSlow module is handling it. A write that has to disappear is what
     * {@code REPLACE_FIELD} is for; a write that has to store something else
     * is {@code REPLACE_FIELD_WITH}.
     */
    public static void onPlayerSlowdownForward(MovementInput input, float value) {
        Hooks.onPlayerSlowdown();
    }

    // --------------------------------------------------------------- combat

    /**
     * Head of {@code EntityLivingBase.jump}. A non-null array skips the vanilla
     * jump.
     */
    public static Object[] onJump(EntityLivingBase entity) {
        return Hooks.onJump(entity) ? EMPTY_RESULT : null;
    }

    /** Head of {@code EntityPlayer.isBlocking}, replacing the return value. */
    public static Object[] isBlocking(EntityPlayer player) {
        return new Object[]{Boolean.valueOf(Hooks.isBlocking(player))};
    }

    /**
     * Head of {@code EntityPlayer.getItemInUseCount}, replacing the return
     * value.
     *
     * <p>The vanilla count is a field, not an argument -- the method takes
     * none, which is why the hook loads only {@code this}.
     */
    public static Object[] getItemInUseCount(EntityPlayer player) {
        return new Object[]{Integer.valueOf(Hooks.getItemInUseCount(player,
                InjectAccess.itemInUseCount(player)))};
    }

    /**
     * The target of the attack being processed, captured at the head of the
     * method and read further down when the sprint slowdown is reached -- the
     * argument is long out of its slot by then.
     */
    private static volatile Object attackTarget;

    /**
     * Head of {@code EntityPlayer.attackTargetEntityWithCurrentItem}: keeps the
     * argument for the two replacements below.
     */
    public static void captureAttackTarget(Object target) {
        attackTarget = target;
    }

    /**
     * Replaces the {@code setSprinting(false)} call that ends the vanilla
     * slowdown, and undoes the slowdown while it is here.
     *
     * <p>The two writes just above this call multiplied the velocity by 0.6,
     * and they are not hooked: the store is reached through a
     * {@code dup; getfield; dmul; putfield} sequence that ASM refuses to
     * recompute frames for once it is edited. Multiplying back out at the call
     * that follows them is the same answer the Mixin build got by dropping the
     * writes, and it costs nothing.
     */
    public static void onAttackSlowdown(Object player, boolean sprinting) {
        net.minecraft.entity.Entity entity = (net.minecraft.entity.Entity) player;
        entity.motionX /= 0.6D;
        entity.motionZ /= 0.6D;
        Hooks.onAttackSlowdown((net.minecraft.entity.Entity) attackTarget);
    }

    // --------------------------------------------------------------- movement

    /**
     * The yaw {@code Entity.moveFlying} walks in.
     *
     * <p>The Mixin build redirects the field read; this is the callback that
     * stands where it was. The client answers its own yaw while a rotation
     * module or Scaffold is steering.
     */
    public static float getMovementYaw(Entity entity) {
        return Hooks.getMovementYaw(entity);
    }

    /**
     * The value {@code Entity.moveEntity} is about to store for "this entity
     * moved".
     *
     * <p>The original value is deliberately ignored, exactly as the Mixin
     * handler ignores it: the client's answer comes from where the player is
     * standing, not from what vanilla worked out.
     */
    public static boolean stopOnEdge(boolean value, Entity entity) {
        return Hooks.stopOnEdge(entity);
    }
    // --------------------------------------------------------------- render

    /** Head of {@code Minecraft.runTick}, replacing the mouse-over update. */
    public static void getMouseOver() {
        Hooks.getMouseOver();
        // The client's own assets are put on the game's resource path here, on
        // the game thread, at the first tick -- before the first world render
        // asks for a cape texture. Installing them at the client's start is
        // three ticks too late for that, and a texture that failed to load once
        // stays failed.
        ClientAssets.install();
    }

    /**
     * The end of {@code Minecraft.runTick}, which is where Forge posts the
     * client tick. The shim fires it here rather than at the head of the tick:
     * a client that polls its binds at the end of a tick should see the tick
     * that just finished.
     */
    public static void afterClientTick() {
        ForgeBridge.clientTick();
    }

    /** Head of {@code EntityRenderer.updateCameraAndRender}. */
    public static void renderTickStart(float partialTicks) {
        showBlockAnimation();
        ForgeBridge.frameStart(partialTicks);
    }

    /** Its return: after the world and the GUI are on the screen. */
    public static void renderTickEnd() {
        ForgeBridge.renderTickEnd();
        restoreBlockAnimation();
    }

    // ---------------------------------------------- the forced block animation

    /**
     * Shows a block while a module is only pretending to block.
     *
     * <p>This is InjectMyau's cameraPre/cameraPost. The client's own block is
     * reported through the isBlocking()/getItemInUseCount() hooks, which answer
     * the code that asks those methods -- and only that code. Everything that
     * reads the field itself -- the hand renderer, the player model, OptiFine's
     * passes, a launcher's HUD -- saw a player who was not using anything, so
     * "fake" blocking drew nothing on the clients that never ask. Swapping the
     * item-use fields for the length of the frame makes every one of those
     * consumers agree, and the real values are put back before any game logic
     * runs again, so no slowdown, no server-side use, nothing to undo.
     */
    private static boolean blockAnimationShown;
    private static ItemStack savedItemInUse;
    private static int savedItemInUseCount;

    private static boolean pretendingToBlock() {
        Module killAura = ModuleManager.get("KillAura");
        if (killAura != null && killAura.isFakeBlocking()) {
            return true;
        }
        Module autoBlock = ModuleManager.get("AutoBlock");
        return autoBlock != null && autoBlock.isFakeBlocking();
    }

    private static void showBlockAnimation() {
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft.thePlayer == null || !pretendingToBlock()
                    || !PlayerUtils.holdingSword()) {
                return;
            }
            ItemStack held = minecraft.thePlayer.getHeldItem();
            if (held == null) {
                return;
            }
            savedItemInUse = minecraft.thePlayer.getItemInUse();
            // The real field, not the getter: the getter is the hook this is
            // working around.
            savedItemInUseCount = InjectAccess.itemInUseCount(minecraft.thePlayer);
            minecraft.thePlayer.setItemInUse(held, held.getMaxItemUseDuration());
            blockAnimationShown = true;
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }

    private static void restoreBlockAnimation() {
        try {
            if (!blockAnimationShown) {
                return;
            }
            blockAnimationShown = false;
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft.thePlayer != null) {
                minecraft.thePlayer.setItemInUse(savedItemInUse, savedItemInUseCount);
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }

    /**
     * Just before {@code EntityRenderer.renderHand}, inside the last world
     * pass: where Forge fires RenderWorldLastEvent from. The world is drawn, the
     * camera matrix is still the world's, and the partial ticks are the ones
     * this frame rendered with.
     *
     * <p>No arguments: the hook anchors on the call to {@code renderHand}
     * <i>inside</i> {@code renderWorldPass}, and the transformer allows an
     * argument read only at a method's head -- by the time the call is reached
     * the slot may hold anything. The partial ticks come from the head of the
     * pass instead, which is where they are still the argument they arrived as.
     */
    public static void afterWorldRender() {
        ForgeBridge.renderWorld(worldPassPartialTicks);
    }

    /**
     * The partial ticks the world is being rendered with.
     *
     * <p>Written at the head of every pass and read just before the hand is
     * drawn, which is the last pass -- so the value belongs to the pass whose
     * matrices are the ones still set.
     */
    private static volatile float worldPassPartialTicks;

    /** Head of a world pass: keeps the pass number and its partial ticks. */
    public static void captureWorldPass(int pass, float partialTicks) {
        worldPassPartialTicks = partialTicks;
        ForgeBridge.captureWorldPass(pass, partialTicks);
    }

    /** Return of a world pass: fires the overlays on the last one. */
    public static void fallbackWorldRender() {
        ForgeBridge.fallbackWorldRender();
    }

    /**
     * Replaces the hurt-camera strength constant of 14.0F.
     *
     * <p>The constant is the argument, not something loaded from a local: a
     * MODIFY_CONSTANT hook leaves the push in place and takes the callback's
     * return as its replacement, so the value has to be consumed here or the
     * stack does not balance.
     */
    public static float hurtCam(float vanilla) {
        return Hooks.getHurtCam();
    }

    /**
     * Replaces the camera clip raycast. Null means "do not clip".
     *
     * <p>Typed, and has to be: the call being replaced produced a
     * MovingObjectPosition and the code after it uses the value as one, so an
     * Object-shaped answer fails verification at the instruction that reads it.
     */
    public static MovingObjectPosition orientCameraClip(WorldClient world, Vec3 from, Vec3 to) {
        if (!Hooks.shouldCameraClip()) {
            return null;
        }
        return world.rayTraceBlocks(from, to);
    }

    /**
     * Partial ticks for the two pitch replacements below.
     *
     * The one piece of state these callbacks own: {@code doRender} receives the
     * value as an argument at its head, the two field reads happen further in
     * with no argument path between them, and the reads have to produce what
     * those two fields would have. The Mixin build keeps the same value in a
     * static field of its own class.
     */
    private static volatile float renderPartialTicks;

    public static void capturePartialTicks(EntityLivingBase entity, double x, double y, double z,
                                           float yaw, float partialTicks) {
        renderPartialTicks = partialTicks;
    }

    /** Replaces the read of {@code EntityLivingBase.prevRotationPitch}. */
    public static float rotationPitchHeadPrev(EntityLivingBase entity) {
        return Hooks.getRotationPitchHead(entity, renderPartialTicks);
    }

    /** Replaces the read of {@code EntityLivingBase.rotationPitch}. */
    public static float rotationPitchHead(EntityLivingBase entity) {
        return Hooks.getRotationPitchHead(entity, renderPartialTicks);
    }

    /**
     * Replaces the layer render call, so capes and nametags can be taken over.
     *
     * <p>The renderer is the enclosing method's own {@code this}, which the
     * call does not put on the stack. The hook loads it separately, on top of
     * the call's arguments, so it is the last parameter here while the call's
     * own arguments keep their order.
     */
    public static void doRenderLayer(Object layer, EntityLivingBase entity, float f, float f2,
                                     float f3, float f4, float f5, float f6, float f7,
                                     RendererLivingEntity renderer) {
        Hooks.doRenderLayer(renderer,
                (net.minecraft.client.renderer.entity.layers.LayerRenderer) layer, entity,
                f, f2, f3, f4, f5, f6, f7);
    }

    /**
     * Replaces the read of {@code ItemRenderer.itemToRender}.
     *
     * The stack at a field read holds the owning instance and nothing else, so
     * the callback takes the renderer and reads the field itself through
     * {@link InjectAccess}. It cannot take the item as an argument: reading the
     * field is the thing being replaced.
     */
    public static ItemStack getItemToRender(ItemRenderer renderer) {
        return Hooks.getItemToRender(InjectAccess.itemToRender(renderer));
    }

    /**
     * The writer of the equipped-progress local in
     * {@code ItemRenderer.renderItemInFirstPerson}, so the swing animation can
     * be restarted.
     */
    public static float setAnimationProgress(float value) {
        return Hooks.setAnimationProgress(value);
    }

    // -------------------------------------------------------------- network

    /** Head of {@code NetworkManager.sendPacket}. False cancels the packet. */
    public static boolean onSend(Packet<?> packet) {
        return Hooks.onSend(packet);
    }

    /**
     * Replaces {@code Packet.processPacket} inside
     * {@code NetworkManager.channelRead0}. The client dispatches the packet
     * itself so that it can drop it, which is why the vanilla dispatch is not
     * left in place afterwards.
     */
    @SuppressWarnings("rawtypes")
    public static void onReceive(Packet packet, INetHandler handler) {
        Hooks.onReceive(packet, handler);
    }

    /** Replaces {@code FontRenderer.renderStringAtPos}'s string argument. */
    public static String renderString(String value) {
        return Hooks.renderString(value);
    }

    /**
     * Return of {@code EntityLivingBase.onLivingUpdate}: where Forge fires
     * {@code LivingUpdateEvent}, and therefore where the shim has to.
     */
    public static void onLivingUpdate(EntityLivingBase entity) {
        ForgeBridge.livingUpdate(entity);
    }

    // --------------------------------------------------- the rendered player

    /**
     * The local player's pitch, for the length of one entity render.
     *
     * <p>InjectMyau swaps the rendered player's rotation fields around its
     * {@code RenderManager} call, and this is that swap. The reason is what the
     * client cannot see from inside its own class: its pitch redirect only
     * reaches the code in {@code RendererLivingEntity.doRender}. A launcher
     * client's model system reads {@code rotationPitch} off the entity
     * directly, so the game's own model pass and this client disagreed about
     * the head by a few degrees every frame, and the head that came out was
     * neither rotation -- "the head pitch looks a bit off".
     *
     * <p>Swapping the field makes every consumer of that entity agree for the
     * length of the render: the vanilla model, OptiFine's pass, and Badlion's
     * model capture all read the rotation this client is sending. The real
     * values are put back in {@link #renderEntityPost()}.
     */
    private static EntityLivingBase renderedPlayer;
    private static float savedPrevRotationPitch;
    private static float savedRotationPitch;

    public static void renderEntityPre(Entity entity, float partialTicks) {
        renderedPlayer = null;
        try {
            if (!(entity instanceof EntityPlayerSP)) {
                return;
            }
            // Nothing to swap when no rotation is being sent: the rendered
            // head is the player's own either way.
            if (!xyz.daikirai.util.RotationManager.rotating) {
                return;
            }
            EntityPlayerSP player = (EntityPlayerSP) entity;
            float head = Hooks.getRotationPitchHead(player, partialTicks);
            savedPrevRotationPitch = player.prevRotationPitch;
            savedRotationPitch = player.rotationPitch;
            player.prevRotationPitch = head;
            player.rotationPitch = head;
            renderedPlayer = player;
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }

    /** Puts back what {@link #renderEntityPre} swapped, whatever happened. */
    public static void renderEntityPost() {
        try {
            EntityLivingBase player = renderedPlayer;
            if (player == null) {
                return;
            }
            player.prevRotationPitch = savedPrevRotationPitch;
            player.rotationPitch = savedRotationPitch;
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        } finally {
            renderedPlayer = null;
        }
    }
}
