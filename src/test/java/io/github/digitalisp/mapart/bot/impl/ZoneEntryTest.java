package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The per-tick no-go check of every task ({@link ZoneEntry}): walking into a zone fails the task; sitting in a vehicle
 * never does (live 2026-11-14: an edge box over the preload lap's start rail failed the boarding the moment the bot sat
 * in the cart).
 */
class ZoneEntryTest {
    private static MapArtConfig.RegionCfg box(String name, Pos a, Pos b) {
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.name = name;
        r.min = a;
        r.max = b;
        return r;
    }

    /** The cart's rail cell and the cell above it ("edge"), and a second zone next to it ("machinery"). */
    private static final NoGoZones ZONES = NoGoZones.of(List.of(
        box("edge", new Pos(10, 70, 5), new Pos(10, 71, 6)),
        box("machinery", new Pos(11, 60, 0), new Pos(20, 80, 20))));
    static final Pos STAND = new Pos(9, 70, 5);
    static final Pos RAIL = new Pos(10, 70, 5);

    private static NoGoZones.Zone tick(ZoneEntry e, boolean first, boolean seated, Pos feet) {
        return e.entered(ZONES, first, seated, feet, feet.x() + 0.5, feet.y(), feet.z() + 0.5);
    }

    @Test
    void walkingIntoAZoneIsAnEntry() {
        ZoneEntry e = new ZoneEntry();
        assertNull(tick(e, true, false, STAND));
        NoGoZones.Zone z = tick(e, false, false, RAIL);
        assertNotNull(z);
        assertEquals("edge", z.label().replaceAll(".*\"(.*)\".*", "$1"));
    }

    @Test
    void sittingInACartOnARailInsideAZoneIsNoEntry() {
        ZoneEntry e = new ZoneEntry();
        assertNull(tick(e, true, false, STAND), "at the stand, outside every zone");
        // boarded: the server puts the bot on the cart, on the rail cell inside the edge box
        for (int i = 0; i < 40; i++) assertNull(tick(e, false, true, RAIL), "seated, tick " + i);
        // the cart carries it through other zones too (the ride)
        assertNull(tick(e, false, true, new Pos(15, 70, 10)));
        assertNull(tick(e, false, true, RAIL));
    }

    @Test
    void theZoneTheBotGetsOutInMayBeLeftAndOnlyThatOne() {
        ZoneEntry e = new ZoneEntry();
        assertNull(tick(e, true, true, RAIL), "the task starts seated");
        assertNull(tick(e, false, false, RAIL), "out of the cart on its rail: no entry");
        assertNull(tick(e, false, false, RAIL), "still there");
        assertNull(tick(e, false, false, STAND), "walked out");
        assertNotNull(tick(e, false, false, RAIL), "walking back in is an entry again");

        ZoneEntry other = new ZoneEntry();
        assertNull(tick(other, true, true, RAIL));
        assertNull(tick(other, false, false, RAIL));
        assertNotNull(tick(other, false, false, new Pos(11, 70, 5)), "from the edge box into the machinery: an entry");
    }

    @Test
    void theFirstTicksZoneMayBeLeftAsBefore() {
        ZoneEntry e = new ZoneEntry();
        assertNull(tick(e, true, false, RAIL), "the task starts inside the zone");
        assertNull(tick(e, false, false, RAIL));
        assertNull(tick(e, false, false, STAND));
        assertNotNull(tick(e, false, false, RAIL));
        assertNull(new ZoneEntry().entered(NoGoZones.NONE, true, false, RAIL, 10.5, 70, 5.5), "no zones");
    }
}
