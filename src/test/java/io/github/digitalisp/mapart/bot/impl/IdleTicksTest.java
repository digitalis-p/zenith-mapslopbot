package io.github.digitalisp.mapart.bot.impl;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.LaneSim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Standing prefetch with delayed/stale reports, bounded uncertainty and tick/sub-tick click windows. */
class IdleTicksTest {
    private static final String[] COLOURS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
        "gray", "light_gray", "cyan", "purple", "blue"};
    private static final Pattern IDLE = Pattern.compile("walk ticks (\\d+), (\\d+) without a click");
    private static final PlacementListener NONE = new PlacementListener() {
        @Override public void placed(Placement p) { }
        @Override public void skipped(Placement p, String reason) { }
    };
    private record Result(LaneSim sim, int walkTicks, int idle) {
        double clicksPerTick() { return (walkTicks - idle) / (double) walkTicks; }
    }

    private static Result run(boolean fenceClicks, boolean prefetch, int interval, int stack, int colours,
                              boolean paced, boolean stackSwitch) {
        return run(fenceClicks, prefetch, interval, stack, colours, paced, stackSwitch, false, false);
    }

    private static Result run(boolean fenceClicks, boolean prefetch, int interval, int stack, int colours,
                              boolean paced, boolean stackSwitch, boolean lookahead, boolean evict) {
        return run(fenceClicks, prefetch, interval, stack, colours, paced, stackSwitch, lookahead, evict, 1);
    }

    private static Result run(boolean fenceClicks, boolean prefetch, int interval, int stack, int colours,
                              boolean paced, boolean stackSwitch, boolean lookahead, boolean evict, int clicks) {
        return run(fenceClicks, prefetch, interval, stack, colours, paced, stackSwitch, lookahead, evict, clicks, true, false);
    }

    private static Result run(boolean fenceClicks, boolean prefetch, int interval, int stack, int colours,
                              boolean paced, boolean stackSwitch, boolean lookahead, boolean evict, int clicks,
                              boolean exactClock, boolean missingAnswer) {
        Map<Pos, BlockSpec> world = new HashMap<>();
        Map<String, Integer> stock = new HashMap<>();
        for (String c : COLOURS) stock.put("minecraft:" + c + "_carpet", 10_000);
        LaneSim sim = new LaneSim(new LaneSim.Blocks() {
            @Override public BlockSpec get(Pos p) { return world.getOrDefault(p, BlockSpec.AIR); }
            @Override public void set(Pos p, BlockSpec b) { world.put(p, b); }
        }, new LaneSim.Items() {
            @Override public int count(String item) { return stock.getOrDefault(item, 0); }
            @Override public boolean take(String item) {
                int n = count(item);
                if (n == 0) return false;
                stock.put(item, n - 1);
                return true;
            }
        }, -64, -64, 64, new AdaptivePacer(interval, interval, interval), 5);
        sim.confirmDelay = 5;
        sim.countLag = 5;
        sim.echoes = true;
        sim.stackSize = stack;
        sim.moveRequestTicks = 2; // move plus its later SetHeldItem: all slots blocked until request completion
        sim.moveFenceTicks = 7;
        sim.staleMoveReports = true;
        sim.lookaheadPrefetch = lookahead;
        sim.lookaheadEvict = evict;
        sim.millisecondArrivals = clicks > 1 && exactClock;
        sim.paperWindowTicks = 6;
        sim.paperWindowMax = 9;
        sim.teleport(-59.5, 64, -63.5);
        List<Placement> ps = new ArrayList<>();
        Random rnd = new Random(5);
        for (int z = -62; z <= -23; z++) {
            for (int x = -64; x <= -56; x++) {
                int c = colours <= 4 ? rnd.nextInt(colours)
                    : Math.floorMod(x, 3) + 3 * Math.floorMod(z / 4 + rnd.nextInt(2), 4);
                String id = "minecraft:" + COLOURS[c] + "_carpet";
                ps.add(new Placement(new Pos(x, 64, z), BlockSpec.of(id), id));
            }
        }
        Lane lane = new Lane(0, new Pos(-60, 64, -63), new Pos(-60, 64, -22), ps);
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        if (missingAnswer && clicks == 1) b.placeMode = "LOOK";
        b.pacedLaneWalk = paced;
        b.placeDuringMoveFence = fenceClicks;
        b.placeHotbarPrefetch = prefetch;
        b.placeStackSwitch = stackSwitch;
        b.placeHotbarLookahead = lookahead;
        b.placeHotbarLookaheadEvict = evict;
        b.placeClicksPerTick = clicks;
        b.placeSubTickDispatch = clicks > 1;
        b.placeIntervalMinTicks = interval;
        b.placeIntervalMaxTicks = interval;
        sim.silent = PlaceMode.silentRotation(b);
        InventoryEchoes echoes = new InventoryEchoes();
        if (missingAnswer) {
            echoes.quarantine(1, "minecraft:white_carpet", echoes.mark());
            sim.knownSlot = slot -> slot != echoes.quarantinedSlot() && echoes.known(slot);
            sim.inventoryPriority = echoes::inventoryNeeded;
        }
        TaskStatus status = sim.runLane(lane, LaneRun.Params.of(b, sim.tileArea()), NONE, 20_000, (tick, run) -> {
            if (!missingAnswer) return true;
            echoes.tick(true, false);
            if (clicks == 1 && tick == MoveFence.TIMEOUT + 1) {
                assertTrue(echoes.recovering());
                run.requestPark(); // engine hunger/totem path, before an EatTask/OffhandRepairTask is made
                assertEquals(-1, echoes.quarantinedSlot());
                assertFalse(echoes.recovering(), "parking must never wait on speculative recovery");
            }
            if (clicks == 1 && tick == 50) {
                assertTrue(run.parked(), "hunger/offhand parking reaches its safe point without quarantine");
                run.resume();
            }
            if (tick == 160) {
                assertEquals(-1, echoes.quarantinedSlot(), "inventory hold is bounded across task ticks");
                assertFalse(echoes.known(1));
                assertTrue(sim.timedClicks.stream().noneMatch(e -> e.slot() == 1),
                    "LOOK/AIRPLACE and prepared clicks never use the abandoned slot");
                echoes.report(1, "minecraft:white_carpet");
            }
            if (echoes.recovering()) { run.suspendClicks(); return false; }
            return true;
        });
        String log = String.join("\n", sim.log);
        assertEquals(TaskStatus.SUCCESS, status, log);
        assertEquals(360, sim.accepted, log);
        if (clicks == 1) assertEquals(360, sim.clicks, log);
        else assertTrue(sim.clicks >= 360, log); // limiter drops retry; inventory correctness is still exact
        assertEquals(0, sim.wrongClicks, log);
        assertEquals(0, sim.boxRejects, log);
        assertEquals(0, sim.farRejects, log);
        if (clicks == 1) assertEquals(0, sim.paperDrops, log);
        assertEquals(0, sim.moveRuleViolations, "still tick, no click with a move/request/fenced slot: " + log);
        if (paced) FastPlacementTest.assertStandingAccounting(log);
        else assertFalse(log.contains("standing ticks "), log);
        assertTrue(sim.maxPlacementsPerTick <= clicks, log);
        assertTrue(sim.skipReasons.isEmpty(), log);
        for (Placement p : ps) assertEquals(p.block(), world.get(p.pos()), "same color at " + p.pos());
        var m = IDLE.matcher(log);
        assertTrue(m.find(), log);
        return new Result(sim, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    }

    private static void improvement(String label, Result before, Result after) {
        System.out.printf("%s: %.4f -> %.4f clicks/walk tick; idle %d -> %d; lane ticks %d -> %d; moves %d -> %d; prefetch %d%n",
            label, before.clicksPerTick(), after.clicksPerTick(), before.idle, after.idle,
            before.sim.lastTicks, after.sim.lastTicks, before.sim.inventoryMoves, after.sim.inventoryMoves,
            after.sim.prefetchMoves);
        assertTrue(after.idle < before.idle, label + ": idle " + before.idle + " -> " + after.idle);
        assertTrue(after.sim.lastTicks < before.sim.lastTicks, label);
    }

    @Test
    void fenceWaitClicksOtherSlotsEvenWithStaleReports() {
        Result before = run(false, false, 1, 64, 12, true, true);
        Result after = run(true, false, 1, 64, 12, true, true);
        improvement("fence clicks, 12 colors, 64-stacks", before, after);
        assertEquals(0, before.sim.fenceClicks);
        assertTrue(after.sim.fenceClicks > 0);
        assertTrue(after.sim.staleReports > 0);
        assertEquals(0, after.sim.prefetchMoves);
        String line = String.join("\n", before.sim.log);
        assertTrue(FastPlacementTest.reasonCount(line, "standing ticks", "hotbar move/fence") > 0, line);
        assertTrue(FastPlacementTest.reasonCount(line, "standing ticks", "inventory busy") > 0, line);
    }

    @Test
    void prefetchUsesPacedStandingGaps() {
        Result before = run(true, false, 2, 64, 12, true, true);
        Result after = run(true, true, 2, 64, 12, true, true);
        improvement("prefetch, interval 2, 12 colors", before, after);
        assertTrue(after.sim.prefetchMoves > 0);
        assertTrue(after.sim.inventoryMoves <= before.sim.inventoryMoves, "no speculative churn");
    }

    @Test
    void bothFixesAtIntervalOneWithStackWaits() {
        Result before = run(false, false, 1, 8, 12, true, true);
        Result after = run(true, true, 1, 8, 12, true, true);
        improvement("both fixes, interval 1, 8-stacks", before, after);
    }

    @Test
    void fenceClicksKeepTheStackGuardWithoutStackSwitch() {
        Result before = run(false, false, 1, 16, 4, true, false);
        Result after = run(true, false, 1, 16, 4, true, false);
        improvement("fence clicks, stack switch off", before, after);
    }

    @Test
    void prefetchDoesNothingWithoutAPacedWalk() {
        Result before = run(true, false, 1, 64, 12, false, true);
        Result after = run(true, true, 1, 64, 12, false, true);
        assertEquals(before.sim.lastTicks, after.sim.lastTicks);
        assertEquals(before.sim.inventoryMoves, after.sim.inventoryMoves);
        assertEquals(0, after.sim.prefetchMoves);
    }

    @Test
    void prefetchDoesNotStealAReadyClick() {
        Result before = run(true, false, 1, 64, 12, true, true);
        Result after = run(true, true, 1, 64, 12, true, true);
        assertEquals(before.sim.lastTicks, after.sim.lastTicks);
        assertEquals(before.idle, after.idle);
        assertEquals(0, after.sim.prefetchMoves);
    }

    @Test
    void prefetchNeverEvictsAnUpcomingOrUnsettledStack() {
        assertTrue(Hotbar.prefetchSafe(4, slot -> Integer.MAX_VALUE, slot -> true));
        assertFalse(Hotbar.prefetchSafe(4, slot -> 100, slot -> true));
        assertFalse(Hotbar.prefetchSafe(4, slot -> Integer.MAX_VALUE, slot -> false));
        assertFalse(Hotbar.prefetchSafe(4, null, slot -> true));
    }

    @Test
    void lookaheadRefillsMixedLaneBeforeStacksRunOut() {
        Result before = run(true, true, 2, 16, 12, true, true, false, false);
        Result after = run(true, true, 2, 16, 12, true, true, true, true);
        improvement("40-cell lookahead, 12 alternating colors, 16-stacks", before, after);
        int oldStanding = FastPlacementTest.reasonCount(String.join("\n", before.sim.log), "standing ticks", "hotbar move/fence");
        int newStanding = FastPlacementTest.reasonCount(String.join("\n", after.sim.log), "standing ticks", "hotbar move/fence");
        System.out.println("Standing move ticks: " + oldStanding + " -> " + newStanding);
        // the switches-off numbers follow main's lane walk (step back tuning, 2026-10-14), so only the comparison is pinned
        assertTrue(after.sim.lastTicks < before.sim.lastTicks, before.sim.lastTicks + " -> " + after.sim.lastTicks);
        assertTrue(newStanding < oldStanding);
        assertTrue(after.sim.prefetchMoves > 0);
    }

    @Test
    void lookaheadWithThreeSubTickClicksKeepsEveryAnswerGuard() {
        Result before = run(true, true, 1, 16, 12, true, true, false, false, 3);
        Result after = run(true, true, 1, 16, 12, true, true, true, true, 3);
        improvement("40-cell lookahead, three sub-tick clicks", before, after);
        int oldStanding = FastPlacementTest.reasonCount(String.join("\n", before.sim.log), "standing ticks", "hotbar move/fence");
        int newStanding = FastPlacementTest.reasonCount(String.join("\n", after.sim.log), "standing ticks", "hotbar move/fence");
        System.out.println("Three-click standing move ticks: " + oldStanding + " -> " + newStanding);
        System.out.println("Three-click Paper drops: " + before.sim.paperDrops + " -> " + after.sim.paperDrops);
        assertTrue(after.sim.lastTicks < before.sim.lastTicks, before.sim.lastTicks + " -> " + after.sim.lastTicks);
        assertTrue(newStanding < oldStanding, oldStanding + " -> " + newStanding);
        assertTrue(after.sim.paperDrops <= before.sim.paperDrops, "lookahead must not increase drops");
    }

    @Test
    void oldTickRoundedThreeClickScenarioAlsoKeepsDropsAtOrBelowOff() {
        Result before = run(true, true, 1, 16, 12, true, true, false, false, 3, false, false);
        Result after = run(true, true, 1, 16, 12, true, true, true, true, 3, false, false);
        int oldStanding = FastPlacementTest.reasonCount(String.join("\n", before.sim.log), "standing ticks", "hotbar move/fence");
        int newStanding = FastPlacementTest.reasonCount(String.join("\n", after.sim.log), "standing ticks", "hotbar move/fence");
        System.out.printf("Tick-rounded regression: ticks %d -> %d, standing moves %d -> %d, drops %d -> %d%n",
            before.sim.lastTicks, after.sim.lastTicks, oldStanding, newStanding, before.sim.paperDrops, after.sim.paperDrops);
        assertTrue(newStanding <= oldStanding, oldStanding + " -> " + newStanding);
        assertTrue(after.sim.paperDrops <= before.sim.paperDrops);
    }

    @Test
    void boundedMissingAnswerSlotIsNotClickedUntilAReport() {
        run(true, true, 1, 64, 4, true, true, true, true, 3, true, true);
    }

    @Test
    void hungerOrTotemParkClearsRecoveryAndLookNeverClicksItsUnknownSlot() {
        run(true, true, 1, 64, 4, true, true, true, true, 1, true, true);
    }

    @Test
    void oldConfigsEnableBothIndependentSwitchesAndKeepOneClickPerTick() {
        MapArtConfig.BuildCfg b = new Gson().fromJson("{}", MapArtConfig.BuildCfg.class);
        assertTrue(b.placeDuringMoveFence);
        assertTrue(b.placeHotbarPrefetch);
        assertFalse(b.placeHotbarLookahead);
        assertFalse(b.placeHotbarLookaheadEvict);
        assertEquals(1, b.placeClicksPerTick);
        assertEquals(PlacePacing.DEFAULT, PlacePacing.of(b));
        b.placeDuringMoveFence = false;
        assertFalse(PlacePacing.of(b).fenceClicks());
        assertTrue(PlacePacing.of(b).prefetch());
        b.placeDuringMoveFence = true;
        b.placeHotbarPrefetch = false;
        assertTrue(PlacePacing.of(b).fenceClicks());
        assertFalse(PlacePacing.of(b).prefetch());
    }
}
