package xyz.daikirai.inject;

import xyz.daikirai.inject.HookRegistry.Flow;
import xyz.daikirai.inject.HookRegistry.Position;

import static xyz.daikirai.inject.HookRegistry.hook;

/**
 * The injection table, ported one entry at a time from the Mixin build's
 * annotations in {@code xyz.daikirai.mixin}.
 *
 * Read a line as: rewrite {@code owner.method}, at {@code position}, calling
 * {@code callback}. The Mixin annotation it came from is in the comment, so the
 * two builds can be diffed by eye when a hook stops landing.
 *
 * <p>Two translations are not mechanical:
 *
 * <ul>
 *   <li>{@code @Inject(cancellable = true)} becomes {@link Flow#CANCEL} when the
 *       callback returns false to let the method run, or {@link Flow#OVERRIDE}
 *       when it supplies a value through {@code setReturnValue}. Only a head or
 *       return hook can cancel -- anywhere else the stack is non-empty and
 *       there is no frame to return from.</li>
 *   <li>{@code @Redirect} on a field write becomes {@link Position#REPLACE_FIELD}
 *       with a void callback, which drops the write and leaves the value to be
 *       popped. A redirect whose handler returns a value is registered with
 *       {@link Flow#REPLACE} instead: the stack already holds the receiver and
 *       the value, so the callback's own descriptor takes them and its return
 *       value becomes the field read.</li>
 * </ul>
 *
 * <p>Hooks marked {@link HookRegistry.Builder#invisible()} are the two that
 * cannot be confirmed without the game's bytecode in hand: both pick an
 * instruction out of {@code ItemRenderer.renderItemInFirstPerson} by ordinal,
 * and an ordinal that shifted is a silent miss. They still install and still
 * log; they are simply not counted in the hook report's fraction, so that a
 * green report stays meaningful. Check {@code %TEMP%\openraven-native.log} for
 * "wanted ... saw ..." lines if the item render swap does not appear in game.
 */
public final class InjectHooks {

    private static final String CALLBACKS = RuntimeCallbacks.class.getName();

    /** Where the item swaps and the swing animation reset show up. */
    private static final String ITEM_RENDERER = "net.minecraft.client.renderer.ItemRenderer";

    /** The entity render entry point, where the rendered player's pitch is swapped. */
    private static final String RENDER_MANAGER = "net.minecraft.client.renderer.entity.RenderManager";

    private InjectHooks() {
    }

    /** Set once the table has been filled: the DLL ends up calling register twice. */
    private static boolean registered;

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // ---------------------------------------------------------- Minecraft

        // @Mixin(Minecraft.class) @Redirect(runTick, INVOKE EntityRenderer.getMouseOver)
        // The client runs the mouse-over itself so reach and hitbox modules can
        // stand in for it.
        HookRegistry.add(hook("net.minecraft.client.Minecraft", "runTick")
                .desc("()V")
                .at(Position.BEFORE_INVOKE)
                .of("net.minecraft.client.renderer.EntityRenderer", "getMouseOver", "(F)V")
                .call(CALLBACKS, "getMouseOver", "()V", Flow.VOID)
                .build());

        // ------------------------------------------------ EntityPlayerSP

        // @Inject(onUpdate, at INVOKE AbstractClientPlayer.onUpdate)
        //
        // Forge only, and marked so. The method it lands in does not exist in
        // vanilla 1.8.9: Forge's patch gives EntityPlayerSP an onUpdate that
        // calls super and then fires LivingUpdateEvent, and the Mixin targets
        // the super call inside it. Badlion runs the vanilla jar, where
        // EntityPlayerSP has no onUpdate at all -- offline, over the game's own
        // class file: "no method net.minecraft.client.entity.EntityPlayerSP
        // .onUpdate()V". That is the intended answer on that client, not a
        // broken hook, so it is optional rather than a required miss the report
        // has to explain away.
        HookRegistry.add(hook("net.minecraft.client.entity.EntityPlayerSP", "onUpdate")
                .desc("()V")
                .at(Position.BEFORE_INVOKE)
                .of("net.minecraft.client.entity.AbstractClientPlayer", "onUpdate", "()V")
                .call(CALLBACKS, "onUpdate", "()V", Flow.VOID)
                .optional()
                .build());

