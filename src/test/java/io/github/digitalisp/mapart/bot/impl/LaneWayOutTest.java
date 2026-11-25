package io.github.digitalisp.mapart.bot.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lane walk never ends walled in by its own blocks that the pathfinder can neither walk through nor onto
 * ({@code build.laneWayOut}, LaneRun class javadoc "Way out"), on the art of the live incident (2026-10-12 10:09,
 * ellen [0,6] on moss, fixtures/art/ellen_0_6.json): lane 8 (columns 72-80, walking line column 76) crosses a
 * pointed-dripstone field around rows 59-72. Its first pass came from the south end and stopped at row 67; the second,
 * from the north, placed dripstone around and behind the bot and ended at column 76, row 66 with dripstone on every
 * side; lane 9's start was unreachable, BLOCKED after 6 attempts. On the simulated platform ({@code LaneSim.wallIds}:
 * Baritone neither walks through nor onto dripstone and pots). Tile cell (col, row) is (minX + col, minZ + row).
 */
class LaneWayOutTest {
    static final Set<String> WALLS = Set.of("minecraft:pointed_dripstone", "minecraft:decorated_pot");

    static TileArt load(String name) throws Exception {
        JsonObject o;
        try (InputStream in = LaneWayOutTest.class.getResourceAsStream("/fixtures/art/" + name + ".json")) {
            assertNotNull(in);
            o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<BlockSpec> palette = new ArrayList<>();
        for (var e : o.getAsJsonArray("palette")) palette.add(BlockSpec.parse(e.getAsString()));
        ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(o.get("cells").getAsString()))
            .order(ByteOrder.LITTLE_ENDIAN);
        TileArt art = new TileArt();
        for (int row = 0; row < TileArt.SIZE; row++) {
            for (int col = 0; col < TileArt.SIZE; col++) {
                int i = buf.getShort();
                if (i >= 0) art.set(col, row, palette.get(i));
            }
        }
        return art;
    }

    /** Lane 8's columns of the art (72-80). */
    static TileArt lane8() throws Exception {
        TileArt art = load("ellen_0_6");
        TileArt out = new TileArt();
        for (int col = 72; col <= 80; col++) {
            for (int row = 0; row < TileArt.SIZE; row++) {
                BlockSpec b = art.get(col, row);
                if (b != null) out.set(col, row, b);
            }
        }
        return out;
    }

    /** The live set-up: AIRPLACE 9-wide lanes, dripstone a wall to Baritone, the first pass placed rows 67 and on. */
    static TileRunner runner(TileArt art, boolean guard) {
        TileRunner r = LaneReentryTest.runner(5, true);
        r.finiteStock = false;
        r.sim.wallIds = WALLS;
        r.build.laneWayOut = guard;
        for (int col = 72; col <= 80; col++) {
            for (int row = 67; row < TileArt.SIZE; row++) {
                BlockSpec b = art.get(col, row);
                if (b != null) r.world.put(new Pos(r.geo.minX() + col, r.geo.artY(), r.geo.minZ() + row), b);
            }
        }
        return r;
    }

    /** The bot can walk away from where it stands: to the walkway end of the walking line (row 1, column 76). */
    static boolean wayOut(TileRunner r) {
        return r.sim.reachable(r.geo.minX() + 76, r.geo.minZ() + 1);
    }

    static String where(TileRunner r) {
        return String.format("bot at col %.2f row %.2f y %.2f", r.sim.x - r.geo.minX(), r.sim.z - r.geo.minZ(), r.sim.y);
    }

    @Test
    void withoutTheGuardTheSecondPassEndsCagedAsLive() throws Exception {
        TileArt art = lane8();
        TileRunner r = runner(art, false);
        Lane l = r.plan(art).getFirst();
        LaneReentryTest.Pass p = LaneReentryTest.run(r, l);
        System.out.println("guard off: " + p + " " + where(r));
        assertFalse(wayOut(r), "walled in: " + where(r));
    }

    @Test
    void withTheGuardTheWayOutStaysOpenAndRepairPassesFinishIt() throws Exception {
        TileArt art = lane8();
        TileRunner r = runner(art, true);
        Lane l = r.plan(art).getFirst();
        LaneReentryTest.Pass p = LaneReentryTest.run(r, l);
        System.out.println("guard on: " + p + " " + where(r) + "; left " + r.mismatches(art).size());
        assertTrue(wayOut(r), where(r));
        assertTrue(p.log().contains("walking line ends in full blocks"), "no walk onto the dripstone: " + p.log());
        // VERIFY's repair passes, the bot coming back from the chests each time: never walled in
        for (int pass = 1; pass <= 3 && !r.plan(art).isEmpty(); pass++) {
            for (Lane rl : r.plan(art)) {
                LaneReentryTest.Pass rp = LaneReentryTest.run(r, rl);
                System.out.println("repair " + pass + ": " + rp + " " + where(r) + "; left " + r.mismatches(art).size());
                assertTrue(wayOut(r), "repair " + pass + ": " + where(r));
            }
        }
        List<String> left = r.mismatches(art);
        assertTrue(left.isEmpty(), "left after the repair passes: " + left);
    }

    /**
     * 10:18: lane 9 (columns 81-89, line column 85) restarted at its first pass's last row, 78, whose line cell is a
     * placed dripstone: the walk over the line wanted the bot on top of it, Baritone cannot stand there, the lane start
     * was unreachable 6 times, BLOCKED. Now the line's dripstone ends the walk instead (it starts after it).
     */
    @Test
    void aRestartCellThatIsAPlacedDripstoneIsNoWalkStart() throws Exception {
        TileArt full = load("ellen_0_6");
        TileArt art = new TileArt();
        for (int col = 81; col <= 89; col++) {
            for (int row = 0; row < TileArt.SIZE; row++) {
                BlockSpec b = full.get(col, row);
                if (b != null) art.set(col, row, b);
            }
        }
        assertTrue(WALLS.contains(art.get(85, 78).id()));
        for (boolean guard : new boolean[]{false, true}) {
            TileRunner r = LaneReentryTest.runner(5, true);
            r.finiteStock = false;
            r.sim.wallIds = WALLS;
            r.build.laneWayOut = guard;
            for (int col = 81; col <= 89; col++) {
                for (int row = 78; row < TileArt.SIZE; row++) {
                    BlockSpec b = art.get(col, row);
                    if (b != null) r.world.put(new Pos(r.geo.minX() + col, r.geo.artY(), r.geo.minZ() + row), b);
                }
            }
            Lane l = r.plan(art).getFirst();
            LaneReentryTest.Pass p = LaneReentryTest.run(r, l);
            System.out.println("restart, guard " + guard + ": " + p.status() + " " + p + " " + where(r));
            boolean out = r.sim.reachable(r.geo.minX() + 85, r.geo.minZ() + 1);
            if (!guard) {
                // the walk over the line's dripstone (live: its start on top of it, which Baritone cannot reach)
                assertTrue(p.log().contains("walking over them"), p.log());
                continue;
            }
            assertFalse(p.log().contains("walking over them"), p.log());
            assertFalse(p.log().contains("cannot reach"), p.log());
            assertTrue(out, where(r));
        }
    }
}
