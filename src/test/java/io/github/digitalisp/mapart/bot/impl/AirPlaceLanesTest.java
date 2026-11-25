package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AIRPLACE lanes against LOOK lanes on the simulated platform ({@link io.github.digitalisp.mapart.testutil.LaneSim}), built by
 * the production planner and {@link LaneRun} with repair passes re-planned from the world like VERIFY.
 *
 * <p>AIRPLACE (the new defaults): 9-wide lanes (airplace reaches 5.0 from the eye to the cell centre, no rotation, no
 * line of sight), reach 4.5; cells whose state follows the look (lichen, resin) still go by LOOK, with silent looks
 * (rotatePlayer false, grimRotate true), and so does every cell while the offhand holds a block. LOOK as live today:
 * 5-wide lanes, the real rotation, reach 4.3.
 */
class AirPlaceLanesTest {
    private static final int TILE_CELLS = TileArt.SIZE * TileArt.SIZE;

    /** The placement setups compared. */
    private enum Mode {
        /** The new defaults: 9-wide airplace lanes, LOOK cells with silent looks, reach 4.5. */
        AIRPLACE("AIRPLACE", false, 4.5),
        /** As live today: 5-wide LOOK lanes, the real rotation, reach 4.3. */
        LOOK_TODAY("LOOK", true, 4.3),
        /** 5-wide LOOK lanes with the new look defaults: silent looks, reach 4.5. */
        LOOK_SILENT("LOOK", false, 4.5);

        final String placeMode;
        final boolean rotatePlayer;
        final double reach;

        Mode(String placeMode, boolean rotatePlayer, double reach) {
            this.placeMode = placeMode;
            this.rotatePlayer = rotatePlayer;
            this.reach = reach;
        }
    }

    private static TileRunner runner(Mode m, long seed) {
        TileRunner r = new TileRunner(seed);
        r.build.placeMode = m.placeMode;
        r.build.rotatePlayer = m.rotatePlayer;
        r.build.grimRotate = true;
        r.build.reach = m.reach;
        return r;
    }

    /**
     * Built, no lane failed, nothing given up as unreachable, never walled in, never into its own box, no wrong click,
     * never off the platform, no airplace beyond the server's range, one placement per tick, bounded walking.
     */
    private static void assertBuilt(TileRunner r, TileArt art, boolean built, double maxOverhead) {
        String rep = report(r, art);
        assertTrue(built, "tile not built\n" + rep);
        assertEquals(0, r.failures, "lane failures\n" + rep);
        assertTrue(r.sim.skipReasons.keySet().stream().noneMatch(k -> k.contains("not reachable")),
            "a cell was given up as not reachable\n" + rep);
        assertEquals(0, r.sim.pocketTicks, "the bot stood on the floor walled in on all four sides\n" + rep);
        assertEquals(0, r.sim.boxRejects, "placement into the bot's own box\n" + rep);
        assertEquals(0, r.sim.wrongClicks, "click without a target or with the wrong item\n" + rep);
        assertEquals(0, r.sim.fellOff, "walked off the platform\n" + rep);
        assertEquals(0, r.sim.farRejects, "airplace refused beyond the server's range\n" + rep);
        assertEquals(1, r.sim.maxPlacementsPerTick, "placements per tick\n" + rep);
        assertTrue(r.walkOverhead() <= maxOverhead, "walking overhead " + r.walkOverhead() + "\n" + rep);
    }

    private static String report(TileRunner r, TileArt art) {
        return r.report(art) + "\nairPlaces=" + r.sim.airPlaces + " farRejects=" + r.sim.farRejects + " realTurns="
            + r.sim.realTurns + " silentLooks=" + r.sim.silentLooks + " maxPlacementsPerTick=" + r.sim.maxPlacementsPerTick
            + " dropped=" + r.sim.dropped + " overhead=" + String.format("%.3f", r.walkOverhead());
    }

    private static TileRunner run(TileArt art, Mode m, long seed, double missRate, int repairPasses, double maxOverhead) {
        TileRunner r = runner(m, seed);
        r.sim.missRate = missRate;
        boolean built = r.build(art, repairPasses);
        assertBuilt(r, art, built, maxOverhead);
        return r;
    }

    /** Cells of {@code art} that go by {@code method} in AIRPLACE mode ({@link TopFaceRules#placeMethod}). */
    private static int cellsBy(TileArt art, PlaceMethod method) {
        int n = 0;
        for (int x = 0; x < TileArt.SIZE; x++) {
            for (int z = 0; z < TileArt.SIZE; z++) {
                BlockSpec b = art.get(x, z);
                if (b != null && TopFaceRules.placeMethod(b, PlaceMode.AIRPLACE) == method) n++;
            }
        }
        return n;
    }