        // @Inject(onUpdateWalkingPlayer, HEAD, cancellable) -- the client sends
        // the position packet, so the vanilla method never runs. The method
        // returns void, so a boolean cancel would do; OVERRIDE is used because
        // it is the same shape every other skip in this table has, and a
        // non-null array reads as "do not run the vanilla body" at a glance.
        HookRegistry.add(hook("net.minecraft.client.entity.EntityPlayerSP", "onUpdateWalkingPlayer")
                .desc("()V")
                .at(Position.HEAD)
                .call(CALLBACKS, "onUpdateWalkingPlayer", "()[Ljava/lang/Object;", Flow.OVERRIDE)
                .build());

        // @Redirect(onLivingUpdate, FIELD MovementInput.moveStrafe, PUTFIELD, ordinal 0)
        // The write is dropped, which is what the redirect with a void handler
        // means. Hooks.onPlayerSlowdown() is what puts the slowdown back, and
        // it is called once -- from the forward redirect below, exactly as in
        // the Mixin build -- because it reapplies *both* fields. Calling it
        // here as well would square the 0.2 and leave a player who can barely
        // move while eating.
        HookRegistry.add(hook("net.minecraft.client.entity.EntityPlayerSP", "onLivingUpdate")
                .desc("()V")
                .at(Position.REPLACE_FIELD)
                .of("net.minecraft.util.MovementInput", "moveStrafe", "F")
                .ordinal(0)
                .call(CALLBACKS, "onPlayerSlowdownStrafe",
                        "(Lnet/minecraft/util/MovementInput;F)V", Flow.VOID)
                .build());

        // @Redirect(onLivingUpdate, FIELD MovementInput.moveForward, PUTFIELD, ordinal 0)
        HookRegistry.add(hook("net.minecraft.client.entity.EntityPlayerSP", "onLivingUpdate")
                .desc("()V")
                .at(Position.REPLACE_FIELD)
                .of("net.minecraft.util.MovementInput", "moveForward", "F")
                .ordinal(0)
                .call(CALLBACKS, "onPlayerSlowdownForward",
                        "(Lnet/minecraft/util/MovementInput;F)V", Flow.VOID)
                .build());

        // @Inject(updateEntityActionState, at RETURN)
        HookRegistry.add(hook("net.minecraft.client.entity.EntityPlayerSP", "updateEntityActionState")
                .desc("()V")
                .at(Position.RETURN)
                .call(CALLBACKS, "onUpdateEntityActionState", "()V", Flow.VOID)
                .build());

        // ------------------------------------------------------ ItemRenderer

        // @Inject(renderItemInFirstPerson, at FIELD itemToRender GETFIELD, ordinal 0)
        // The read is replaced, so the callback returns what the field would
        // have held. A mixin writes the swapped stack back into the field here;
        // a read cannot be assigned to, so the swap is applied on every read
        // instead, which is what the write was for.
        HookRegistry.add(hook(ITEM_RENDERER, "renderItemInFirstPerson")
                .desc("(F)V")
                .at(Position.REPLACE_FIELD_WITH)
                .of(ITEM_RENDERER, "itemToRender", "Lnet/minecraft/item/ItemStack;")
                .ordinal(0)
                .call(CALLBACKS, "getItemToRender",
                        "(Lnet/minecraft/client/renderer/ItemRenderer;)Lnet/minecraft/item/ItemStack;",
                        Flow.VOID)
                .invisible()
                .build());

        // @ModifyVariable(renderItemInFirstPerson, at FIELD itemToRender, index 2)
        // The local written next to that field access, so that a swap restarts
        // the swing animation.
        HookRegistry.add(hook(ITEM_RENDERER, "renderItemInFirstPerson")
                .desc("(F)V")
                .at(Position.MODIFY_STORE)
                .of(ITEM_RENDERER, "itemToRender", "F")
                .ordinal(0)
                .call(CALLBACKS, "setAnimationProgress", "(F)F", Flow.REPLACE)
                .invisible()
                .build());

        // ---------------------------------------------------- EntityLivingBase

