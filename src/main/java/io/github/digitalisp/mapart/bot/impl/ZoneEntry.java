package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

/**
 * Last line of the no-go rule for one task ({@code ZenithTask}), for every way the bot moves itself (Baritone, the lane
 * walk's own keys, a knock-back): the feet centre entering a zone ({@link NoGoZones#FEET_MARGIN} deep: a corner or an
 * edge clipped for a tick while the bot turns is no entry, {@link NoGoZones#deepAt}) fails the task. Leaving the zone
 * the bot stood in on the task's first tick is allowed, until the feet cell is out of it.
 *
 * <p>Seated in a vehicle the check is off: the vehicle carries the bot, the bot walks nowhere. A cart's rail may lie in
 * a zone or a lethal-edge box by design (live 2026-11-14: {@code ride.preload.edges} covered the preload lap's start
 * rail cell, and boarding ended "entered no-go zone ... stopped" the moment the bot sat in the cart). The zone the bot
 * is in when it gets out counts like the first tick's: it may walk out of it.
 */
final class ZoneEntry {
    /** The zone the bot may leave: where it stood on the first tick, or where a vehicle put it; null once out. */
    private NoGoZones.@Nullable Zone startZone;
    private boolean wasSeated;

    /**
     * One tick. {@code first}: the task's first tick; {@code seated}: the bot sits in a vehicle; {@code feet} the feet
     * cell and ({@code x}, {@code y}, {@code z}) the feet point. The zone entered, or null while all is well.
     */
    NoGoZones.@Nullable Zone entered(NoGoZones zones, boolean first, boolean seated, Pos feet, double x, double y,
                                     double z) {
        if (zones.isEmpty()) return null;
        NoGoZones.Zone cell = zones.at(feet);
        if (seated) {
            wasSeated = true;
            startZone = cell;
            return null;
        }
        NoGoZones.Zone deep = zones.deepAt(x, y, z, NoGoZones.FEET_MARGIN);
        if (wasSeated) {
            // out of the vehicle: where it let the bot out is where this task starts from
            wasSeated = false;
            startZone = deep != null ? deep : cell;
        } else {
            if (first) startZone = cell;
            if (cell == null) startZone = null;
        }
        if (deep == null || deep.sameZone(startZone)) return null;
        return deep;
    }
}