    private static double walking(TileRunner r) {
        return r.sim.walked + r.sim.baritoneDistance;
    }

    // ------------------------------------------------------------------------------------------ full carpet tile

    /** One full carpet-tile run per mode, shared by the tests (a full tile takes about a second). */
    private static final Map<Mode, CarpetRun> CARPET = new EnumMap<>(Mode.class);

    /**
     * A full white-carpet tile built in one pass, the column of each cell off its lane's walking line (BUILD plan), and
     * the bot's distance off that line (across the lane) when the server accepted the cell.
     */
    private record CarpetRun(TileRunner r, TileArt art, Map<Pos, Integer> column, Map<Pos, Double> botOffLine) { }

    private static synchronized CarpetRun carpet(Mode m) {
        return CARPET.computeIfAbsent(m, k -> {
            TileArt art = TileRunner.fill("minecraft:white_carpet", 0, 0, 128, 128);
            TileRunner r = runner(k, 21);
            Map<Pos, Integer> lineX = new HashMap<>();
            Map<Pos, Integer> column = new HashMap<>();
            for (Lane l : r.plan(art)) {
                for (Placement p : l.placements()) {
                    lineX.put(p.pos(), l.from().x());
                    column.put(p.pos(), p.pos().x() - l.from().x());
                }
            }
            Map<Pos, Double> off = new HashMap<>();
            r.sim.onAccept = p -> off.put(p.pos(), Math.abs(r.sim.x - (lineX.get(p.pos()) + 0.5)));
            boolean built = r.build(art, 0);
            assertBuilt(r, art, built, 1.12);
            return new CarpetRun(r, art, column, off);
        });
    }

    private static void summary(Mode m, TileRunner r) {
        System.out.printf("AIRPLACE-SIM %-11s ticks=%d lanes=%d passes=%d walked=%.0f baritone=%.0f (%d walks)"
                + " walking=%.0f overhead=%.3f clicks=%d airPlaces=%d realTurns=%d silentLooks=%d spotLanes=%d"
                + " blocks/tick=%.3f minutes@20TPS=%.1f%n",
            m, r.ticks, r.lanesRun, r.passes, r.sim.walked, r.sim.baritoneDistance, r.sim.baritoneWalks, walking(r),
            r.walkOverhead(), r.sim.clicks, r.sim.airPlaces, r.sim.realTurns, r.sim.silentLooks, r.spotLanes(),
            (double) TILE_CELLS / r.ticks, r.ticks / 20.0 / 60.0);
    }

    @Test
    void fullCarpetTileAirPlaceVersusLook() {
        CarpetRun air = carpet(Mode.AIRPLACE);
        CarpetRun today = carpet(Mode.LOOK_TODAY);
        CarpetRun silent = carpet(Mode.LOOK_SILENT);
        summary(Mode.AIRPLACE, air.r());
        summary(Mode.LOOK_TODAY, today.r());
        summary(Mode.LOOK_SILENT, silent.r());

        TileRunner a = air.r();
        TileRunner t = today.r();
        String rep = report(a, air.art());
        assertEquals(1, a.passes, "built in the BUILD pass\n" + rep);
        assertEquals(15, a.lanesRun, "15 lanes: 14 of 9 columns, the last of 2\n" + rep);
        assertTrue(a.ticks <= 0.8 * t.ticks, "ticks " + a.ticks + " vs LOOK today " + t.ticks + "\n" + rep);
        assertTrue(walking(a) <= 0.65 * walking(t), "walking " + walking(a) + " vs LOOK today " + walking(t) + "\n" + rep);
        assertEquals(0, a.sim.realTurns, "no real rotation for carpets\n" + rep);
        assertEquals(0, a.sim.silentLooks, "no silent look for carpets either: no rotation packet at all\n" + rep);
        assertEquals(TILE_CELLS, a.sim.airPlaces, "every cell airplaced, once\n" + rep);
        assertEquals(TILE_CELLS, a.sim.clicks, "no LOOK click\n" + rep);

        String trep = report(t, today.art());
        assertEquals(1, t.passes, trep);
        assertEquals(26, t.lanesRun, "LOOK lanes are 5 wide\n" + trep);
        assertEquals(0, t.sim.airPlaces, trep);
        assertTrue(t.sim.realTurns > 0, "LOOK today turns the real rotation\n" + trep);
        assertEquals(0, t.sim.silentLooks, trep);

        TileRunner s = silent.r();
        String srep = report(s, silent.art());
        assertEquals(1, s.passes, srep);
        assertEquals(26, s.lanesRun, srep);
        assertEquals(0, s.sim.airPlaces, srep);
        assertEquals(0, s.sim.realTurns, "silent LOOK keeps the real rotation\n" + srep);
        assertTrue(s.sim.silentLooks > 0, "silent LOOK sends silent looks\n" + srep);
    }

