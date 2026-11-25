package io.github.digitalisp.mapart.discord;

import net.dv8tion.jda.api.OnlineStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.discord.PresenceText.Link;
import io.github.digitalisp.mapart.engine.BuildEngine;
import io.github.digitalisp.mapart.engine.EngineControl.EngineStatus;
import io.github.digitalisp.mapart.engine.Phase;
import io.github.digitalisp.mapart.model.Job;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;

class PresenceUpdaterTest {
    /** Records what would go to JDA. */
    static final class FakeTarget implements PresenceUpdater.Target {
        Object connection = new Object();
        boolean failApply;
        boolean failConnection;
        final List<String> sent = new ArrayList<>();

        @Override
        public Object connection() {
            if (failConnection) throw new IllegalStateException("jda gone");
            return connection;
        }

        @Override
        public void apply(OnlineStatus status, String text) {
            if (failApply) throw new IllegalStateException("gateway closed");
            sent.add(status + "|" + text);
        }
    }

    long now = 1_000_000L;
    FakeTarget target;
    MapArtConfig.DiscordCfg cfg;
    Job job;
    EngineStatus status;
    boolean sourceFails;
    PresenceUpdater u;

    @BeforeEach
    void setUp() {
        target = new FakeTarget();
        cfg = new MapArtConfig.DiscordCfg();
        job = PresenceTextTest.job("j0007", "Pine", 1, 1);
        phase(Phase.BUILD, 4_300);
        u = new PresenceUpdater(target, () -> {
            if (sourceFails) throw new IllegalStateException("store busy");
            return PresenceText.input(status, List.of(job), true, Link.IN_GAME, 0);
        }, cfg, NOPLogger.NOP_LOGGER, () -> now);
    }

    void phase(Phase p, int placed) {
        status = new EngineStatus(true, false, "", p, job.id, job.name, 0, 0, "moss", placed, 10_000, "", 0);
    }

    void at(long ms) {
        now = 1_000_000L + ms;
        u.tick();
    }

    @Test
    void firstPollSendsRightAwayAndUnchangedIsNotResent() {
        at(0);
        assertEquals(List.of("ONLINE|Printing · Pine · 43%"), target.sent);
        at(5_000);
        at(60_000);
        at(600_000);
        assertEquals(1, target.sent.size());
    }

    @Test
    void aChangeInsideTheWindowGoesOutAtTheNextAllowedPoll() {
        at(0);
        phase(Phase.RESTOCK, 4_300); // phase change 5 s later
        at(5_000);
        at(10_000);
        assertEquals(1, target.sent.size(), "held back: at most one update per 15 s");
        at(15_000);
        assertEquals("ONLINE|Restocking · Pine · 43%", target.sent.get(1));
    }

    @Test
    void statesInsideTheWindowAreSkippedLatestWins() {
        at(0);
        phase(Phase.RESTOCK, 4_300);
        at(5_000);
        phase(Phase.BUILD, 4_500);
        at(10_000);
        phase(Phase.BUILD, 4_600);
        at(14_999);
        at(15_000);
        assertEquals(List.of("ONLINE|Printing · Pine · 43%", "ONLINE|Printing · Pine · 46%"),
            target.sent);
    }

    @Test
    void rateNeverExceedsOnePerIntervalEvenWhenEveryPollChanges() {
        for (int i = 0; i <= 120; i++) { // 10 minutes of 5 s polls while the % keeps rising
            phase(Phase.BUILD, i * 50);
            at(i * 5_000L);
        }
        assertEquals(41, target.sent.size()); // t = 0, 15, 30, … 600 s
    }

