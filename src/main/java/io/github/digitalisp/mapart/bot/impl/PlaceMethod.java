package io.github.digitalisp.mapart.bot.impl;

/**
 * How one lane block is placed ({@link TopFaceRules#placeMethod}): {@link #AIRPLACE} (offhand swap, use-item-on the
 * target cell itself, no rotation) or {@link #LOOK} (rotation to the top face of the block below, then click it).
 */
public enum PlaceMethod {
    AIRPLACE, LOOK
}
