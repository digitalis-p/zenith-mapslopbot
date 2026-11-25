package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live 2026-11-05, Edgerunners [3,6] lane 14 (soul sand, tile-relative cells): a diagonal of full blocks and
 * pointed dripstone from column 119 row 127 to column 127 row 122 shuts the south-east corner (beside the east trench,
 * a no-go zone past column 127) into a pocket. Its only way out on the floor was through the last pointed-dripstone
 * holes, then up onto a full block of the diagonal (Baritone climbs it). The bot stood in the pocket; every repair pass
 * walked to a floor spot inside it (floor routes never climb), where placing a hole would close its own way out, so
 * the way-out guard held the hole back: 0 clicks, "not reachable from the lane or 4 stand spots", BLOCKED after 4
 * passes (16:24 with holes (123,126) and (124,126); 18:41 with hole (126,124)).
 */
class PocketExitCellsTest {
    static final BlockSpec DRIP = BlockSpec.of("minecraft:pointed_dripstone");
    static final BlockSpec FULL = BlockSpec.of("minecraft:gray_terracotta");
    static final BlockSpec CARPET = BlockSpec.of("minecraft:black_carpet");
    static final BlockSpec PLATE = BlockSpec.of("minecraft:spruce_pressure_plate");

    /** Columns 116..127, rows 118..127 at 16:24 (from the replay). c carpet, p plate, # full, D dripstone, O hole. */
    static final String[] AT_1624 = {
        "cccccccccccc",
        "cccccccccccc",
        "cccccccccccc",
        "cccccccccccc",
        "ccccccccccc#",
        "ccccccccc##D",
        "cccccccc#DD#",
        "ccccccc#DDcc",
        "ccccc##OOccp",
        "ccc##DDDcppp",
    };

    /** 18:41: the same diagonal, the holes of 16:24 filled, the one hole (126,124) left. */
    static final String[] AT_1841 = {
        "cccccccccccc",
        "cccccccccccc",
        "cccccccccccc",
        "cccccccccccc",
        "ccccccccccc#",
        "ccccccccc##D",
        "cccccccc#DO#",
        "ccccccc#DDcc",
        "ccccc##DDccp",
        "ccc##DDDcppp",
    };

    record Fixture(TileRunner runner, TileArt art, List<Pos> holes) { }

