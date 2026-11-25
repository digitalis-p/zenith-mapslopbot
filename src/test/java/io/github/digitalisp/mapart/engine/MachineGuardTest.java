package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig.RegionCfg;
import io.github.digitalisp.mapart.model.Pos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flying-machine guard on a small made-up machine: box "bottom units" over chunks 0-1 and box "top units" over
 * chunks 1-2 (given with its corners swapped), overlapping in chunk 1, 266 guarded cells in all.
 */
class MachineGuardTest {
    static final Pos P_HONEY = new Pos(12, 65, 3);   // chunk 0, bottom units
    static final Pos P_AIR = new Pos(34, 66, 5);     // chunk 2, top units only
    static final Pos P_OVERLAP = new Pos(19, 65, 4); // chunk 1, in both boxes
    static final Pos P_BULB = new Pos(12, 64, 2);
    static final Pos P_COPPER = new Pos(33, 65, 6);
    static final List<Pos> SLIME = List.of(new Pos(13, 64, 4), new Pos(14, 64, 4), new Pos(25, 65, 5));
    static final List<Pos> AIR = List.of(new Pos(15, 64, 5), new Pos(30, 66, 3), new Pos(17, 66, 2));
    static final int CELLS = 132 + 152 - 18;

    static RegionCfg box(int ax, int ay, int az, int bx, int by, int bz, String name) {
        RegionCfg r = new RegionCfg();
        r.min = new Pos(ax, ay, az);
        r.max = new Pos(bx, by, bz);
        r.name = name;
        return r;
    }

    static List<RegionCfg> boxes() {
        return List.of(box(10, 64, 2, 20, 66, 5, "bottom units"), box(36, 66, 6, 18, 65, 3, "top units"));
    }

    static final class World implements MachineGuard.Blocks {
        final Map<Pos, String> blocks = new HashMap<>();
        final Set<Long> unloaded = new HashSet<>();
        int reads;
        /** Called before each read with the cell read. */
        java.util.function.Consumer<Pos> onRead = p -> { };

        @Override
        public boolean loaded(int x, int y, int z) {
            return !unloaded.contains(MachineGuard.chunkKey(x >> 4, z >> 4));
        }

        @Override
        public String id(int x, int y, int z) {
            reads++;
            Pos p = new Pos(x, y, z);
            onRead.accept(p);
            return blocks.getOrDefault(p, "minecraft:air");
        }

        World set(Pos p, String id) {
            blocks.put(p, id);
            return this;
        }

        World unload(int cx, int cz) {
            unloaded.add(MachineGuard.chunkKey(cx, cz));
            return this;
        }
    }

    /** The machine: a pattern of slime, honey, sticky pistons, observers and air, plus the named cells. */
    static World machine() {
        World w = new World();
        for (RegionCfg r : boxes())
            for (int y = Math.min(r.min.y(), r.max.y()); y <= Math.max(r.min.y(), r.max.y()); y++)
                for (int z = Math.min(r.min.z(), r.max.z()); z <= Math.max(r.min.z(), r.max.z()); z++)
                    for (int x = Math.min(r.min.x(), r.max.x()); x <= Math.max(r.min.x(), r.max.x()); x++) {
                        String id = switch (Math.floorMod(x + 2 * y + 3 * z, 5)) {
                            case 0 -> "minecraft:slime_block";
                            case 1 -> "minecraft:honey_block";
                            case 2 -> "minecraft:sticky_piston[facing=east,extended=false]";
                            case 3 -> "minecraft:observer[facing=up,powered=false]";
                            default -> null;
                        };
                        if (id != null) w.set(new Pos(x, y, z), id);
                    }
        w.set(P_HONEY, "minecraft:honey_block").set(P_OVERLAP, "minecraft:slime_block");
        w.set(P_BULB, "minecraft:weathered_copper_bulb[lit=false,powered=false]").set(P_COPPER, "minecraft:copper_block");
        for (Pos p : SLIME) w.set(p, "minecraft:slime_block");
        w.blocks.remove(P_AIR);
        for (Pos p : AIR) w.blocks.remove(p);
        return w;
    }

