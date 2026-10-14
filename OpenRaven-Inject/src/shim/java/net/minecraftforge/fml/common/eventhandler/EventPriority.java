package net.minecraftforge.fml.common.eventhandler;

/**
 * The order listeners run in. Forge's enum, with the same names and the same
 * order, because the client writes {@code EventPriority.LOWEST} in an
 * annotation and the value is read back by reflection.
 */
public enum EventPriority {
    HIGHEST,
    HIGH,
    NORMAL,
    LOW,
    LOWEST
}