    static Fixture corner(String[] rows, int botCol, int botRow) {
        TileArt art = TileRunner.fill(CARPET.id(), 0, 0, 128, 128);
        List<int[]> holeCells = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            for (int j = 0; j < rows[i].length(); j++) {
                int col = 116 + j, row = 118 + i;
                char c = rows[i].charAt(j);
                art.set(col, row, switch (c) {
                    case '#' -> FULL;
                    case 'D', 'O' -> DRIP;
                    case 'p' -> PLATE;
                    default -> CARPET;
                });
                if (c == 'O') holeCells.add(new int[]{col, row});
            }
        }
        TileRunner r = LaneReentryTest.runner(5, true);
        r.finiteStock = false;
        // the live build flags that bear on this (mapart.json 2026-11-05)
        r.build.rotatePlayer = true;
        r.build.airPlaceRange = 5.0;
        r.build.laneWayOut = true;
        r.build.laneKeepReachable = true;
        r.build.verifySealOut = true;
        r.build.verifyBreakIn = true;
        r.build.laneStartSpots = true;
        r.build.standSpotWalkable = true;
        r.build.laneEvenWidths = true;
        r.build.placeInReachGreedy = true;
        r.sim.dripstoneBreakThrough = true;
        r.sim.edgeSneak = true;
        r.sim.edgeSpotsLast = true;
        r.sim.wallIds = LaneWayOutTest.WALLS;
        r.sim.platformBlock = BlockSpec.of("minecraft:soul_sand");
        r.sim.standCells = p -> r.sim.tileArea().contains(p.x(), p.z());
        // column 127 is beside the east trench (a no-go zone past the tile)
        r.sim.edgeCells = p -> p.x() - r.geo.minX() == 127;
        for (int col = 0; col < 128; col++) for (int row = 0; row < 128; row++) {
            r.world.put(KeepReachableTest.cell(r, col, row), art.get(col, row));
        }
        List<Pos> holes = new ArrayList<>();
        for (int[] h : holeCells) {
            Pos p = KeepReachableTest.cell(r, h[0], h[1]);
            r.world.remove(p);
            holes.add(p);
        }
        Pos bot = KeepReachableTest.cell(r, botCol, botRow);
        r.sim.teleport(bot.x() + 0.5, r.geo.artY(), bot.z() + 0.5);
        return new Fixture(r, art, holes);
    }

    static List<String> placedHoles(Fixture f) {
        List<String> out = new ArrayList<>();
        for (Pos h : f.holes()) {
            if (TopFaceRules.equivalent(DRIP, f.runner().world.getOrDefault(h, BlockSpec.AIR))) {
                out.add((h.x() - f.runner().geo.minX()) + "," + (h.z() - f.runner().geo.minZ()));
            }
        }
        return out;
    }

    /** Tile-relative cells of the corner pocket (beside the trench) and the full block it ends at. */
    static final List<int[]> POCKET = List.of(new int[]{126, 125}, new int[]{127, 125}, new int[]{125, 126},
        new int[]{126, 126}, new int[]{127, 126}, new int[]{124, 127}, new int[]{125, 127}, new int[]{126, 127},
        new int[]{127, 127}, new int[]{127, 124});

    static boolean inPocket(TileRunner r) {
        int col = (int) Math.floor(r.sim.x) - r.geo.minX();
        int row = (int) Math.floor(r.sim.z) - r.geo.minZ();
        for (int[] c : POCKET) if (c[0] == col && c[1] == row) return true;
        return false;
    }

    /** VERIFY from the bot in the pocket; every hole goes up with the bot outside the pocket (the way out kept). */
    static Fixture repair(String[] rows, int botCol, int botRow) {
        Fixture f = corner(rows, botCol, botRow);
        TileRunner r = f.runner();
        List<String> placedFromPocket = new ArrayList<>();
        r.sim.onAccept = p -> {
            if (f.holes().contains(p.pos()) && inPocket(r)) placedFromPocket.add(p.pos().toString());
        };
        boolean ok = r.verify(f.art(), 4);
        assertTrue(ok, "holes placed " + placedHoles(f) + "\n" + r.report(f.art()) + "\nlog:\n"
            + String.join("\n", r.sim.log));
        assertEquals(f.holes().size(), placedHoles(f).size());
        assertEquals(1, r.passes, "one repair pass: " + r.report(f.art()));
        assertEquals(List.of(), placedFromPocket, "the guard still holds: no hole closes the bot in");
        assertTrue(r.sim.log.stream().anyMatch(l -> l.contains("would close the bot's way out")), r.report(f.art()));
        assertTrue(r.sim.log.stream().noneMatch(l -> l.contains("not reachable from the lane or")), r.report(f.art()));
        assertTrue(r.sim.skipReasons.isEmpty(), r.report(f.art()));
        return f;
    }

    @Test
    void the1624PocketHolesArePlacedFromOutsideThePocket() {
        repair(AT_1624, 125, 126);
    }

    @Test
    void the1841PocketHoleIsPlacedFromOutsideThePocket() {
        repair(AT_1841, 127, 126);
    }

    @Test
    void fromOutsideThePocketTheLaneWalksInAndStillPlacesFromOutside() {
        // live the bot came in with lane 14's walk: from the open floor the run ends the same way
        Fixture f = corner(AT_1624, 118, 120);
        TileRunner r = f.runner();
        assertTrue(r.verify(f.art(), 4), r.report(f.art()));
        assertEquals(2, placedHoles(f).size());
        assertTrue(r.sim.skipReasons.isEmpty(), r.report(f.art()));
    }

    @Test
    void anOpenFloorHoleKeepsTheSpotSearchItHad() {
        // a dripstone hole in the open: it closes nothing, the new branch never runs
        String[] open = AT_1624.clone();
        open[2] = "ccccOccccccc";
        open[8] = "ccccc##DDccp";
        Fixture f = corner(open, 112, 112);
        TileRunner r = f.runner();
        assertTrue(r.verify(f.art(), 4), r.report(f.art()));
        assertEquals(List.of("120,120"), placedHoles(f));
        assertTrue(r.sim.log.stream().noneMatch(l -> l.contains("would close the bot's way out")), r.report(f.art()));
    }
}
