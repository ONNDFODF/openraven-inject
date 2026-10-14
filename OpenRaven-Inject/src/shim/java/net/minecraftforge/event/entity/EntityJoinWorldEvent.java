package net.minecraftforge.event.entity;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.Event;

/** An entity arriving in a world, the local player among them. */
public class EntityJoinWorldEvent extends Event {

    public final Entity entity;
    public final World world;

    public EntityJoinWorldEvent(Entity entity, World world) {
        this.entity = entity;
        this.world = world;
    }
}