        // Where Forge fires LivingUpdateEvent: at the end of the entity's own
        // living update. The shim defines the event but nothing fired it, so
        // every module that listens for it -- Velocity, TPAura -- was dead on
        // the two clients that have no Forge. The callback stands down when
        // the real Forge is present, because then the game fires it itself.
        HookRegistry.add(hook("net.minecraft.entity.EntityLivingBase", "onLivingUpdate")
                .desc("()V")
                .at(Position.RETURN)
                .call(CALLBACKS, "onLivingUpdate",
                        "(Lnet/minecraft/entity/EntityLivingBase;)V", Flow.VOID)
                .args("this")
                .build());

        // @Inject(jump, HEAD, cancellable) -- returns a value, so this is an
        // override rather than a cancel.
        HookRegistry.add(hook("net.minecraft.entity.EntityLivingBase", "jump")
                .desc("()V")
                .at(Position.HEAD)
                .call(CALLBACKS, "onJump",
                        "(Lnet/minecraft/entity/EntityLivingBase;)[Ljava/lang/Object;",
                        Flow.OVERRIDE)
                .args("this")
                .build());

        // -------------------------------------------------------- EntityPlayer

        // @Inject(isBlocking, HEAD, cancellable) -- setReturnValue, so the hook
        // supplies the answer rather than cancelling with a default.
        HookRegistry.add(hook("net.minecraft.entity.player.EntityPlayer", "isBlocking")
                .desc("()Z")
                .at(Position.HEAD)
                .call(CALLBACKS, "isBlocking",
                        "(Lnet/minecraft/entity/player/EntityPlayer;)[Ljava/lang/Object;",
                        Flow.OVERRIDE)
                .args("this")
                .build());

        // @Inject(getItemInUseCount, HEAD, cancellable) -- setReturnValue, and
        // the vanilla value goes in as an argument.
        HookRegistry.add(hook("net.minecraft.entity.player.EntityPlayer", "getItemInUseCount")
                .desc("()I")
                .at(Position.HEAD)
                .call(CALLBACKS, "getItemInUseCount",
                        "(Lnet/minecraft/entity/player/EntityPlayer;)[Ljava/lang/Object;",
                        Flow.OVERRIDE)
                .args("this")
                .build());

        // @Inject(attackTargetEntityWithCurrentItem, HEAD) -- keeps the argument
        // for the three hooks below.
        HookRegistry.add(hook("net.minecraft.entity.player.EntityPlayer", "attackTargetEntityWithCurrentItem")
                .desc("(Lnet/minecraft/entity/Entity;)V")
                .at(Position.HEAD)
                .call(CALLBACKS, "captureAttackTarget", "(Ljava/lang/Object;)V",
                        Flow.VOID)
                .args("0")
                .build());

        // @Redirect(attackTargetEntityWithCurrentItem, FIELD motionX/motionZ,
        // PUTFIELD, ordinal 0) -- and deliberately not hooked here.
        //
        // Replacing those two writes is what the Mixin build did, and the
        // rewrite cannot be done from outside: the store is reached through
        // load_0; dup; getfield; ldc; dmul; putfield, and ASM's frame
        // computation refuses the method once that sequence is edited at all
        // (a negative array size from inside Frame.merge, with or without the
        // store left in place). The velocity is put back at the setSprinting
        // call below instead, which is the next thing the method does and is
        // already being replaced.

        // @Redirect(attackTargetEntityWithCurrentItem, INVOKE EntityPlayer.setSprinting)
        // This is the call that applies the vanilla attack slowdown, so the
        // client's own replaces it rather than the field writes above.
        HookRegistry.add(hook("net.minecraft.entity.player.EntityPlayer", "attackTargetEntityWithCurrentItem")
                .desc("(Lnet/minecraft/entity/Entity;)V")
                .at(Position.REPLACE_INVOKE)
                .of("net.minecraft.entity.player.EntityPlayer", "setSprinting", "(Z)V")
                .call(CALLBACKS, "onAttackSlowdown",
                        "(Ljava/lang/Object;Z)V", Flow.VOID)
                .build());

        // @Inject(runTick, RETURN) -- where Forge posts the client tick. The
        // shim fires ClientTickEvent from here; a Forge client already has it
        // and the bridge stands down.
        HookRegistry.add(hook("net.minecraft.client.Minecraft", "runTick")
                .desc("()V")
                .at(Position.RETURN)
                .call(CALLBACKS, "afterClientTick", "()V", Flow.VOID)
                .build());

