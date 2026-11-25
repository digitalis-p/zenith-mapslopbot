package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.LIME;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * Cells that only wait for stock never use up VERIFY's repair passes ({@code build.verifyStockWaitFree}). Live
 * 2026-10-13 01:03, ellen [1,6]: "tile does not match after 4 repair passes: 0 wrong, 123 missing", 122 of them cyan
 * terracotta with no stock. Here a white cell is refused on the build pass and the first three repair passes and goes
 * up on the fourth; then only lime is left, which no chest holds: the stock wait (STOCK_CHECK), and the passes start
 * again from 0. When the lime comes, a lime cell is refused on two more passes: with the switch the tile is finished,
 * without it the passes already used BLOCK it.
 */
class VerifyStockWaitTest {
    private static final int LONG = 120_000;
    private static final Pos LIME_CHEST = new Pos(-90, 61, 104);

    record Result(Job job, Sim sim, RecordingLogger log) { }

    static Result run(boolean free) {
        RecordingLogger log = new RecordingLogger();
        Sim sim = new Sim();
        sim.modules.state.put("AntiAFK", false);
        sim.config.build.waitingRecheckMinutes = 2;
        sim.config.build.verifyStockWaitFree = free;
        sim.bot.addContainer(LIME_CHEST, Map.of());
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log);
        TileArt art = PartialBuildTest.mixed(4); // 512 cells, 128 of them lime: no lime at the base yet
        Job job = sim.addJob("j0950", "Wait free", 1, 1, List.of("moss"), (x, z) -> art);
        Pos white = new Pos(sim.moss.minX + 12, sim.moss.artY, sim.moss.minZ + 1); // a white carpet cell
        Pos lime = new Pos(sim.moss.minX + 11, sim.moss.artY, sim.moss.minZ + 2);
        AtomicInteger whiteTries = new AtomicInteger();
        AtomicInteger limeTries = new AtomicInteger();
        sim.bot.unplaceable = p -> {
            if (p.pos().equals(white)) return whiteTries.incrementAndGet() <= 4 ? "not reachable" : null;
            if (p.pos().equals(lime)) return limeTries.incrementAndGet() <= 2 ? "not reachable" : null;
            return null;
        };
        sim.start();
        // the white cell up (after 4 refusals), then the wait for lime
        assertTrue(sim.runUntil(() -> {
            String n = sim.engine.status().note();
            return sim.phase() == Phase.STOCK_CHECK && n != null && n.startsWith(BuildEngine.MATERIALS_NOTE)
                && sim.world.block(white).id().equals(WHITE) || sim.phase() == Phase.BLOCKED;
        }, LONG), sim.state());
        assertEquals(WHITE, sim.world.block(white).id(), sim.state());
        assertEquals(5, whiteTries.get(), "refused on the build pass and 3 repair passes");
        // the lime comes
        sim.bot.containers.get(LIME_CHEST).items.put(LIME, 1728);
        sim.runUntil(() -> job.status == JobStatus.DONE
            || sim.phase() == Phase.BLOCKED, LONG);
        return new Result(job, sim, log);
    }

    @Test
    void stockWaitingCellsUseNoRepairPasses() {
        Result r = run(true);
        assertEquals(JobStatus.DONE, r.job().status, r.sim().state());
        r.log().lines.stream().filter(l -> l.contains("verify pass") || l.contains("repair passes")).forEach(System.out::println);
        assertTrue(r.log().lines.stream().anyMatch(l -> l.contains("repair passes back to 0 for the stock wait (were 4)")),
            "the passes start again for the wait");
    }

    @Test
    void withoutTheSwitchThePassesBeforeTheWaitCount() {
        Result r = run(false);
        assertEquals(Phase.BLOCKED, r.sim().phase(), r.sim().state());
        assertTrue(r.log().lines.stream().anyMatch(l -> l.contains("tile does not match after 4 repair passes")),
            "blocked by the passes before the wait");
    }
}