    static MachineGuard.Snapshot read(World w, List<RegionCfg> boxes, Predicate<Pos> excluded, int budget) {
        MachineGuard.Scan s = new MachineGuard.Scan(boxes, excluded);
        for (int i = 0; i < 1_000_000; i++) {
            int before = w.reads;
            boolean done = s.step(w, budget);
            assertTrue(w.reads - before <= budget, "a step read more than its budget");
            if (done) {
                assertTrue(s.done());
                return s.result();
            }
        }
        throw new AssertionError("scan never finished");
    }

    static MachineGuard.Snapshot read(World w) {
        return read(w, boxes(), null, 1000);
    }

    @Test
    void normaliseStripsNamespaceStatesWaxOxidationAndWeather() {
        assertEquals("anvil", MachineGuard.normalise("minecraft:chipped_anvil[facing=north]"));
        assertEquals("anvil", MachineGuard.normalise("damaged_anvil"));
        assertEquals("honey_block", MachineGuard.normalise("minecraft:honey_block"));
        assertEquals("sticky_piston", MachineGuard.normalise("minecraft:sticky_piston[facing=east,extended=true]"));
        assertEquals("slime_block", MachineGuard.normalise("Minecraft:Slime_Block"));
        assertEquals("cut_copper", MachineGuard.normalise("waxed_weathered_cut_copper"));
        assertEquals("cut_copper_stairs", MachineGuard.normalise("minecraft:oxidized_cut_copper_stairs[facing=north]"));
        assertEquals("lightning_rod", MachineGuard.normalise("minecraft:oxidized_lightning_rod[facing=up]"));
        assertEquals("copper_bulb", MachineGuard.normalise("weathered_copper_bulb"));
        assertEquals("copper_bulb", MachineGuard.normalise("minecraft:waxed_copper_bulb[lit=true]"));
        for (String id : List.of("copper_block", "exposed_copper", "weathered_copper", "oxidized_copper",
            "waxed_copper_block", "minecraft:waxed_oxidized_copper", "waxed_exposed_copper"))
            assertEquals("copper_block", MachineGuard.normalise(id), id);
        for (String id : List.of("minecraft:snow[layers=3]", "fire", "minecraft:soul_fire", "cave_air", "void_air",
            "minecraft:air"))
            assertEquals("air", MachineGuard.normalise(id), id);
        assertEquals("water", MachineGuard.normalise("minecraft:bubble_column[drag=true]"));
        assertEquals("water", MachineGuard.normalise("minecraft:water[level=0]"));
        assertEquals("snow_block", MachineGuard.normalise("minecraft:snow_block"));
        assertEquals("piston_head", MachineGuard.normalise("minecraft:piston_head[type=sticky,facing=up,short=false]"));
        assertEquals("moving_piston", MachineGuard.normalise("minecraft:moving_piston[type=normal,facing=down]"));
        assertEquals("air", MachineGuard.normalise(null));
    }

    @Test
    void referenceInSmallStepsMatchesAnIdenticalWorld() {
        World w = machine();
        MachineGuard.Scan scan = new MachineGuard.Scan(boxes(), null);
        assertEquals(3, scan.chunksTotal());
        int steps = 0;
        while (!scan.step(w, 7)) {
            steps++;
            assertFalse(scan.done());
            assertThrows(IllegalStateException.class, scan::result);
        }
        assertTrue(steps > 30, "steps " + steps);
        MachineGuard.Snapshot ref = scan.result();
        assertEquals(3, ref.chunks());
        assertEquals(CELLS, ref.cells());
        assertEquals(Set.of(MachineGuard.chunkKey(0, 0), MachineGuard.chunkKey(1, 0), MachineGuard.chunkKey(2, 0)),
            ref.chunkKeys());
        assertTrue(ref.has(1, 0));
        assertFalse(ref.has(3, 0));

        MachineGuard.Diff d = MachineGuard.compare(ref, read(machine()), boxes());
        assertTrue(d.clean());
        assertEquals(3, d.chunksCompared());
        assertEquals(0, d.chunksNotSeen());
        assertEquals(0, d.chunksDiffer());
        assertTrue(d.kinds().isEmpty());
        assertTrue(d.sample().isEmpty());
        assertEquals("no cells differ in 3 chunks", d.summary());
    }

