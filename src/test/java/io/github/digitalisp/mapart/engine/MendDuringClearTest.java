package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mending during the clear (the part the presence rule allows):
 * the tools are mended in WAIT_CLEAR, standing at the centre while the machines sweep, instead of at a lane end in the
 * NUKE. Phase timing as live: the clear takes 70 s (1,400 ticks), a bottle 8 ticks, a block 5 ticks (4 blocks/s), a
 * use every 4 breaks (Unbreaking III), a lane end every 600 blocks, the carts' run-on the fixed 30 s (no item count in
 * the Sim). The tail is the time from the map stored (STORE ends) to the next map's first BUILD.
 */
class MendDuringClearTest {
    private static final int LONG = 600_000;
    private static final String PICK = "minecraft:netherite_pickaxe";
    private static final String XP = "minecraft:experience_bottle";
    /** 50 % of 2,031: due for mending at or below this. 95 %: 1,930. */
    private static final int DUE = 1015;
    private static final int TARGET = 1930;
    private static final int BOTTLE_TICKS = 8;

    /** 10 rows of carpet (white/red/black) and 20 rows of terracotta: 2,560 full blocks for the NUKE. */
    private static TileArt mixed() {
        return Sim.stripes(10, 20);
    }

    private static Sim sim(RecordingLogger log, boolean mendDuringClear, int pickUses) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        for (int i = 0; i < 4; i++) sim.bot.addContainer(new Pos(-90 + 2 * i, 61, 106), Map.of(Sim.TERRACOTTA, 1728));
        sim.bot.containers.get(Sim.XP).items.put(XP, 3000);
        sim.inv.items.remove("minecraft:diamond_pickaxe");
        sim.inv.toolStacks.clear();
        sim.inv.addTool(PICK, pickUses, true);
        sim.bot.breaksPerUse = 4;
        sim.bot.laneBlocks = 600;
        sim.config.build.mendDuringClear = mendDuringClear;
        // as live: the bot presses the clear from the centre and waits there (moss bridge), the clear takes 70 s
        sim.moss.stayDuringClear = true;
        sim.moss.clear.getFirst().standAt = sim.moss.center;
        sim.moss.clearTimeoutSeconds = 180;
        sim.bot.clearMachine(sim.moss, Sim.MOSS_BUTTON, 1400, () -> true, () -> { });
        Runnable press = sim.bot.useHandlers.get(Sim.MOSS_BUTTON);
        sim.bot.useHandlers.put(Sim.MOSS_BUTTON, () -> {
            press.run();
            sim.setCarts(true);
        });
        sim.bot.mendTicksPerBottle = BOTTLE_TICKS;
        sim.bot.breakTicksPerBlock = 5;
        return sim;
    }

    private static String tail(RecordingLogger log) {
        List<String> l = log.lines;
        return String.join("\n", l.subList(Math.max(0, l.size() - 60), l.size()));
    }

    /** Ticks per phase from one map stored to the next map's first BUILD (or to SELECT/IDLE after the last map). */
    private static final class Tail {
        final Map<Phase, Integer> ticks = new EnumMap<>(Phase.class);
        int total;

        int of(Phase p) {
            return ticks.getOrDefault(p, 0);
        }

        @Override
        public String toString() {
            return total + " ticks " + ticks;
        }
    }

    /** Watches every tick (call {@link #observe()} from the run condition) and records each tail. */
    private static final class Recorder {
        final Sim sim;
        final List<Tail> tails = new ArrayList<>();
        Phase last;
        Tail cur;

        Recorder(Sim sim) {
            this.sim = sim;
        }

        boolean observe() {
            Phase now = sim.phase();
            if (last == Phase.STORE && now != Phase.STORE) {
                cur = new Tail();
                tails.add(cur);
            }
            if (cur != null) {
                if (now == Phase.BUILD) {
                    cur = null;
                } else {
                    cur.ticks.merge(now, 1, Integer::sum);
                    cur.total++;
                }
            }
            last = now;
            return true;
        }
    }

    private static Recorder runJob(Sim sim, RecordingLogger log, int maps) {
        Recorder rec = new Recorder(sim);
        Job job = sim.addJob("j0601", "Mixed", maps, 1, List.of("moss"), (x, z) -> mixed());
        sim.start();
        assertTrue(sim.runUntil(() -> rec.observe() && job.status == JobStatus.DONE, LONG), sim.state() + "\n" + tail(log));
        assertTrue(sim.runUntil(() -> rec.observe() && sim.phase() == Phase.IDLE, 5_000), sim.state());
        return rec;
    }

    private static long mendsIn(Sim sim, Phase p) {
        return sim.bot.mendPhases.stream().filter(x -> x == p).count();
    }

    /**
     * The pickaxe at 54 % goes below 50 % 340 blocks into the 2,560-block NUKE. Off: the NUKE stops at the next lane end
     * and throws the STORE bottles there. On: the measured wear says it finishes the NUKE far above the floor, so the
     * mend waits and is thrown at the centre while the clear runs; the tail is shorter by the throws and the pause, and
     * the clear's wait is not longer. The next map's NUKE starts with the pickaxe at its target.
     */
    @Test
    void theDueMendWaitsForTheClearAndTheTailGetsShorter() {
        RecordingLogger offLog = new RecordingLogger();
        Sim off = sim(offLog, false, 1100);
        Recorder offRec = runJob(off, offLog, 2);
        RecordingLogger onLog = new RecordingLogger();
        Sim on = sim(onLog, true, 1100);
        Recorder onRec = runJob(on, onLog, 2);

        Tail a = offRec.tails.getFirst();
        Tail b = onRec.tails.getFirst();
        System.out.println("tail map 1 -> map 2, off: " + a + "; mends " + off.bot.mendPhases + ", paused at "
            + off.bot.pausedAtUses + ", bottles " + off.bot.mendBottles);
        System.out.println("tail map 1 -> map 2, on:  " + b + "; mends " + on.bot.mendPhases + ", paused at "
            + on.bot.pausedAtUses + ", bottles " + on.bot.mendBottles);
        System.out.println("map 2's own tail, off: " + offRec.tails.get(1) + "; on: " + onRec.tails.get(1));
        onLog.at(Level.INFO).stream().filter(l -> l.contains("waits for the clear") || l.contains("during the clear")
            || l.contains("XP for the nuke") || l.startsWith("mended ")).forEach(l -> System.out.println("  " + l));

        // off: the mend in the NUKE, as before
        assertTrue(mendsIn(off, Phase.NUKE) >= 1, off.bot.mendPhases.toString());
        assertEquals(0, mendsIn(off, Phase.WAIT_CLEAR), off.bot.mendPhases.toString());
        // on: no pause and no mend in either NUKE; every mend in WAIT_CLEAR
        assertEquals(0, mendsIn(on, Phase.NUKE), on.bot.mendPhases.toString() + "\n" + tail(onLog));
        assertTrue(on.bot.pausedAtUses.isEmpty(), on.bot.pausedAtUses.toString());
        assertTrue(mendsIn(on, Phase.WAIT_CLEAR) >= 1, on.bot.mendPhases.toString());
        assertTrue(onLog.at(Level.INFO).stream().anyMatch(l -> l.startsWith("netherite_pickaxe at ")
            && l.contains(": its mend waits for the clear: ")), tail(onLog));
        // never at the floor, and at the target once the clear is over
        assertEquals(0, on.bot.breaksAtFloor);
        assertEquals(0, on.bot.toolStops);
        assertTrue(on.bot.lowestToolUses > DUE - 700, "lowest " + on.bot.lowestToolUses);
        assertTrue(on.inv.tool(PICK).remaining >= TARGET, on.inv.tool(PICK).toString());
        // the tail: the NUKE without the throws and the pause, the clear's wait no longer than one run-on mend
        assertTrue(b.of(Phase.NUKE) < a.of(Phase.NUKE), a + " / " + b);
        assertTrue(b.of(Phase.WAIT_CLEAR) <= a.of(Phase.WAIT_CLEAR)
            + EngineTuning.CLEAR_RUNON_MEND_CHUNK * BOTTLE_TICKS + EngineTuning.POLL_TICKS, a + " / " + b);
        assertTrue(a.total - b.total >= 300, "at least the 46 STORE bottles' throws saved: " + a + " / " + b);
        assertTrue(on.notifier.of("alert").isEmpty(), on.notifier.toString());
    }

    /**
     * A whole map of full blocks (9,879, like ellen) wears the pickaxe by about 2,470 uses, more than it has: the first
     * due mend cannot wait (the bound says it would not finish above the floor) and is thrown at a lane end; the next one
     * waits for the clear. Never at the floor.
     */
    @Test
    void aNukeTooBigForTheToolStillMendsInTheNukeFirst() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true, 1929);
        sim.bot.breakTicksPerBlock = 0; // fast: this one is about the decisions, not the timing
        for (int i = 4; i < 7; i++) sim.bot.addContainer(new Pos(-90 + 2 * i, 61, 106), Map.of(Sim.TERRACOTTA, 1728));
        // 9,879 terracotta cells from the north-west corner, then two rows of carpet: the piston clear still runs
        TileArt ellen = new TileArt();
        for (int i = 0; i < 9_879; i++) ellen.set(i % 128, i / 128, io.github.digitalisp.mapart.model.BlockSpec.of(Sim.TERRACOTTA));
        for (int i = 9_879; i < 9_879 + 256; i++) ellen.set(i % 128, i / 128, io.github.digitalisp.mapart.model.BlockSpec.of(Sim.WHITE));
        Job job = sim.addJob("j0602", "Ellen", 1, 1, List.of("moss"), (x, z) -> ellen);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + "\n" + tail(log));
        System.out.println("ellen with mending during the clear: paused at " + sim.bot.pausedAtUses + ", mends in "
            + sim.bot.mendPhases + ", lowest " + sim.bot.lowestToolUses + ", ends at " + sim.inv.tool(PICK));
        log.lines.stream().filter(l -> l.contains("XP") || l.contains("mend") || l.contains("phase ")
            || l.contains("paused")).forEach(l -> System.out.println("  " + l));
        assertEquals(0, sim.bot.breaksAtFloor);
        assertEquals(0, sim.bot.toolStops, "never stopped at the floor");
        assertTrue(mendsIn(sim, Phase.NUKE) >= 1, "the first due mend in the NUKE: " + sim.bot.mendPhases);
        assertTrue(mendsIn(sim, Phase.WAIT_CLEAR) >= 1, "the rest during the clear: " + sim.bot.mendPhases);
        assertTrue(sim.bot.pausedAtUses.getFirst() <= DUE, sim.bot.pausedAtUses.toString());
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.contains("waits for the clear")), tail(log));
        assertTrue(sim.inv.tool(PICK).remaining >= TARGET, sim.inv.tool(PICK).toString());
    }

    /**
     * No bottles carried (the XP chest empty at STORE, refilled later): the due mend still waits while the pickaxe
     * finishes the NUKE well above the floor, so the NUKE makes no walk to the XP chest; the bottles are fetched and
     * thrown after the clear, before the next build.
     */
    @Test
    void withoutCarriedBottlesTheMendWaitsUntilAfterTheClear() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true, 1100);
        sim.config.map.rename = false; // the anvil would want the XP the chest does not have
        int stocked = sim.bot.containers.get(Sim.XP).items.remove(XP);
        Job job = sim.addJob("j0603", "Mixed", 1, 1, List.of("moss"), (x, z) -> mixed());
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.NUKE, LONG), sim.state() + "\n" + tail(log));
        assertEquals(0, sim.inv.count(XP));
        sim.bot.containers.get(Sim.XP).items.put(XP, stocked); // a human refills the XP chest
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + "\n" + tail(log));
        assertEquals(0, mendsIn(sim, Phase.NUKE), sim.bot.mendPhases.toString());
        assertEquals(0, mendsIn(sim, Phase.WAIT_CLEAR), "nothing to throw there: " + sim.bot.mendPhases);
        assertTrue(sim.bot.withdrawLog.stream().noneMatch(e -> e.getKey().equals(Sim.XP) && e.getValue() == Phase.NUKE),
            "no walk to the XP chest in the NUKE: " + sim.bot.withdrawLog);
        assertTrue(sim.bot.mends >= 1, "mended after the clear: " + sim.bot.mendPhases);
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.contains("its mend waits for the clear (no XP bottles"
            + " carried: fetched after it)")), tail(log));
        assertEquals(0, sim.bot.breaksAtFloor);
        assertTrue(sim.inv.tool(PICK).remaining > DUE, sim.inv.tool(PICK).toString());
    }

    /** Off: WAIT_CLEAR stays as it was, nothing thrown there even with bottles and a worn tool. */
    @Test
    void offLeavesTheClearWaitAlone() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, false, 1100);
        sim.inv.add(XP, 64);
        Recorder rec = runJob(sim, log, 1);
        assertEquals(0, mendsIn(sim, Phase.WAIT_CLEAR), sim.bot.mendPhases.toString());
        assertFalse(log.at(Level.INFO).stream().anyMatch(l -> l.contains("during the clear")), tail(log));
        assertEquals(1, rec.tails.size());
    }

    /**
     * The server's simulation distance is logged when known and on every change, and WAIT_CLEAR logs the presence rule
     * from where the bot waits: the Sim's moss centre is 5 chunks from the farthest sweep chunk (as on the moss bridge).
     */
    @Test
    void thePresenceRuleIsLoggedAgainstTheSimulationDistance() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true, 1900);
        sim.world.simulationDistance = 5;
        Job job = sim.addJob("j0604", "Mixed", 1, 1, List.of("moss"), (x, z) -> mixed());
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        sim.world.simulationDistance = 4; // the server lowers it (set_simulation_distance)
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + "\n" + tail(log));
        List<String> info = log.at(Level.INFO);
        assertEquals(1, info.stream().filter(l -> l.equals("server simulation distance 5 chunks (view distance 10)")).count(),
            tail(log));
        assertEquals(1, info.stream().filter(l -> l.equals("server simulation distance 4 chunks (view distance 10), was 5"))
            .count(), tail(log));
        assertTrue(log.at(Level.WARN).stream().anyMatch(l -> l.startsWith("clear on moss: the farthest chunk of the sweep"
            + " is 5 chunks from where the bot waits; server simulation distance 4 chunks (view 10): the flying machines")),
            tail(log));
    }
}
