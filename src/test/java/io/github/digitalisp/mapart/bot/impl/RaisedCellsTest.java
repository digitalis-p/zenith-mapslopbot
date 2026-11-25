package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.RestockLoads;
import io.github.digitalisp.mapart.testutil.LaneSim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Raised cells (docs/COLOR_SUPPRESSION.md, {@code build.suppressionMergeLevels}):
 * a colour-suppression layer's level-1 cells, one block above the art layer, placed by the level-0 lanes with the feet
 * on the glass. LaneSim's second level ({@link LaneSim#raisedLevel}) holds them: a raised block over air is a wall at
 * head height a box on the floor can neither pass under nor climb. Each case runs the same lane with the run told about
 * the raised level ({@link LaneSim#raisedRun}: the change) and without it (the lane placer as it was), and prints both.
 * Lanes are 9 wide airplace lanes of full blocks with the live colour-suppression settings ({@link LaneTailTest}).
 */
class RaisedCellsTest {
    private static final String[] BLOCKS = {"minecraft:white_concrete", "minecraft:black_concrete", "minecraft:gray_concrete",
        "minecraft:brown_concrete", "minecraft:orange_concrete", "minecraft:yellow_concrete", "minecraft:red_concrete",
        "minecraft:blue_concrete", "minecraft:lime_concrete", "minecraft:cyan_concrete", "minecraft:oak_planks",
        "minecraft:spruce_planks"};
    private static final int ART_Y = 201;
    private static final int MIN = -64;

    /** A cell of the lane: its block and level (0 on the glass, 1 raised). */
    record Cell(BlockSpec spec, int level) { }

    interface Art {
        /** The cell at tile column {@code col} and row {@code row}, or null. */
        Cell at(int col, int row);
    }

    static final class Fixture {
        final Map<Pos, BlockSpec> world = new HashMap<>();
        final MapArtConfig.BuildCfg build = new MapArtConfig.BuildCfg();
        final LaneSim sim;
        int placed, skipped, fromSpots;
        long ticks;
        final Map<String, Integer> skips = new java.util.TreeMap<>();

        Fixture(long seed, boolean raisedRun) {
            sim = new LaneSim(new LaneSim.Blocks() {
                @Override public BlockSpec get(Pos p) { return world.getOrDefault(p, BlockSpec.AIR); }
                @Override public void set(Pos p, BlockSpec b) { world.put(p, b); }
            }, new LaneSim.Items() {
                @Override public int count(String item) { return 10_000; }
                @Override public boolean take(String item) { return true; }
            }, MIN, MIN, ART_Y, new AdaptivePacer(1, 1, 4), seed);
            sim.platformBlock = BlockSpec.of("minecraft:glass");
            sim.confirmDelay = 2;
            sim.countLag = 2;
            sim.echoes = true;
            sim.paperWindowTicks = 6;
            sim.paperWindowMax = 9;
            sim.stackSize = 64;
            sim.raisedLevel = true;
            sim.raisedRun = raisedRun;
            build.placeMode = "AIRPLACE";
            build.rotatePlayer = true;
            build.grimRotate = true;
            build.reach = 4.5;
            build.airPlaceRange = 5.0;
            build.pacedLaneWalk = true;
            build.laneStepBack = true;
            build.placeClicksPerTick = 3;
            build.placeClickWindowTicks = 7;
        }

        /** Runs {@code lane}; its status. */
        TaskStatus run(Lane lane) {
            TaskStatus s = sim.runLane(lane, LaneRun.Params.of(build, sim.tileArea()), new PlacementListener() {
                @Override public void placed(Placement p) { placed++; }
                @Override public void placedFromStandSpot(Placement p) {
                    placed++;
                    fromSpots++;
                }
                @Override public void skipped(Placement p, String reason) {
                    skipped++;
                    skips.merge(reason.replaceAll("[0-9.,-]+", "#"), 1, Integer::sum);
                }
            }, 60_000);
            ticks += sim.lastTicks;
            return s;
        }

        String stats() {
            return String.format(Locale.ROOT, "%d ticks, %d placed (%d from %d stand spots), %d skipped %s, box rejects %d,"
                    + " occupied %d, pocket ticks %d, narrow stands %d, on a raised top %d ticks / %d spots, max feet +%.2f,"
                    + " jumps %d, fell %d, into a block in flight %d / beside %d", ticks, placed, fromSpots,
                sim.spotGoals.size(), skipped, skips, sim.boxRejects, sim.occupiedRejects, sim.pocketTicks,
                sim.narrowStandTicks, sim.raisedStands, sim.raisedSpots, sim.maxFeetY - ART_Y, sim.jumps, sim.fellOff,
                sim.inFlightEntries, sim.sideRaceEntries);
        }

        /** Cells of {@code art} in columns c-4..c+4 that the world does not show. */
        int missing(Art art, int c) {
            int n = 0;
            for (int row = 0; row < 128; row++)
                for (int x = c - 4; x <= c + 4; x++) {
                    Cell cell = art.at(x - MIN, row);
                    if (cell == null) continue;
                    BlockSpec have = world.getOrDefault(new Pos(x, ART_Y + cell.level(), MIN + row), BlockSpec.AIR);
                    if (!have.equals(cell.spec())) n++;
                }
            return n;
        }
    }

    /**
     * The lane of columns c-4..c+4 (tile columns clamped to the tile) as the serpentine planner orders it: rows in the
     * walking direction from {@code first} to {@code last} (the walking line's ends), in each row nearest the line first,
     * west before east; only the cells the world does not show yet.
     */
    static Lane lane(Fixture f, Art art, int c, int first, int last) {
        List<Placement> ps = new ArrayList<>();
        boolean south = first <= last;
        for (int i = 0; i < 128; i++) {
            int row = south ? i : 127 - i;
            for (int d = 0; d <= 4; d++)
                for (int x : d == 0 ? new int[] {c} : new int[] {c - d, c + d}) {
                    if (x < MIN || x >= MIN + 128) continue;
                    Cell cell = art.at(x - MIN, row);
                    if (cell == null) continue;
                    Pos pos = new Pos(x, ART_Y + cell.level(), MIN + row);
                    if (!f.world.getOrDefault(pos, BlockSpec.AIR).isAir()) continue;
                    ps.add(new Placement(pos, cell.spec(), cell.spec().id()));
                }
        }
        return new Lane(0, new Pos(c, ART_Y, MIN + first), new Pos(c, ART_Y, MIN + last), List.copyOf(ps));
    }

    /** A random art: {@code low} of the cells on the glass, {@code high} raised, the rest empty, a few materials. */
    static Art random(long seed, double low, double high) {
        Map<Long, Cell> cells = new HashMap<>();
        Random r = new Random(seed);
        for (int col = 0; col < 128; col++)
            for (int row = 0; row < 128; row++) {
                double v = r.nextDouble();
                String b = BLOCKS[(int) Math.min(BLOCKS.length - 1, Math.abs(r.nextGaussian()) * 4)];
                if (v < low) cells.put(key(col, row), new Cell(BlockSpec.of(b), 0));
                else if (v < low + high) cells.put(key(col, row), new Cell(BlockSpec.of(b), 1));
            }
        return (col, row) -> cells.get(key(col, row));
    }

    static Art flat(Art art) {
        return (col, row) -> {
            Cell c = art.at(col, row);
            return c == null ? null : new Cell(c.spec(), 0);
        };
    }

    private static long key(int a, int b) {
        return ((long) a << 32) ^ (b & 0xffffffffL);
    }

    private static void sane(Fixture f, String what) {
        assertEquals(0, f.sim.boxRejects, "a click into the box: " + what);
        assertEquals(0, f.sim.pocketTicks, "walled in: " + what + " " + f.sim.pockets);
        assertEquals(0, f.sim.raisedStands, "stood on a raised block: " + what);
        assertEquals(0, f.sim.raisedSpots, "a stand spot on a raised block: " + what);
        assertEquals(0, f.sim.fellOff, what);
        assertEquals(0, f.sim.wrongClicks, what);
        assertTrue(f.sim.maxPlacementsPerTick <= 1, "more than one click a tick: " + what);
    }

    private static void table(String name, Fixture before, Fixture after, Fixture flat) {
        if (System.getenv("RAISED_LOG") != null) {
            System.out.println("LOG before " + name + "\n  " + String.join("\n  ", before.sim.log));
            System.out.println("LOG after " + name + "\n  " + String.join("\n  ", after.sim.log));
        }
        System.out.println("raised cells, " + name + ":\n  before (one level):  " + before.stats()
            + "\n  after (two levels):  " + after.stats()
            + (flat == null ? "" : "\n  all on the glass:    " + flat.stats()));
    }

    /**
     * Case 1: a fresh lane, 70 % of its cells on the glass and 16 % raised, spread over every column. With the raised
     * level everything goes up in one walk and its stand spots, nothing into the box, no stand spot on or under a
     * raised block, and the lane takes no longer than the same cells all on the glass.
     */
    @Test
    void aFreshLaneWithRaisedCellsInEveryColumn() {
        Art art = random(5, 0.70, 0.16);
        for (int[] line : new int[][] {{0, 127}, {127, 0}}) {
            Fixture before = new Fixture(5, false);
            Fixture after = new Fixture(5, true);
            Fixture flat = new Fixture(5, false);
            for (Fixture f : new Fixture[] {before, after}) {
                f.sim.teleport(-60 + 0.5, ART_Y, MIN + line[0] + 0.5);
                f.run(lane(f, art, -60, line[0], line[1]));
            }
            flat.sim.teleport(-60 + 0.5, ART_Y, MIN + line[0] + 0.5);
            flat.run(lane(flat, flat(art), -60, line[0], line[1]));
            String what = "line " + line[0] + "->" + line[1];
            table("fresh lane " + what, before, after, flat);
            assertEquals(0, after.missing(art, -60), what + ": " + after.stats());
            assertEquals(0, after.skipped, what);
            sane(after, what);
            assertTrue(after.ticks <= flat.ticks * 1.10 + 100, "raised cells hold the walk longer than blocks on the glass: "
                + after.ticks + " against " + flat.ticks);
        }
    }

    /**
     * Case 2: raised cells in the walking column, 3 in a row mid-lane and the lane's last 8: each goes up once the box
     * has passed it, the last ones at the lane end from the floor; the bot never climbs.
     */
    @Test
    void raisedCellsInTheWalkingColumn() {
        Random r = new Random(9);
        Map<Long, Cell> cells = new HashMap<>();
        for (int row = 0; row < 128; row++)
            for (int col = 0; col <= 8; col++) {
                boolean line = col == 4;
                if (line) {
                    if ((row >= 40 && row <= 42) || row >= 120)
                        cells.put(key(col, row), new Cell(BlockSpec.of(BLOCKS[row % 3]), 1));
                } else if (r.nextDouble() < 0.5) {
                    cells.put(key(col, row), new Cell(BlockSpec.of(BLOCKS[r.nextInt(4)]), r.nextDouble() < 0.2 ? 1 : 0));
                }
            }
        Art art = (col, row) -> cells.get(key(col, row));
        Fixture before = new Fixture(9, false);
        Fixture after = new Fixture(9, true);
        for (Fixture f : new Fixture[] {before, after}) {
            f.sim.teleport(-60 + 0.5, ART_Y, MIN + 0.5);
            f.run(lane(f, art, -60, 0, 127));
        }
        table("walking column 3 + last 8", before, after, null);
        assertEquals(0, after.missing(art, -60), after.stats());
        assertEquals(0, after.skipped);
        sane(after, "walking column");
        assertTrue(after.sim.maxFeetY < ART_Y + 1.3, "climbed: " + after.stats());
    }

    /**
     * Case 3: a lane cut by the restock planner at 600 and at 1,200 cells and entered again: the bot comes back over
     * the tops of the rows already built, the walk starts after the cut, and nothing is skipped.
     */
    @Test
    void aLaneCutAndEnteredAgain() {
        Art art = random(11, 0.70, 0.16);
        Fixture before = new Fixture(11, false);
        Fixture after = new Fixture(11, true);
        for (Fixture f : new Fixture[] {before, after}) {
            f.sim.teleport(-60 + 0.5, ART_Y, MIN + 0.5);
            for (int cut : new int[] {600, 600, Integer.MAX_VALUE}) {
                Lane all = lane(f, art, -60, 0, 127);
                if (all.placements().isEmpty()) break;
                // the walk to the lane again from the rows built, as from the chests north of them: over their tops
                Lane part = cut >= all.placements().size() ? all : RestockLoads.cut(all, cut);
                Lane restart = restartLine(part, all);
                comeBack(f, art);
                f.run(restart);
            }
        }
        table("cut at 600 and 1,200 and entered again", before, after, null);
        assertEquals(0, after.missing(art, -60), after.stats());
        assertEquals(0, after.skipped);
        sane(after, "re-entry");
    }

    /** The cut lane as the planner plans it again from the world: its line from one row before its first cell's row. */
    private static Lane restartLine(Lane part, Lane all) {
        int firstRow = part.placements().getFirst().pos().z() - MIN;
        int from = Math.max(0, firstRow - 1);
        return new Lane(0, new Pos(part.from().x(), ART_Y, MIN + from), part.to(), part.placements());
    }

    /** The bot back in the built rows near the lane's north end, on the floor or on a block, as from the chests. */
    private static void comeBack(Fixture f, Art art) {
        for (int row = 0; row < 4; row++)
            for (int x = -64; x <= -56; x++) {
                Cell c = art.at(x - MIN, row);
                if (c != null && c.level() == 1) continue;
                double y = c == null ? ART_Y : ART_Y + 1;
                if (c != null && f.world.getOrDefault(new Pos(x, ART_Y, MIN + row), BlockSpec.AIR).isAir()) y = ART_Y;
                f.sim.teleport(x + 0.5, y, MIN + row + 0.5);
                return;
            }
    }

    /**
     * Case 4: walling in. A cell on the glass ringed by four raised cells, and the lane's end, where the bot stands in
     * the last row with raised cells behind it and on both sides: "way out" holds the block that would close the bot
     * in back, "keep reachable" the one that would close a cell still to place; nothing is skipped.
     */
    @Test
    void raisedCellsRingACellAndTheBotsOwnPlace() {
        Random r = new Random(13);
        Map<Long, Cell> cells = new HashMap<>();
        for (int row = 0; row < 128; row++)
            for (int col = 0; col <= 8; col++) {
                if (col == 4) continue;
                if (r.nextDouble() < 0.3) cells.put(key(col, row), new Cell(BlockSpec.of(BLOCKS[r.nextInt(4)]), 0));
            }
        BlockSpec up = BlockSpec.of("minecraft:white_concrete");
        // (a) a cell on the glass with a raised cell on each side
        cells.put(key(6, 40), new Cell(BlockSpec.of("minecraft:red_concrete"), 0));
        for (int[] d : new int[][] {{5, 40}, {7, 40}, {6, 39}, {6, 41}}) cells.put(key(d[0], d[1]), new Cell(up, 1));
        // (b) the lane's last rows: the end cell, the cells beside it and the cells behind them raised
        for (int col = 3; col <= 5; col++) for (int row = 125; row <= 127; row++) cells.put(key(col, row), new Cell(up, 1));
        Art art = (col, row) -> cells.get(key(col, row));
        Fixture before = new Fixture(13, false);
        Fixture after = new Fixture(13, true);
        for (Fixture f : new Fixture[] {before, after}) {
            f.sim.teleport(-60 + 0.5, ART_Y, MIN + 0.5);
            f.run(lane(f, art, -60, 0, 127));
        }
        table("a ringed cell and the bot's own place", before, after, null);
        assertEquals(0, after.missing(art, -60), after.stats());
        assertEquals(0, after.skipped);
        sane(after, "walling in");
    }

    /**
     * Case 5: the tile's corner lanes with the lethal-edge clamp of the start tail ({@code build.laneStartTail}): the
     * west lane walking north from three rows inside the south edge, and the east lane (two columns, its line on the
     * tile's last column) walking south between the clamps; raised cells in the edge rows and columns.
     */
    @Test
    void cornerLanesWithTheEdgeClamps() {
        Art base = random(17, 0.65, 0.16);
        BlockSpec up = BlockSpec.of("minecraft:white_concrete");
        Art all = (col, row) -> {
            boolean edge = row >= 125 || row <= 2 || col == 0 || col == 127;
            if (edge && (col + row) % 3 == 0) return new Cell(up, 1);
            return base.at(col, row);
        };
        // the planner's lane 0 (columns 0-8, line on column 4) and lane 14 (columns 126-127, line on column 127)
        for (int[] l : new int[][] {{-60, 124, 0, 0, 8}, {63, 3, 124, 126, 127}}) {
            Art art = (col, row) -> col >= l[3] && col <= l[4] ? all.at(col, row) : null;
            Fixture before = new Fixture(17, false);
            Fixture after = new Fixture(17, true);
            for (Fixture f : new Fixture[] {before, after}) {
                f.sim.startTail = true;
                f.sim.standCells = c -> c.z() <= 61 && c.z() >= -63;
                f.sim.edgeCells = c -> c.z() == 61 || c.z() == -63;
                f.sim.edgeSpotsLast = true;
                f.sim.edgeSneak = true;
                f.sim.teleport(l[0] + 0.5, ART_Y, MIN + l[1] + 0.5);
                f.run(lane(f, art, l[0], l[1], l[2]));
            }
            String what = "corner lane at x " + l[0] + ", line " + l[1] + "->" + l[2];
            table(what, before, after, null);
            assertEquals(0, after.skipped, what + ": " + after.skips);
            assertEquals(0, after.missing(art, l[0]), what + ": " + after.stats());
            sane(after, what);
            for (Pos spot : after.sim.spotGoals) assertTrue(spot.z() <= 61 && spot.z() >= -63, "a stand spot in the edge rows: " + spot);
        }
    }

    /**
     * Case 7 (local 10x runs M and M-off, 2026-11-14): the box drifts 0.2-0.3 off the line, its edge into the column
     * beside, where a raised block stands. On top of the line's blocks (feet height) the collision's jump climbed it
     * (eight lane walks a print, 1-9 ticks on the raised top each; here 6 ticks, feet +2.25); on the floor (head height)
     * the walk pressed into it until the stuck wait (here at 0.3: 933 ticks, 17 stand spots; live 223 and 312 cells of
     * two lanes to stand spots). Now the walk steers back across before it goes on, and on top makes no such jump: no
     * tick on a raised top, nothing to stand spots, and about as fast as on the line.
     */
    @Test
    void offTheLineBesideARaisedBlock() {
        for (boolean onTop : new boolean[] {true, false}) {
            long onLine = -1;
            for (double lat : new double[] {0, 0.22, 0.25, 0.3}) {
                for (int every = 3; every <= 5; every++) {
                    String what = (onTop ? "on top of the line, " : "on the floor, ") + "off the line by " + lat
                        + ", a raised block every " + every + " rows";
                    Fixture f = new Fixture(3, true);
                    int cells = driftBesideRaised(f, onTop, lat, every, what);
                    System.out.println("raised cells, " + what + ": " + f.sim.lastTicks + " ticks, on a raised top "
                        + f.sim.raisedStands + " ticks, max feet +" + String.format(Locale.ROOT, "%.2f", f.sim.maxFeetY - ART_Y));
                    assertEquals(cells, f.placed, what);
                    assertEquals(0, f.sim.spotGoals.size(), "stand spots: " + what);
                    assertEquals(0, f.sim.raisedStands, "climbed a raised block: " + what);
                    assertTrue(f.sim.maxFeetY < ART_Y + 1.5, "climbed: " + what);
                    if (lat == 0) onLine = Math.max(onLine, f.sim.lastTicks);
                    else assertTrue(f.sim.lastTicks <= onLine + 20, what + ": " + f.sim.lastTicks + " ticks against " + onLine);
                }
            }
        }
    }

    /**
     * Case 7's lane: 41 rows south along x -60, cells in the outer columns only; raised blocks in the column beside the
     * line (x -59) from row 8 every {@code every} rows; with {@code onTop} the line holds full blocks from row 4 on (the
     * walk goes over their tops). Half a row before the first raised block the box is moved {@code lat} across towards
     * it. Runs the lane (asserts SUCCESS and the drift); the lane's cells.
     */
    private static int driftBesideRaised(Fixture f, boolean onTop, double lat, int every, String what) {
        BlockSpec low = BlockSpec.of("minecraft:white_concrete");
        BlockSpec up = BlockSpec.of("minecraft:black_concrete");
        if (onTop) for (int row = 4; row <= 40; row++) f.world.put(new Pos(-60, ART_Y, MIN + row), low);
        for (int row = 8; row <= 40; row += every) f.world.put(new Pos(-59, ART_Y + 1, MIN + row), up);
        List<Placement> ps = new ArrayList<>();
        for (int row = 0; row <= 40; row++)
            for (int x : new int[] {-64, -63, -62, -57, -56}) ps.add(new Placement(new Pos(x, ART_Y, MIN + row), low, low.id()));
        Lane lane = new Lane(0, new Pos(-60, ART_Y, MIN), new Pos(-60, ART_Y, MIN + 40), List.copyOf(ps));
        f.sim.teleport(-60 + 0.5, ART_Y, MIN + 0.5);
        boolean[] drifted = {false};
        double feet = ART_Y + (onTop ? 1 : 0) - 1e-6;
        TaskStatus s = f.sim.runLane(lane, LaneRun.Params.of(f.build, f.sim.tileArea()), new PlacementListener() {
            @Override public void placed(Placement p) { f.placed++; }
            @Override public void placedFromStandSpot(Placement p) { f.placed++; }
            @Override public void skipped(Placement p, String reason) { f.skipped++; }
        }, 20_000, (t, run) -> {
            if (!drifted[0] && f.sim.y >= feet && f.sim.onGround() && f.sim.z >= MIN + 7.55) {
                f.sim.x = -60 + 0.5 + lat;
                drifted[0] = true;
            }
            return true;
        });
        assertEquals(TaskStatus.SUCCESS, s, what);
        assertTrue(drifted[0], what);
        return ps.size();
    }

    /**
     * Case 8, open (local 10x runs M2 and M3, 2026-11-14, bottom layer): over a walled line the walk jumps across a dip
     * from one of the line's blocks to the next and rises above the raised tops (feet +2.25); drifting 0.2-0.3 across
     * on the way it lands on a raised block beside the dip or the block beyond, most of them placed by the walk ahead of
     * itself (M2: six lane walks a print, 2-9 ticks each; M3: one, 2 ticks). Here the raised cells beside each dip and
     * the block beyond are cells of the lane and the box is moved 0.25 across while above the raised tops: 744 ticks, 62
     * on a raised top, 10 stand spots. Two fixes made this case clean and were dropped for the whole layer
     * ({@code LevelMergeSimTest}, bottom layer 27,656 ticks): walking through such a dip instead of jumping it (246,157
     * ticks, 5 pocket ticks, a failed lane), and placing those raised cells only once the box is past them (393,704
     * ticks, two failed lanes).
     */
    @org.junit.jupiter.api.Disabled("open: the jump across a dip lands on a raised block beside it (docs/COLOR_SUPPRESSION.md)")
    @Test
    void aDipOfAWalledLineBesideARaisedBlock() {
        long plain = dipBesideRaised(new Fixture(3, true), 0, "no raised block beside");
        String what = "raised cells beside still to place";
        Fixture f = new Fixture(3, true);
        long ticks = dipBesideRaised(f, 2, what);
        System.out.println("raised cells, a walled line's dips, " + what + ": " + ticks + " ticks (" + plain
            + " without), on a raised top " + f.sim.raisedStands + " ticks, max feet +"
            + String.format(Locale.ROOT, "%.2f", f.sim.maxFeetY - ART_Y) + ", jumps " + f.sim.jumps);
        assertEquals(0, f.sim.spotGoals.size(), "stand spots: " + what);
        assertEquals(0, f.sim.raisedStands, "on a raised top: " + what);
        assertTrue(ticks <= plain * 1.25 + 40, what + ": " + ticks + " ticks against " + plain);
    }

    /**
     * Case 8's lane: 41 rows south along x -60, cells in the outer columns; the line holds full blocks from row 4 with a
     * dip every 4 rows from row 8; {@code mode} 1 puts a raised block beside each dip and the block beyond it (x -59) in
     * the world, 2 makes them cells of the lane, 0 leaves them out (and the box where the walk puts it). Runs the lane
     * (asserts SUCCESS and every cell placed); its ticks.
     */
    private static long dipBesideRaised(Fixture f, int mode, String what) {
        BlockSpec low = BlockSpec.of("minecraft:white_concrete");
        BlockSpec up = BlockSpec.of("minecraft:black_concrete");
        for (int row = 4; row <= 40; row++) if (row < 8 || row % 4 != 0) f.world.put(new Pos(-60, ART_Y, MIN + row), low);
        List<Placement> ps = new ArrayList<>();
        for (int row = 0; row <= 40; row++)
            for (int x : new int[] {-64, -63, -62, -57, -56}) ps.add(new Placement(new Pos(x, ART_Y, MIN + row), low, low.id()));
        for (int row = 8; row <= 36; row += 4)
            for (int r : new int[] {row, row + 1}) {
                Pos p = new Pos(-59, ART_Y + 1, MIN + r);
                if (mode == 1) f.world.put(p, up);
                if (mode == 2) ps.add(new Placement(p, up, up.id()));
            }
        Lane lane = new Lane(0, new Pos(-60, ART_Y, MIN), new Pos(-60, ART_Y, MIN + 40), List.copyOf(ps));
        f.sim.teleport(-60 + 0.5, ART_Y, MIN + 0.5);
        TaskStatus s = f.sim.runLane(lane, LaneRun.Params.of(f.build, f.sim.tileArea()), new PlacementListener() {
            @Override public void placed(Placement p) { f.placed++; }
            @Override public void placedFromStandSpot(Placement p) { f.placed++; }
            @Override public void skipped(Placement p, String reason) { f.skipped++; }
        }, 20_000, (t, run) -> {
            if (mode > 0 && !f.sim.onGround() && f.sim.y > ART_Y + 2) f.sim.x = -60 + 0.5 + 0.25;
            return true;
        });
        assertEquals(TaskStatus.SUCCESS, s, what);
        assertEquals(ps.size(), f.placed, what);
        return f.sim.lastTicks;
    }

    /** No raised block in the world: the sim's second level changes nothing, tick for tick. */
    @Test
    void theSecondLevelChangesNothingWithoutRaisedBlocks() {
        Art art = random(19, 0.8, 0);
        long[] ticks = new long[2];
        int[] clicks = new int[2];
        for (int i = 0; i < 2; i++) {
            Fixture f = new Fixture(19, false);
            f.sim.raisedLevel = i == 1;
            f.sim.teleport(-60 + 0.5, ART_Y, MIN + 0.5);
            f.run(lane(f, art, -60, 0, 127));
            ticks[i] = f.ticks;
            clicks[i] = f.sim.clicks;
        }
        assertEquals(ticks[0], ticks[1]);
        assertEquals(clicks[0], clicks[1]);
    }
}
