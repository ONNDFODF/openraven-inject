package net.minecraftforge.event.entity.living;

import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.event.entity.EntityEvent;

/** Base class for the living-entity events. */
public class LivingEvent extends EntityEvent {

    public final EntityLivingBase entity;

    public LivingEvent(EntityLivingBase entity) {
        super(entity);
        this.entity = entity;
    }

    public static class LivingUpdateEvent extends LivingEvent {
        public LivingUpdateEvent(EntityLivingBase entity) {
            super(entity);
        }
    }

    public static class LivingJumpEvent extends LivingEvent {
        public LivingJumpEvent(EntityLivingBase entity) {
            super(entity);
        }
    }
}