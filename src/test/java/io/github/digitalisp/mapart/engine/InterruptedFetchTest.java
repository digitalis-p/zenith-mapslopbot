package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * A fetch cut short (a disconnect, a pause, a back-off) is not a fetch that found nothing: the chest is tried again.
 * Seen in a Sim run: a disconnect during RENAME's XP fetch used up the only XP chest's attempt, and the step went
 * BLOCKED "no XP for renaming: none carried and no XP chest tried yet".
 */
class InterruptedFetchTest {
    private static final int LONG = 60_000;

    /** RENAME with no XP: runs until the bot is fetching bottles from the (only) XP chest. */
    private static Job untilXpFetch(Sim sim) {
        Job job = sim.addJob("j0301", "Xp", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> {
            BotTask t = sim.bot.lastTask;
            return sim.phase() == Phase.RENAME && t != null && !t.isFinished()
                && t.name().startsWith("withdraw " + Sim.XP);
        }, LONG), sim.state());
        return job;
    }

    private static void assertDoneWithoutBlock(Sim sim, Job job) {
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), "job not done: " + sim.state());
        assertFalse(sim.notifier.has("alert", "blocked"), sim.notifier.toString());
        assertFalse(sim.store.phaseLog.contains("BLOCKED"), sim.store.phaseLog.toString());
        assertEquals("Xp", sim.output().maps.getFirst().name());
        assertTrue(sim.bot.count("withdraw " + Sim.XP) >= 2, "the XP chest was tried again: " + sim.bot.history);
    }

    @Test
    void reconnectDuringTheXpFetchRetriesIt() {
        Sim sim = new Sim();
        Job job = untilXpFetch(sim);
        // the adapter's order on a disconnect and reconnect
        sim.engine.onBotTickStopped();
        sim.engine.onDisconnect();
        sim.run(3);
        sim.engine.onBotTickStarting();
        assertDoneWithoutBlock(sim, job);
    }

    @Test
    void aFetchCancelledWhileTheStepGoesOnRetriesIt() {
        Sim sim = new Sim();
        Job job = untilXpFetch(sim);
        // the disconnect's cancel lands while the step's context lives on (no ClientBotTick.Starting in between)
        sim.engine.onDisconnect();
        assertDoneWithoutBlock(sim, job);
    }

    @Test
    void aBackOffDuringTheXpFetchRetriesIt() {
        Sim sim = new Sim();
        Job job = untilXpFetch(sim);
        sim.engine.backoff("setbacks", 2_000);
        assertDoneWithoutBlock(sim, job);
    }
}
