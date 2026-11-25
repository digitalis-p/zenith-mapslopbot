package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.InventoryView.Armor;
import io.github.digitalisp.mapart.config.MapArtConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The durability guard's armour arithmetic. */
class GearGuardTest {
    private static final String BOOTS = "minecraft:netherite_boots";   // 481 uses
    private static final String HELMET = "minecraft:diamond_helmet";   // 363 uses

    /** The defaults: boots below 50 %, the rest below 50 %, park below 25 %, off at 6 %. */
    private static final GearGuard G = GearGuard.of(new MapArtConfig.BuildCfg());

    private static Armor boots(int remaining) {
        return new Armor(Armor.FEET, BOOTS, remaining, 481, true);
    }

    private static Armor helmet(int remaining, boolean mending) {
        return new Armor(Armor.HEAD, HELMET, remaining, 363, mending);
    }

    @Test
    void defaultsMatchTheConfig() {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        assertTrue(b.gearGuard && b.heldItemGuard);
        assertEquals(50, G.bootsBelowPercent);
        assertEquals(50, G.armorBelowPercent);
        assertEquals(25, G.parkPercent);
        assertEquals(6, G.offPercent);
        assertEquals(10, b.mendWatchdogMinutes);
    }

    @Test
    void theHardFloorIsSixPercentRoundedUpAndAtLeastOneUse() {
        assertEquals(29, G.offUses(boots(481)));       // ceil(481 * 0.06) = 29
        assertTrue(G.atOff(boots(29)));
        assertFalse(G.atOff(boots(30)));
        GearGuard none = new GearGuard(50, 50, 25, 0);
        assertEquals(1, none.offUses(boots(481)), "never below one use");
        assertTrue(none.atOff(boots(1)));
        assertFalse(none.atOff(boots(2)));
    }

    @Test
    void thresholdsAreSanitised() {
        GearGuard g = new GearGuard(200, -5, 99, 80);
        assertEquals(95, g.bootsBelowPercent);
        assertEquals(0, g.armorBelowPercent);
        assertEquals(95, g.parkPercent);
        assertEquals(50, g.offPercent);
    }

    @Test
    void dueBelowTheThresholdUrgentBelowThePark() {
        assertFalse(G.wantsMend(boots(241)), "50.1 %: not due");
        assertTrue(G.wantsMend(boots(240)), "49.9 %: due");
        assertFalse(G.urgent(boots(240)), "due, not urgent");
        assertTrue(G.urgent(boots(120)), "below 25 %: urgent");
        assertTrue(G.urgent(boots(29)), "at the floor: urgent");
        assertFalse(G.wantsMend(helmet(10, false)), "no Mending: never due");
        assertFalse(G.urgent(helmet(10, false)));
    }

    @Test
    void bootsAndTheOtherPiecesHaveTheirOwnThreshold() {
        GearGuard g = new GearGuard(80, 30, 25, 6);
        assertTrue(g.wantsMend(boots(300)), "62 % boots: below 80");
        assertFalse(g.wantsMend(helmet(200, true)), "55 % helmet: above 30");
        assertTrue(g.wantsMend(helmet(100, true)), "27 % helmet: below 30");
        assertEquals(80, g.belowPercent(boots(1)));
        assertEquals(30, g.belowPercent(helmet(1, true)));
        // a threshold of 0: never mended, but still due at the floor
        GearGuard off = new GearGuard(0, 0, 0, 6);
        assertFalse(off.wantsMend(boots(100)));
        assertTrue(off.wantsMend(boots(20)));
    }

    @Test
    void aWornOutMendingPieceIsWornOnlyWithBottlesAPlainOneOnlyAboveTheFloor() {
        assertTrue(G.wearable(boots(400), 0), "above the threshold: fine without bottles");
        assertFalse(G.wearable(boots(200), 0), "below the threshold, no bottles: it would wear on unmended");
        assertTrue(G.wearable(boots(200), 1));
        assertFalse(G.wearable(boots(10), 0), "at the floor, no bottles");
        assertTrue(G.wearable(boots(10), 5), "at the floor with bottles: mended at once");
        assertTrue(G.wearable(helmet(30, false), 0), "no Mending, above the floor");
        assertFalse(G.wearable(helmet(22, false), 99), "no Mending at the floor: bottles do not help");
    }

    @Test
    void bottlesToMendCountOnlyWornMendingPiecesBelowTheTarget() {
        Armor b = boots(240);                                   // misses ceil(481 * 0.95) - 240 = 457 - 240 = 217
        Armor h = helmet(363, true);                            // full
        Armor plain = helmet(10, false);                        // no Mending: ignored
        Armor carried = new Armor(20, BOOTS, 10, 481, true);    // not worn: ignored
        assertEquals(217, GearGuard.missing(List.of(b, h, plain, carried)));
        assertEquals(16, GearGuard.bottlesToMend(List.of(b, h, plain, carried)), "217 / 14 rounded up");
        assertEquals(0, GearGuard.bottlesToMend(List.of(h)));
        assertEquals(240 + 363, GearGuard.uses(List.of(b, h, plain, carried)));
    }

    @Test
    void mostWornWornAndCarried() {
        Armor b = boots(100);
        Armor h = helmet(363, true);
        Armor carried = new Armor(12, HELMET, 5, 363, true);
        List<Armor> all = List.of(h, b, carried);
        assertEquals(List.of(h, b), GearGuard.worn(all));
        assertEquals(List.of(carried), GearGuard.carried(all));
        assertSame(carried, GearGuard.mostWorn(all, a -> true));
        assertSame(b, GearGuard.mostWorn(GearGuard.worn(all), a -> true));
        assertNull(GearGuard.mostWorn(all, a -> false));
        assertSame(b, GearGuard.inSlot(all, Armor.FEET));
        assertNull(GearGuard.inSlot(all, Armor.CHEST));
        assertTrue(GearGuard.isBoots(b));
        assertTrue(GearGuard.isBoots(new Armor(14, BOOTS, 1, 481, true)), "carried boots are boots too");
        assertFalse(GearGuard.isBoots(h));
    }

    @Test
    void describeAndList() {
        assertEquals("netherite_boots at 20% (100/481)", GearGuard.describe(boots(100)));
        assertEquals("diamond_helmet at 2% (10/363) (no Mending)", GearGuard.describe(helmet(10, false)));
        assertEquals("netherite_boots 20%, diamond_helmet 100%", GearGuard.list(List.of(boots(100), helmet(363, true))));
        assertEquals("feet", GearGuard.slotName(Armor.FEET));
        assertEquals("head", GearGuard.slotName(Armor.HEAD));
        assertTrue(GearGuard.mended(boots(457)));
        assertFalse(GearGuard.mended(boots(456)));
    }
}
