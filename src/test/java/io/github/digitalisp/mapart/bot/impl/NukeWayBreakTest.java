package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.bot.impl.DigServer.BlockType;

/**
 * The sweep breaks clearable blocks in its way in the NUKE ({@code build.nukeSweepBreaksWay}). Live 2026-10-15, Galileo
 * [0,0]: 35 % terracotta targets with decorated pots (3 % of the art) among them; the sweep walked around the pots until
 * it gave its lanes up (26 of 5,794 broken on the way, 1,301 walks to a target standing, 17 minutes).
 */
class NukeWayBreakTest {
    static final int Y = NukeRunTest.Y;
    static final BlockType POT = new BlockType("minecraft:decorated_pot", 0f, null, false);
    static final String POT_ID = POT.id();

    /** A quarter tile: 35 % gray terracotta (targets), 3 % pots (no targets, not walkable), the rest open floor. */
    private static Map<Pos, BlockType>[] galileoLike(long seed) {
        Random r = new Random(seed);
        Map<Pos, BlockType> targets = new LinkedHashMap<>();
        Map<Pos, BlockType> pots = new LinkedHashMap<>();
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                if (x < 2 && z < 2) continue; // the start
                double v = r.nextDouble();
                if (v < 0.35) targets.put(new Pos(x, Y, z), NukeRunTest.GRAY_TERRACOTTA);
                else if (v < 0.38) pots.put(new Pos(x, Y, z), POT);
            }
        }
        @SuppressWarnings("unchecked")
        Map<Pos, BlockType>[] out = new Map[]{targets, pots};
        return out;
    }

    private static NukeRunTest.Result run(boolean wayBreak, boolean artCleared) {
        Map<Pos, BlockType>[] art = galileoLike(7);
        NukeRunTest.Scenario sc = new NukeRunTest.Scenario("galileo-like, way break " + wayBreak + ", art cleared "
            + artCleared);
        sc.perTick = 1;
        sc.blocks = art[0];
        sc.others = art[1];
        sc.wayBreak = wayBreak;
        sc.artCleared = artCleared;
        sc.maxTicks = 120_000;
        sc.server = s -> {
            s.uplink = 3;
            s.downlink = 3;
            s.jitter = 2;
        };
        sc.client = c -> {
            c.wallIds = Set.of(POT_ID);
            c.clearableIds = Set.of(POT_ID);
            c.pathing = true;
            c.elevation = true;
        };
        NukeRunTest.Result r = sc.run();
        System.out.println(r.line());
        for (String l : r.sim().log) if (l.startsWith("Break round") || l.startsWith("Fast nuke: sweep")) System.out.println("    " + l);
        return r;
    }

    private static long potsBroken(NukeRunTest.Result r) {
        return r.server().destroys.stream().filter(d -> d.block().id().equals(POT_ID)).count();
    }

    @Test
    void theSweepBreaksPotsInItsWayAndKeepsItsLanes() {
        NukeRunTest.Result before = run(false, true);
        NukeRunTest.Result after = run(true, true);
        for (NukeRunTest.Result r : new NukeRunTest.Result[]{before, after}) {
            assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
            assertEquals(r.targets().size(), r.run().done());
            r.assertRules(1);
            assertEquals(r.targets().size(), r.run().swept(), r.run().summaryLine("x"));
        }
        assertEquals(0, potsBroken(before));
        assertTrue(potsBroken(after) > 0, after.line());
        assertEquals(potsBroken(after), after.run().wayBroken());
        // a pot broken on the way is never reported as a broken target
        for (Pos p : after.sim().brokenCalls.keySet()) assertTrue(after.targets().contains(p), "reported " + p);
        // no sidesteps around the pots: a shorter round (sim: 2,891 -> 2,492 ticks)
        assertTrue(after.sim().ticks < before.sim().ticks * 0.95, before.sim().ticks + " -> " + after.sim().ticks);
    }

    @Test
    void soulSandIsFloorForTheNukeWalk() {
        // live 2026-10-15: the walkability test asked for a floor top of 0.9; soul sand is 0.875
        for (String id : new String[]{"minecraft:soul_sand", "minecraft:mud", "minecraft:soul_soil", "minecraft:moss_block",
            "minecraft:stone", "minecraft:farmland", "minecraft:glass"}) {
            assertTrue(BreakBlocksTask.standsOn(io.github.digitalisp.mapart.model.BlockSpec.parse(id)), id);
        }
        for (String id : new String[]{"minecraft:air", "minecraft:white_carpet", "minecraft:stone_slab[type=bottom]",
            "minecraft:stone_pressure_plate", "minecraft:snow[layers=1]"}) {
            assertFalse(BreakBlocksTask.standsOn(io.github.digitalisp.mapart.model.BlockSpec.parse(id)), id);
        }
    }

    @Test
    void artThatStaysIsNeverBrokenInTheWay() {
        // VERIFY's breaks (the art is not cleared afterwards): pots are art, walked around as before
        NukeRunTest.Result r = run(true, false);
        assertEquals(0, potsBroken(r), r.line());
        assertEquals(0, r.run().wayBroken());
    }
}