    @Test
    void airPlaceLanesAreNineWideAndTheWalkPlacesTheOuterColumns() {
        TileArt art = TileRunner.fill("minecraft:white_carpet", 0, 0, 128, 128);
        TileRunner planned = runner(Mode.AIRPLACE, 22);
        assertEquals(4, PlaceMode.laneHalfWidth(planned.build), "airplace range 5.0: half width 4");
        List<Lane> lanes = planned.plan(art);
        assertEquals(15, lanes.size());
        for (Lane l : lanes) {
            int min = l.placements().stream().mapToInt(p -> p.pos().x()).min().orElseThrow();
            int max = l.placements().stream().mapToInt(p -> p.pos().x()).max().orElseThrow();
            assertTrue(max - min + 1 <= 9, "lane " + l.index() + " covers " + (max - min + 1) + " columns");
            assertTrue(l.from().x() >= min && l.from().x() <= max, "walking line inside lane " + l.index());
            if (l.index() < 14) {
                assertEquals(9, max - min + 1, "lane " + l.index() + " is 9 wide");
                assertEquals(4, l.from().x() - min, "lane " + l.index() + " walks its middle column");
            }
        }
        assertEquals(26, runner(Mode.LOOK_SILENT, 22).plan(art).size(), "LOOK lanes stay 5 wide");

        // every outer-column cell (4 off the line) airplaced from the walk: no stand spot, the bot on its line
        CarpetRun air = carpet(Mode.AIRPLACE);
        TileRunner r = air.r();
        String rep = report(r, air.art());
        assertEquals(0, r.spotLanes(), "no stand spots on a carpet tile\n" + rep);
        assertEquals(0, r.sim.jumps, rep);
        int outer = 0;
        double worst = 0;
        for (Map.Entry<Pos, Integer> e : air.column().entrySet()) {
            if (Math.abs(e.getValue()) != 4) continue;
            outer++;
            Double off = air.botOffLine().get(e.getKey());
            assertNotNull(off, "outer cell " + e.getKey() + " never accepted\n" + rep);
            worst = Math.max(worst, off);
        }
        assertEquals(14 * 2 * TileArt.SIZE, outer, "the outer columns of the 14 full lanes");
        assertTrue(worst <= 0.5, "an outer cell was placed with the bot " + worst + " off its walking line\n" + rep);
    }

    // ------------------------------------------------------------------------------------------ mixed tiles

    @Test
    void liveStripesTakeLichenAndResinByLook() {
        TileArt art = TileRunner.liveStripes();
        TileRunner r = run(art, Mode.AIRPLACE, 1, 0, 0, 1.35);
        String rep = report(r, art);
        assertEquals(1, r.passes, rep);
        int look = cellsBy(art, PlaceMethod.LOOK);
        assertEquals(64, look, "lichen and resin: 2 columns each, 16 rows");
        assertEquals(art.nonEmptyCount() - look, r.sim.airPlaces, "every other cell airplaced, once\n" + rep);
        assertEquals(look, r.sim.clicks - r.sim.airPlaces, "one LOOK click per lichen / resin cell\n" + rep);
        assertTrue(r.sim.silentLooks > 0, "lichen and resin go by silent LOOK\n" + rep);
        assertEquals(0, r.sim.realTurns, rep);
        // resin in lane 7's outer column, hidden behind petals: see liveStripesWithoutStandSpots
        assertTrue(r.spotLanes() <= 1, "stand spots in " + r.spotLanes() + " lanes\n" + rep);
    }

    /**
     * Lane 7 (columns 63-71, line 67) has resin (LOOK) in its outer column 71, 4 off the line, and pink petals
     * (airplaced, outline 3/16) in columns 69-70. Once the petals stand, the resin's support face is only visible from
     * the line beyond about 3.96 across, about 4.28 from the eye: out of the walking LOOK reach (4.5 − step − 0.05 ≈
     * 4.23). With the 5-wide shading height ({@code LaneRun.SHADE} 0.5) the petals went first and 12 of the 16 resin
     * cells went to 4 stand spots (the tile 1096 ticks, overhead 1.275); in AIRPLACE mode anything above a carpet
     * shades ({@code LaneRun.AIR_SHADE}), so the resin goes first.
     */
    @Test
    void liveStripesWithoutStandSpots() {
        TileArt art = TileRunner.liveStripes();
        TileRunner r = run(art, Mode.AIRPLACE, 1, 0, 0, 1.2);
        assertEquals(0, r.spotLanes(), report(r, art));
    }