    @Test
    void twoMovedBlocksAreFoundWithTheirPositionsKindsAndBoxes() {
        MachineGuard.Snapshot ref = read(machine(), boxes(), null, 7);
        World now = machine();
        now.blocks.remove(P_HONEY);
        now.set(P_AIR, "minecraft:honey_block");
        MachineGuard.Diff d = MachineGuard.compare(ref, read(now), boxes());
        assertFalse(d.clean());
        assertEquals(2, d.cellsDiffer());
        assertEquals(2, d.chunksDiffer());
        assertEquals(3, d.chunksCompared());
        assertEquals(Map.of("honey_block -> air", 1, "air -> honey_block", 1), d.kinds());
        assertEquals(List.of(new MachineGuard.Change(P_HONEY, "honey_block", "air"),
            new MachineGuard.Change(P_AIR, "air", "honey_block")), d.sample());
        assertEquals(Map.of("bottom units", 1, "top units", 1), d.perBox());

        List<RegionCfg> unnamed = List.of(boxes().get(0), box(36, 66, 6, 18, 65, 3, null));
        assertEquals(Map.of("bottom units", 1, "box 2", 1), MachineGuard.compare(ref, read(now), unnamed).perBox());
    }

    @Test
    void aCellInTwoBoxesCountsOnceForTheEarlierBox() {
        MachineGuard.Snapshot ref = read(machine());
        World now = machine();
        now.blocks.remove(P_OVERLAP);
        MachineGuard.Diff d = MachineGuard.compare(ref, read(now), boxes());
        assertEquals(1, d.cellsDiffer());
        assertEquals(Map.of("bottom units", 1), d.perBox());
        assertEquals(List.of(new MachineGuard.Change(P_OVERLAP, "slime_block", "air")), d.sample());
    }

    @Test
    void oxidationWaxSnowAndFireAreNotDamage() {
        MachineGuard.Snapshot ref = read(machine());
        World now = machine();
        now.set(P_BULB, "minecraft:oxidized_copper_bulb[lit=true,powered=true]");
        now.set(P_COPPER, "minecraft:waxed_exposed_copper");
        now.set(AIR.get(0), "minecraft:snow[layers=1]");
        now.set(AIR.get(1), "minecraft:fire[age=3,east=false]");
        now.set(AIR.get(2), "minecraft:cave_air");
        now.set(SLIME.get(0), "slime_block");
        MachineGuard.Diff d = MachineGuard.compare(ref, read(now), boxes());
        assertTrue(d.clean(), d.summary());
        assertEquals(3, d.chunksCompared());
    }

    @Test
    void aChunkNotLoadedAtTheReadingIsNotSeen() {
        MachineGuard.Snapshot ref = read(machine());
        MachineGuard.Snapshot now = read(machine().unload(2, 0));
        assertEquals(2, now.chunks());
        assertFalse(now.has(2, 0));
        MachineGuard.Diff d = MachineGuard.compare(ref, now, boxes());
        assertTrue(d.clean());
        assertEquals(1, d.chunksNotSeen());
        assertEquals(2, d.chunksCompared());
        assertTrue(d.summary().contains("1 chunk not seen"), d.summary());
    }