        // @Inject(updateCameraAndRender, HEAD/RETURN) -- the frame, and the
        // only pair of points that gets the HUD in the right place: the client
        // draws on the render tick's END, which is after the GUI.
        HookRegistry.add(hook("net.minecraft.client.renderer.EntityRenderer", "updateCameraAndRender")
                .desc("(FJ)V")
                .at(Position.HEAD)
                .call(CALLBACKS, "renderTickStart", "(F)V", Flow.VOID)
                .args("0")
                .build());
        HookRegistry.add(hook("net.minecraft.client.renderer.EntityRenderer", "updateCameraAndRender")
                .desc("(FJ)V")
                .at(Position.RETURN)
                .call(CALLBACKS, "renderTickEnd", "()V", Flow.VOID)
                .build());

        // @Inject(renderHand, HEAD) -- where Forge fires RenderWorldLastEvent
        // from, and the placement is the whole point. renderWorldPass runs three
        // times a frame and its return is after the camera matrix has been
        // unpacked again; an event fired there draws the client's overlays in
        // the wrong space, which is an ESP that is in the right shape and the
        // wrong place.
        //
        // The anchor is the read of the renderHand *field* at the end of the
        // last pass, which is InjectMyau's anchor for the same event
        // (`BEFORE_FIELD` on EntityRenderer.renderHand, its render3DPost). That
        // read is vanilla's own gate on drawing the hand, and it sits a few
        // instructions before the call: the frustum and the world matrices are
        // still set, and the partial ticks are the pass's own, kept by the
        // head hook below.
        //
        // What this used to be is why the entry is worth a comment. It asked for
        // the head of EntityRenderer.renderHand itself, in a BEFORE_INVOKE with
        // an argument read -- a position the transformer refuses ("only a head
        // hook may read arguments"), so on every client it was reported as "no
        // insertion point" and the overlays were carried by the fallback below.
        // A hook on the *caller's* field cannot be broken that way: the field is
        // read whether or not a launcher rewrites the method that draws the hand
        // (Lunar rewrites renderHand; its pass is untouched).
        HookRegistry.add(hook("net.minecraft.client.renderer.EntityRenderer", "renderWorldPass")
                .desc("(IFJ)V")
                .at(Position.BEFORE_FIELD)
                .of("net.minecraft.client.renderer.EntityRenderer", "renderHand", "Z")
                .call(CALLBACKS, "afterWorldRender", "()V", Flow.VOID)
                .optional()
                .build());

        // The fallback for a client whose render pass has been rewritten around
        // that call -- Lunar's own mixins do exactly that. The catch-up runs
        // from the head of the world pass, which survives, and fires at the
        // return of the last pass; ForgeBridge posts one event a frame whoever
        // gets there first.
        HookRegistry.add(hook("net.minecraft.client.renderer.EntityRenderer", "renderWorldPass")
                .desc("(IFJ)V")
                .at(Position.HEAD)
                .call(CALLBACKS, "captureWorldPass", "(IF)V", Flow.VOID)
                .args("0,1")
                .build());
        HookRegistry.add(hook("net.minecraft.client.renderer.EntityRenderer", "renderWorldPass")
                .desc("(IFJ)V")
                .at(Position.RETURN)
                .call(CALLBACKS, "fallbackWorldRender", "()V", Flow.VOID)
                .build());
        // ------------------------------------------------------ Entity (movement)

        // @Redirect(moveFlying, FIELD Entity.rotationYaw, GETFIELD)
        // The direction the client walks in, which is not always the one the
        // entity is facing: Scaffold and the rotation modules keep a yaw of
        // their own, and this is the last place the game asks for it. Both reads
        // in the method are replaced, which is what the Mixin redirect with no
        // ordinal does.
        HookRegistry.add(hook("net.minecraft.entity.Entity", "moveFlying")
                .desc("(FFF)V")
                .at(Position.REPLACE_FIELD_WITH)
                .of("net.minecraft.entity.Entity", "rotationYaw", "F")
                .call(CALLBACKS, "getMovementYaw", "(Lnet/minecraft/entity/Entity;)F", Flow.VOID)
                .build());