    @Test
    void theOnlineStatusGoesOutWithTheLineInOneUpdateAndTheSameWindow() {
        at(0);
        // the fill waits for the platform above: new line and idle dot, one update
        status = new EngineStatus(true, false, "", Phase.FILL_MAP, job.id, job.name, 0, 0, "moss", 10_000, 10_000,
            BuildEngine.COVER_NOTE + " (3,200 pixels covered)", 0);
        at(5_000);
        at(15_000);
        assertEquals("IDLE|Waiting: platform above in use (3,200 pixels covered) · Pine · 100%", target.sent.get(1));
        // paused by hand, then stuck: still at most one update per window
        EngineStatus build = new EngineStatus(true, false, "", Phase.BUILD, job.id, job.name, 0, 0, "moss", 4_300,
            10_000, "", 0);
        status = PresenceTextTest.paused(build, "lunch");
        at(30_000);
        status = PresenceTextTest.paused(build, BuildEngine.STUCK_PREFIX + "setbacks");
        at(35_000);
        at(40_000);
        assertEquals(3, target.sent.size(), "a change inside the window waits for it");
        at(45_000);
        assertEquals(List.of(
            "ONLINE|Printing · Pine · 43%",
            "IDLE|Waiting: platform above in use (3,200 pixels covered) · Pine · 100%",
            "IDLE|Paused: lunch · Pine · 43%",
            "DO_NOT_DISTURB|Paused: stuck: setbacks · Pine · 43%"), target.sent);
    }

    @Test
    void longerConfiguredIntervalIsRespectedAndShorterIsClamped() {
        cfg.presenceIntervalSeconds = 60;
        assertEquals(60_000, PresenceUpdater.intervalMs(cfg));
        at(0);
        phase(Phase.GEAR, 4_300);
        at(30_000);
        assertEquals(1, target.sent.size());
        at(60_000);
        assertEquals(2, target.sent.size());

        cfg.presenceIntervalSeconds = 1;
        assertEquals(15_000, PresenceUpdater.intervalMs(cfg));
        cfg.presenceIntervalSeconds = -5;
        assertEquals(15_000, PresenceUpdater.intervalMs(cfg));
    }

    @Test
    void nothingIsSentWhileNotConnected() {
        target.connection = null;
        at(0);
        at(30_000);
        assertTrue(target.sent.isEmpty());
        target.connection = new Object();
        at(35_000);
        assertEquals(1, target.sent.size());
    }

    @Test
    void aNewClientGetsTheLineRightAway() {
        at(0);
        target.connection = new Object(); // reload: new JDA instance
        at(3_000);
        assertEquals(2, target.sent.size());
        assertEquals(target.sent.get(0), target.sent.get(1));
    }

    @Test
    void failuresNeverEscape() {
        sourceFails = true;
        assertDoesNotThrow(() -> at(0));
        assertTrue(target.sent.isEmpty());
        sourceFails = false;

        target.failConnection = true;
        assertDoesNotThrow(() -> at(5_000));
        target.failConnection = false;

        target.failApply = true;
        assertDoesNotThrow(() -> at(10_000));
        assertTrue(target.sent.isEmpty());
        target.failApply = false;
        at(15_000);
        assertTrue(target.sent.isEmpty(), "a failed send still counts against the rate limit");
        at(25_000);
        assertEquals(1, target.sent.size(), "retried after the window");
    }

    @Test
    void turningPresenceOffClearsItOnce() {
        at(0);
        cfg.presence = false;
        at(5_000);
        assertEquals(1, target.sent.size());
        at(15_000);
        assertEquals("ONLINE|null", target.sent.get(1));
        at(30_000);
        at(60_000);
        assertEquals(2, target.sent.size());
        cfg.presence = true;
        at(75_000);
        assertEquals("ONLINE|Printing · Pine · 43%", target.sent.get(2));
    }

    @Test
    void presenceOffFromTheStartSendsNothing() {
        cfg.presence = false;
        at(0);
        at(60_000);
        assertTrue(target.sent.isEmpty());
    }

    @Test
    void startIsIdempotentAndStopCancels() {
        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1);
        exec.setRemoveOnCancelPolicy(true);
        try {
            u.start(exec);
            u.start(exec);
            assertEquals(1, exec.getQueue().size());
            u.stop();
            assertEquals(0, exec.getQueue().size());
            u.stop();
            u.start(exec);
            assertEquals(1, exec.getQueue().size());
            u.stop();
        } finally {
            exec.shutdownNow();
        }
    }
}