    @Test
    void aChunkUnloadedMidReadIsDropped() {
        World w = machine();
        int[] readsInChunk1 = {0};
        w.onRead = p -> {
            if (p.x() >> 4 == 1 && ++readsInChunk1[0] == 5) w.unload(1, 0);
        };
        MachineGuard.Snapshot s = read(w, boxes(), null, 3);
        assertEquals(2, s.chunks());
        assertTrue(s.has(0, 0));
        assertFalse(s.has(1, 0));
        assertTrue(s.has(2, 0));
    }

    @Test
    void twoViewpointsMergeIntoAllChunks() {
        MachineGuard.Snapshot ref = read(machine());
        MachineGuard.Snapshot a = read(machine().unload(2, 0));
        MachineGuard.Snapshot b = read(machine().unload(0, 0));
        assertEquals(1, MachineGuard.merge(a, b));
        assertEquals(3, a.chunks());
        assertEquals(CELLS, a.cells());
        assertEquals(0, MachineGuard.merge(a, b));
        MachineGuard.Diff d = MachineGuard.compare(ref, a, boxes());
        assertTrue(d.clean(), d.summary());
        assertEquals(3, d.chunksCompared());
        assertEquals(0, d.chunksNotSeen());

        // a change only the second viewpoint saw, with a block id the first one's palette lacks
        MachineGuard.Snapshot a2 = read(machine().unload(2, 0));
        MachineGuard.Snapshot b2 = read(machine().unload(0, 0).set(P_AIR, "minecraft:piston_head[type=sticky,facing=up]"));
        MachineGuard.merge(a2, b2);
        MachineGuard.Diff d2 = MachineGuard.compare(ref, a2, boxes());
        assertEquals(1, d2.cellsDiffer());
        assertEquals(Map.of("air -> piston_head", 1), d2.kinds());
        assertEquals(P_AIR, d2.sample().get(0).pos());
    }

    @Test
    void excludedCellsAreIgnored() {
        Predicate<Pos> ex = p -> p.y() == 66 && p.x() >= 10 && p.x() <= 12;
        MachineGuard.Snapshot ref = read(machine(), boxes(), ex, 7);
        assertEquals(CELLS - 12, ref.cells());
        World now = machine().set(new Pos(11, 66, 3), "minecraft:diamond_block").set(new Pos(10, 66, 5), "minecraft:stone");
        assertTrue(MachineGuard.compare(ref, read(now, boxes(), ex, 50), boxes()).clean());
        // excluded in only one of the two snapshots: still skipped, the positions still line up
        assertTrue(MachineGuard.compare(ref, read(now), boxes()).clean());
        assertTrue(MachineGuard.compare(read(machine()), read(now, boxes(), ex, 50), boxes()).clean());
        now.blocks.remove(P_HONEY);
        MachineGuard.Diff d = MachineGuard.compare(ref, read(now, boxes(), ex, 50), boxes());
        assertEquals(1, d.cellsDiffer());
        assertEquals(P_HONEY, d.sample().get(0).pos());
    }

    @Test
    void writeAndReadRoundTrip() throws IOException {
        Predicate<Pos> ex = p -> p.y() == 66 && p.x() == 10;
        MachineGuard.Snapshot ref = read(machine(), boxes(), ex, 7);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MachineGuard.write(ref, out);
        MachineGuard.Snapshot back = MachineGuard.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(ref.chunks(), back.chunks());
        assertEquals(ref.cells(), back.cells());
        assertEquals(ref.chunkKeys(), back.chunkKeys());
        assertEquals(ref.layout(), back.layout());
        assertEquals(3, MachineGuard.compare(back, ref, boxes()).chunksCompared());
        assertTrue(MachineGuard.compare(back, ref, boxes()).clean());
        assertTrue(MachineGuard.compare(ref, back, boxes()).clean());
        World now = machine();
        now.blocks.remove(P_HONEY);
        now.set(P_AIR, "minecraft:slime_block");
        MachineGuard.Snapshot reading = read(now);
        assertEquals(MachineGuard.compare(ref, reading, boxes()), MachineGuard.compare(back, reading, boxes()));

        ByteArrayOutputStream again = new ByteArrayOutputStream();
        MachineGuard.write(back, again);
        assertTrue(Arrays.equals(out.toByteArray(), again.toByteArray()));
    }