        // @ModifyVariable(moveEntity, at = STORE, index = 19)
        // The boolean vanilla stores as "this entity moved"; the client answers
        // it instead, which is how SafeWalk, Tower and Scaffold stop a player at
        // the edge of a block. Slot 19 is the first int store in the method --
        // the next four are later locals -- so the ordinal finds it without
        // naming the slot.
        HookRegistry.add(hook("net.minecraft.entity.Entity", "moveEntity")
                .desc("(DDD)V")
                .at(Position.MODIFY_STORE)
                .of("net.minecraft.entity.Entity", "flag", "Z")
                .ordinal(0)
                .call(CALLBACKS, "stopOnEdge", "(ZLnet/minecraft/entity/Entity;)Z", Flow.REPLACE)
                .args("this")
                .build());
        // ----------------------------------------------- RendererLivingEntity

        // @Inject(doRender, HEAD) -- keeps partial ticks for the two below.
        HookRegistry.add(hook("net.minecraft.client.renderer.entity.RendererLivingEntity", "doRender")
                .desc("(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V")
                .at(Position.HEAD)
                .call(CALLBACKS, "capturePartialTicks",
                        "(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", Flow.VOID)
                .args("0,1,2,3,4,5")
                .build());

        // @Redirect(doRender, FIELD prevRotationPitch GETFIELD)
        HookRegistry.add(hook("net.minecraft.client.renderer.entity.RendererLivingEntity", "doRender")
                .desc("(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V")
                .at(Position.REPLACE_FIELD_WITH)
                .of("net.minecraft.entity.EntityLivingBase", "prevRotationPitch", "F")
                .call(CALLBACKS, "rotationPitchHeadPrev",
                        "(Lnet/minecraft/entity/EntityLivingBase;)F", Flow.VOID)
                .build());

        // @Redirect(doRender, FIELD rotationPitch GETFIELD)
        HookRegistry.add(hook("net.minecraft.client.renderer.entity.RendererLivingEntity", "doRender")
                .desc("(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V")
                .at(Position.REPLACE_FIELD_WITH)
                .of("net.minecraft.entity.EntityLivingBase", "rotationPitch", "F")
                .call(CALLBACKS, "rotationPitchHead",
                        "(Lnet/minecraft/entity/EntityLivingBase;)F", Flow.VOID)
                .build());

        // @Redirect(renderLayers, INVOKE LayerRenderer.doRenderLayer)
        HookRegistry.add(hook("net.minecraft.client.renderer.entity.RendererLivingEntity", "renderLayers")
                .desc("(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V")
                .at(Position.REPLACE_INVOKE)
                .of("net.minecraft.client.renderer.entity.layers.LayerRenderer", "doRenderLayer",
                        "(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V")
                .call(CALLBACKS, "doRenderLayer",
                        "(Ljava/lang/Object;Lnet/minecraft/entity/EntityLivingBase;"
                                + "FFFFFFFLnet/minecraft/client/renderer/entity/"
                                + "RendererLivingEntity;)V",
                        Flow.VOID)
                .args("this")
                .build());

        // ----------------------------------------------- RenderManager (the swap)

        // InjectMyau swaps the rendered player's own rotation fields around its
        // RenderManager call, so every consumer inside the render -- the vanilla
        // model, OptiFine's model pass, Badlion's model capture -- sees the
        // rotation this client is sending. The pitch redirect below only reaches
        // the code inside RendererLivingEntity.doRender, and a launcher client's
        // model system reads the fields itself, which is how a head comes out at
        // an angle neither the client nor the game asked for.
        HookRegistry.add(hook(RENDER_MANAGER, "renderEntityStatic")
                .desc("(Lnet/minecraft/entity/Entity;FZ)Z")
                .at(Position.HEAD)
                .call(CALLBACKS, "renderEntityPre",
                        "(Lnet/minecraft/entity/Entity;F)V", Flow.VOID)
                .args("0,1")
                .build());
        HookRegistry.add(hook(RENDER_MANAGER, "renderEntityStatic")
                .desc("(Lnet/minecraft/entity/Entity;FZ)Z")
                .at(Position.RETURN)
                .call(CALLBACKS, "renderEntityPost", "()V", Flow.VOID)
                .build());

        // ------------------------------------------------------ EntityRenderer

        // @ModifyConstant(hurtCameraEffect, constant = 14.0F)
        HookRegistry.add(hook("net.minecraft.client.renderer.EntityRenderer", "hurtCameraEffect")
                .desc("(F)V")
                .at(Position.MODIFY_CONSTANT)
                .constant("14.0")
                .ordinal(0)
                .call(CALLBACKS, "hurtCam", "(F)F", Flow.REPLACE)
                .build());