    @Test
    void checkerboardOfFullBlocksAndCarpet() {
        // rows 0-63 against the noobline, the west tile edge: every carpet ends up walled in on four sides
        TileArt art = TileRunner.checkerboard(0, 0, 64, 64);
        TileRunner r = run(art, Mode.AIRPLACE, 3, 0, 0, 1.1);
        String rep = report(r, art);
        assertEquals(1, r.passes, rep);
        assertEquals(0, r.spotLanes(), rep);
        assertEquals(art.nonEmptyCount(), r.sim.airPlaces, rep);
        assertEquals(art.nonEmptyCount(), r.sim.clicks, rep);
        assertEquals(0, r.sim.realTurns + r.sim.silentLooks, "no rotation packet\n" + rep);
    }

    @Test
    void ringOfFullBlocksAroundCarpet() {
        TileArt art = TileRunner.ring(10, 0, 70, 50, 20, 10, 60, 40, 2);
        TileRunner r = run(art, Mode.AIRPLACE, 7, 0, 0, 1.1);
        String rep = report(r, art);
        assertEquals(1, r.passes, rep);
        assertEquals(0, r.spotLanes(), rep);
        assertEquals(art.nonEmptyCount(), r.sim.airPlaces, rep);
    }

    @Test
    void randomNoise() {
        for (long seed : new long[]{8, 9, 10}) {
            TileArt art = TileRunner.noise(seed, 0, 0, 64, 64, 0.3);
            TileRunner r = run(art, Mode.AIRPLACE, seed, 0, 0, 1.1);
            String rep = "seed " + seed + "\n" + report(r, art);
            assertEquals(1, r.passes, rep);
            assertEquals(0, r.spotLanes(), rep);
            assertEquals(art.nonEmptyCount(), r.sim.airPlaces, "roots, plates, petals, leaves: all airplaced\n" + rep);
        }
        // mostly full blocks, the far corner of the tile
        TileArt art = TileRunner.noise(11, 64, 64, 128, 128, 0.7);
        TileRunner r = run(art, Mode.AIRPLACE, 11, 0, 0, 1.1);
        assertEquals(1, r.passes, report(r, art));
    }

    // ------------------------------------------------------------------------------------------ dropped placements

    @Test
    void droppedPlacementsAreRepaired() {
        // 5 % of the placements dropped by the "server": the misses slow the pacer down (it sits near its maximum
        // interval of 8, in LOOK as well) and the repair passes place what the walk could not retry
        TileArt[] arts = {TileRunner.liveStripes(), TileRunner.noise(32, 0, 0, 64, 64, 0.3),
            TileRunner.fill("minecraft:white_carpet", 0, 0, 64, 64)};
        for (int i = 0; i < arts.length; i++) {
            TileRunner r = run(arts[i], Mode.AIRPLACE, 31 + i, 0.05, 4, 2.0);
            String rep = report(r, arts[i]);
            assertTrue(r.sim.dropped > 0, rep);
            assertTrue(r.passes <= 3, "repair passes: " + (r.passes - 1) + "\n" + rep);
        }
    }

    // ------------------------------------------------------------------------------------------ offhand busy

    @Test
    void withoutAirPlaceEveryCellGoesByLookInNineWideLanes() {
        // the offhand holds a block (or its guard gave up): AIRPLACE lanes stay 9 wide, every cell by silent LOOK
        TileArt carpet = TileRunner.fill("minecraft:white_carpet", 0, 0, 45, 64);
        TileRunner r = runner(Mode.AIRPLACE, 41);
        r.sim.airPlaceReady = false;
        assertBuilt(r, carpet, r.build(carpet, 2), 1.1);
        String rep = report(r, carpet);
        assertEquals(1, r.passes, rep);
        assertEquals(5, r.lanesRun, "5 lanes of 9\n" + rep);
        assertEquals(0, r.sim.airPlaces, rep);
        assertEquals(carpet.nonEmptyCount(), r.sim.clicks, rep);
        assertTrue(r.sim.silentLooks > 0, rep);
        assertEquals(0, r.sim.realTurns, rep);

        TileArt noise = TileRunner.noise(42, 0, 0, 64, 64, 0.3);
        TileRunner n = runner(Mode.AIRPLACE, 42);
        n.sim.airPlaceReady = false;
        // full blocks wall the outer LOOK cells of 9-wide lanes: stand spots and climbing (measured: 10751 ticks,
        // overhead 2.09, 6 lanes with stand spots; airplaced 4574 ticks; 5-wide silent LOOK lanes 7223 ticks)
        assertBuilt(n, noise, n.build(noise, 2), 2.3);
        String nrep = report(n, noise);
        assertEquals(0, n.sim.airPlaces, nrep);
        assertTrue(n.sim.silentLooks > 0, nrep);
        assertEquals(0, n.sim.realTurns, nrep);
    }
}