    @Test
    void readRejectsAnotherVersionAndGarbage() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(new GZIPOutputStream(bytes))) {
            d.writeInt(0x4d475244);
            d.writeInt(MachineGuard.FORMAT_VERSION + 1);
            d.writeLong(0);
            d.writeInt(0);
            d.writeInt(0);
        }
        IOException e = assertThrows(IOException.class, () -> MachineGuard.read(new ByteArrayInputStream(bytes.toByteArray())));
        assertTrue(e.getMessage().contains("format " + (MachineGuard.FORMAT_VERSION + 1)), e.getMessage());

        assertThrows(IOException.class, () ->
            MachineGuard.read(new ByteArrayInputStream("not a snapshot".getBytes(StandardCharsets.UTF_8))));
        ByteArrayOutputStream good = new ByteArrayOutputStream();
        MachineGuard.write(read(machine()), good);
        byte[] cut = Arrays.copyOf(good.toByteArray(), good.size() / 2);
        assertThrows(IOException.class, () -> MachineGuard.read(new ByteArrayInputStream(cut)));
    }

    @Test
    void otherBoxesAreRefused() {
        MachineGuard.Snapshot ref = read(machine());
        List<RegionCfg> moved = List.of(box(10, 64, 2, 20, 67, 5, "bottom units"), boxes().get(1));
        MachineGuard.Snapshot other = read(machine(), moved, null, 1000);
        assertThrows(IllegalArgumentException.class, () -> MachineGuard.compare(ref, read(machine()), moved));
        assertThrows(IllegalArgumentException.class, () -> MachineGuard.compare(ref, other, boxes()));
        assertThrows(IllegalArgumentException.class, () -> MachineGuard.merge(ref, other));
        assertEquals(MachineGuard.layoutOf(boxes()), ref.layout());
    }

    @Test
    void negativeCoordinatesSpanFourChunks() {
        List<RegionCfg> bx = List.of(box(-5, 10, -20, 5, 11, -1, "west"));
        World w = new World();
        Pos p = new Pos(-3, 10, -17);
        w.set(p, "minecraft:observer");
        MachineGuard.Scan s = new MachineGuard.Scan(bx, null);
        assertEquals(4, s.chunksTotal());
        MachineGuard.Snapshot ref = read(w, bx, null, 5);
        assertEquals(4, ref.chunks());
        assertEquals(11 * 2 * 20, ref.cells());
        assertTrue(ref.has(-1, -2));
        w.blocks.remove(p);
        MachineGuard.Diff d = MachineGuard.compare(ref, read(w, bx, null, 5), bx);
        assertEquals(List.of(new MachineGuard.Change(p, "observer", "air")), d.sample());
        assertEquals(Map.of("west", 1), d.perBox());
    }

    @Test
    void largeBoxesReadInBudgetSizedSteps() {
        List<RegionCfg> bx = List.of(box(0, 100, 0, 127, 107, 127, "big"));
        World w = new World();
        MachineGuard.Scan s = new MachineGuard.Scan(bx, null);
        int calls = 0;
        while (true) {
            int before = w.reads;
            boolean done = s.step(w, 20_000);
            calls++;
            assertTrue(w.reads - before <= 20_000);
            if (done) break;
            assertTrue(w.reads - before >= 19_900, "a step read only " + (w.reads - before));
        }
        assertTrue(calls <= 8, "calls " + calls);
        assertEquals(128 * 8 * 128, s.result().cells());
        assertEquals(64, s.result().chunks());
    }

    @Test
    void summaryListsTheMostFrequentKindFirst() {
        MachineGuard.Snapshot ref = read(machine());
        World now = machine();
        for (Pos p : SLIME) now.blocks.remove(p);
        now.blocks.remove(P_HONEY);
        MachineGuard.Diff d = MachineGuard.compare(ref, read(now), boxes());
        String s = d.summary();
        assertTrue(s.startsWith("4 cells differ in 2 of 3 chunks: slime_block -> air 3, honey_block -> air 1"), s);
        assertTrue(s.contains("(box bottom units 3, box top units 1)"), s);
        assertTrue(s.endsWith("; e.g. 13,64,4 slime_block -> air"), s); // y before z before x: the first in scan order

        Map<String, Integer> kinds = new LinkedHashMap<>();
        kinds.put("slime_block -> air", 353);
        kinds.put("honey_block -> air", 320);
        kinds.put("observer -> air", 300);
        kinds.put("sticky_piston -> air", 200);
        kinds.put("slime_block -> honey_block", 178);
        MachineGuard.Diff big = new MachineGuard.Diff(40, 0, 1351, kinds, Map.of("bottom units", 1351),
            List.of(new MachineGuard.Change(new Pos(12, 203, 40), "slime_block", "air")), 12);
        assertEquals("1,351 cells differ in 12 of 40 chunks: slime_block -> air 353, honey_block -> air 320, "
            + "observer -> air 300, sticky_piston -> air 200, ... (box bottom units 1,351); e.g. 12,203,40 slime_block -> air",
            big.summary());

        Map<String, Integer> longKinds = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) longKinds.put("x".repeat(150) + i + " -> air", 10 - i);
        MachineGuard.Diff huge = new MachineGuard.Diff(40, 3, 55, longKinds, Map.of("y".repeat(200), 55), List.of());
        assertTrue(huge.summary().length() <= 400, huge.summary().length() + "");
        assertTrue(huge.summary().startsWith("55 cells differ in 40 chunks: "), huge.summary());
    }

    /** The pre-ride guard waits only while the difference changes: two readings are compared by their signature. */
    @Test
    void theSignatureTellsTheSameDifferenceFromAnotherOne() {
        MachineGuard.Snapshot ref = read(machine());
        MachineGuard.Diff none = MachineGuard.compare(ref, read(machine()), boxes());
        assertTrue(none.chunks().isEmpty());

        World moved = machine();
        moved.blocks.remove(P_HONEY);
        MachineGuard.Diff a = MachineGuard.compare(ref, read(moved), boxes());
        MachineGuard.Diff again = MachineGuard.compare(ref, read(moved, boxes(), null, 7), boxes());
        assertEquals(a.signature(), again.signature(), "the same cells, the same blocks");
        assertNotEquals(none.signature(), a.signature());
        assertEquals(Set.of(MachineGuard.chunkKey(P_HONEY.x() >> 4, P_HONEY.z() >> 4)), a.chunks());

        // the same cell holding another block; another cell holding the same block; one more cell
        World pane = machine();
        pane.set(P_HONEY, "minecraft:white_stained_glass_pane");
        World elsewhere = machine();
        elsewhere.blocks.remove(SLIME.get(0));
        World more = machine();
        more.blocks.remove(P_HONEY);
        more.set(P_AIR, "minecraft:honey_block");
        Set<Long> seen = new HashSet<>(List.of(a.signature()));
        for (World w : List.of(pane, elsewhere, more))
            assertTrue(seen.add(MachineGuard.compare(ref, read(w), boxes()).signature()), "a different difference");
        assertEquals(2, MachineGuard.compare(ref, read(more), boxes()).chunks().size());

        // a reference chunk not in view is part of the difference
        World gone = machine();
        gone.unloaded.add(MachineGuard.chunkKey(P_AIR.x() >> 4, P_AIR.z() >> 4));
        MachineGuard.Diff unseen = MachineGuard.compare(ref, read(gone), boxes());
        assertEquals(1, unseen.chunksNotSeen());
        assertEquals(Set.of(MachineGuard.chunkKey(P_AIR.x() >> 4, P_AIR.z() >> 4)), unseen.chunks());
        assertNotEquals(none.signature(), unseen.signature());
    }
}