        // @Redirect(orientCamera, INVOKE WorldClient.rayTraceBlocks)
        //
        // Vanilla only, and the live run is what says so. This lands on every
        // vanilla-shaped client -- Forge, Lunar, and the vanilla jar itself,
        // where `tools/bytecode` places it in front of the raycast on all three
        // -- but not on Badlion: Badlion rewrites orientCamera wholesale and
        // the call is simply not in it any more. Its version asks its own
        // Perspective mod for the yaw and pitch and then raycasts through its
        // own helper; the offline log of that method lists 24 calls and
        // `rayTraceBlocks` is not among them. Nothing this table can say will
        // make the call reappear, so it stays a reported miss there rather than
        // a silent one -- the module is the client's to provide on that client.
        HookRegistry.add(hook("net.minecraft.client.renderer.EntityRenderer", "orientCamera")
                .desc("(F)V")
                .at(Position.REPLACE_INVOKE_WITH)
                .of("net.minecraft.client.multiplayer.WorldClient", "rayTraceBlocks",
                        "(Lnet/minecraft/util/Vec3;Lnet/minecraft/util/Vec3;)"
                                + "Lnet/minecraft/util/MovingObjectPosition;")
                .call(CALLBACKS, "orientCameraClip",
                        "(Lnet/minecraft/client/multiplayer/WorldClient;Lnet/minecraft/util/Vec3;"
                                + "Lnet/minecraft/util/Vec3;)Lnet/minecraft/util/"
                                + "MovingObjectPosition;",
                        Flow.VOID)
                .build());

        // ------------------------------------------------------------ network

        // @Inject(sendPacket, HEAD, cancellable)
        HookRegistry.add(hook("net.minecraft.network.NetworkManager", "sendPacket")
                .desc("(Lnet/minecraft/network/Packet;)V")
                .at(Position.HEAD)
                .call(CALLBACKS, "onSend", "(Lnet/minecraft/network/Packet;)Z", Flow.CANCEL)
                .args("0")
                .build());

        // @Redirect(channelRead0, INVOKE Packet.processPacket)
        //
        // The inbound hook, and the only one. It stands where the vanilla client
        // dispatches: `channelRead0` opens with
        // `if (channel.isOpen()) packet.processPacket(listener)`, and the
        // ThreadQuickExitException that ends it is caught right there, which is
        // how a cancelled packet is dropped.
        //
        // <p>InjectMyau anchors the same event at this method's HEAD with a
        // cancellable callback. Both are correct where the client uses the
        // vanilla inbound path, which is Forge and Lunar -- measured on Lunar
        // with this hook in place: eleven distinct `play.server` packet classes
        // including `S12PacketEntityVelocity`. On Badlion neither fires after
        // the login handshake, because that client stops routing received
        // packets through this method at all; see the README.
        HookRegistry.add(hook("net.minecraft.network.NetworkManager", "channelRead0")
                .desc("(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;)V")
                .at(Position.REPLACE_INVOKE)
                .of("net.minecraft.network.Packet", "processPacket",
                        "(Lnet/minecraft/network/INetHandler;)V")
                .call(CALLBACKS, "onReceive",
                        "(Lnet/minecraft/network/Packet;Lnet/minecraft/network/INetHandler;)V",
                        Flow.VOID)
                .build());

        // ------------------------------------------------------- FontRenderer

        // @ModifyVariable(renderStringAtPos, HEAD, argsOnly, ordinal 0)
        HookRegistry.add(hook("net.minecraft.client.gui.FontRenderer", "renderStringAtPos")
                .desc("(Ljava/lang/String;Z)V")
                .at(Position.MODIFY_ARGUMENT)
                .argument(0)
                .call(CALLBACKS, "renderString",
                        "(Ljava/lang/String;)Ljava/lang/String;", Flow.REPLACE)
                .build());

        // -------------------------------------------------- MovementInputFromOptions

        // @Inject(updatePlayerMoveState, at RETURN)
        HookRegistry.add(hook("net.minecraft.util.MovementInputFromOptions", "updatePlayerMoveState")
                .desc("()V")
                .at(Position.RETURN)
                .call(CALLBACKS, "postPlayerInput", "()V", Flow.VOID)
                .build());
    }
}
