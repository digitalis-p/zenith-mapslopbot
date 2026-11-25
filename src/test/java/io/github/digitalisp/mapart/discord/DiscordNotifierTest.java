package io.github.digitalisp.mapart.discord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import org.slf4j.helpers.NOPLogger;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.CoordFormat;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class DiscordNotifierTest {
    /** Records posts/edits; callbacks run immediately (like a fast JDA response) unless {@code deferred}. */
    static final class FakeSink implements DiscordNotifier.Sink {
        boolean ready = true;
        boolean deferred;
        boolean editsFail;
        int nextId = 100;
        final List<Card> posts = new ArrayList<>();
        final List<String> edits = new ArrayList<>();
        final List<Runnable> pendingCallbacks = new ArrayList<>();

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public void post(Card card, Consumer<String> onPosted) {
            posts.add(card);
            String id = String.valueOf(nextId++);
            if (deferred) pendingCallbacks.add(() -> onPosted.accept(id));
            else onPosted.accept(id);
        }

        @Override
        public void edit(String messageId, Card card, Consumer<Boolean> onDone) {
            edits.add(messageId + ":" + card.description());
            onDone.accept(!editsFail);
        }
    }

    long now = 1_000_000L;
    FakeSink sink;
    MapArtConfig.DiscordCfg cfg;
    DiscordNotifier n;
    Job job;

    @BeforeEach
    void setUp() {
        sink = new FakeSink();
        cfg = new MapArtConfig.DiscordCfg();
        cfg.alertMention = "<@&123>";
        n = new DiscordNotifier(sink, cfg, CoordFormat::real, NOPLogger.NOP_LOGGER, () -> now);
        job = new Job();
        job.id = "j0007";
        job.name = "Pine";
    }

    @Test
    void eventsAreSentOnlyByThePump() {
        n.info("Tile stored", "1,0");
        assertTrue(sink.posts.isEmpty());
        assertEquals(1, n.queued());
        n.pump();
        assertEquals(1, sink.posts.size());
        assertEquals("Tile stored", sink.posts.get(0).title());
        assertEquals(Card.INFO, sink.posts.get(0).color());
        assertNull(sink.posts.get(0).content());
        assertEquals(0, n.queued());
    }

    /** Job ids read as random strings on Discord; the log keeps them. */
    @Test
    void jobIdsAreLeftOutOfTheCardNotTheLog() {
        RecordingLogger log = new RecordingLogger();
        n = new DiscordNotifier(sink, cfg, CoordFormat::real, log, () -> now);
        n.info("Started Snowy Angel", "#fbw204nw4zvwd Snowy Angel (Gei/Piston Plaza) [0,7] on platform soulsand: 9,800 blocks.");
        n.alert("No progress", "Stuck for 10 min while restocking.\nSnowy Angel tile 6,1 (#fbw204nw4zvwd, lanes 2-3)");
        n.pump();
        assertEquals("Snowy Angel (Gei/Piston Plaza) [0,7] on platform soulsand: 9,800 blocks.", sink.posts.get(0).description());
        assertEquals("Stuck for 10 min while restocking.\nSnowy Angel tile 6,1 (lanes 2-3)", sink.posts.get(1).description());
        assertTrue(log.lines.get(0).contains("#fbw204nw4zvwd Snowy Angel"), log.lines.toString());
        assertTrue(log.lines.get(1).contains("(#fbw204nw4zvwd, lanes 2-3)"), log.lines.toString());
    }

    @Test
    void withoutJobIdsKeepsEveryOtherNumber() {
        assertEquals("Pine [1,0] stored", DiscordNotifier.withoutJobIds("#j0007 Pine [1,0] stored"));
        assertEquals("Pine: 2 maps", DiscordNotifier.withoutJobIds("#f1abc2de Pine: 2 maps"));
        assertEquals("Job failed: out of lapis", DiscordNotifier.withoutJobIds("Job failed: out of lapis\n(#j0007)"));
        assertEquals("Pine tile 1,0 (lanes 0-1)", DiscordNotifier.withoutJobIds("Pine tile 1,0 (#j0007, lanes 0-1)"));
        for (String keep : List.of("map #1017", "queue #3", "zombie #4512", "channel #feedalerts", "#j12 art", "##j0007 x",
            "Pine#j0007", "#fbw204nw4zvwdx1 too long"))
            assertEquals(keep, DiscordNotifier.withoutJobIds(keep));
        assertNull(DiscordNotifier.withoutJobIds(null));
    }

    @Test
    void alertCarriesMentionAndImageCarriesPng() {
        n.alert("Clear failed", "moss");
        n.image("Final map", "j0007 1,0", "map 1,0", new byte[]{1, 2});
        n.pump();
        assertEquals("<@&123>", sink.posts.get(0).content());
        assertEquals(Card.ERROR, sink.posts.get(0).color());
        Card img = sink.posts.get(1);
        assertEquals("map_1_0.png", img.attachment().fileName());
        assertArrayEquals(new byte[]{1, 2}, img.attachment().data());

        cfg.alertMention = "  ";
        n.alert("Again", "x");
        now += 5_000;
        n.pump();
        assertNull(sink.posts.get(2).content());
    }

    @Test
    void notReadyKeepsEventsThenExpiresThem() {
        sink.ready = false;
        n.alert("A", "a");
        n.pump();
        assertTrue(sink.posts.isEmpty());
        assertEquals(1, n.queued());
        sink.ready = true;
        n.pump();
        assertEquals(1, sink.posts.size());

        sink.ready = false;
        n.info("B", "b");
        now += DiscordNotifier.MAX_AGE_MS + 1;
        n.info("C", "c");
        n.pump();
        assertEquals(1, n.queued()); // B expired, C kept
        sink.ready = true;
        n.pump();
        assertEquals("C", sink.posts.get(1).title());
    }

    @Test
    void tokenBucketLimitsBursts() {
        for (int i = 0; i < 8; i++) n.alert("A" + i, "x"); // alerts are never merged
        n.pump();
        assertEquals(DiscordNotifier.BURST, sink.posts.size());
        n.pump();
        assertEquals(DiscordNotifier.BURST, sink.posts.size());
        now += DiscordNotifier.REFILL_MS;
        n.pump();
        assertEquals(DiscordNotifier.BURST + 1, sink.posts.size());
        now += 10 * DiscordNotifier.REFILL_MS;
        n.pump();
        assertEquals(8, sink.posts.size());
        assertEquals(List.of("A0", "A1", "A2", "A3", "A4", "A5", "A6", "A7"),
            sink.posts.stream().map(Card::title).toList());
    }

    @Test
    void backlogOfInfosIsMerged() {
        for (int i = 0; i < 6; i++) n.info("Event " + i, "detail " + i);
        n.pump();
        assertEquals(1, sink.posts.size());
        Card merged = sink.posts.get(0);
        assertEquals("6 events", merged.title());
        assertTrue(merged.description().startsWith("**Event 0**\ndetail 0\n\n**Event 1**"), merged.description());
        assertEquals(0, n.queued());
    }

    @Test
    void fewInfosAreNotMergedAndAlertsBreakMerging() {
        n.info("One", "1");
        n.info("Two", "2");
        n.pump();
        assertEquals(List.of("One", "Two"), sink.posts.stream().map(Card::title).toList());

        now += 60_000;
        n.info("I1", "");
        n.info("I2", "");
        n.alert("Stop", "");
        n.info("I3", "");
        n.pump();
        assertEquals(List.of("2 events", "Stop", "I3"), sink.posts.subList(2, 5).stream().map(Card::title).toList());
    }

    @Test
    void progressPostsThenEditsThenPostsAgainAfterInterval() {
        cfg.verbose = true;
        cfg.progressIntervalMinutes = 15;
        n.progress(job, 1, 0, 100, 16384, 3600);
        n.pump();
        assertEquals(1, sink.posts.size());
        assertEquals("Printing Pine", sink.posts.get(0).title());
        assertEquals("**0.6%** placed (100 / 16,384)\nETA 1h 00m", sink.posts.get(0).description());

        // within the edit interval: coalesced, nothing sent
        now += 10_000;
        n.progress(job, 1, 0, 200, 16384, 3500);
        n.progress(job, 1, 0, 300, 16384, 3400);
        n.pump();
        assertEquals(1, sink.posts.size());
        assertTrue(sink.edits.isEmpty());

        // after the edit interval: the latest value edits the same message
        now += DiscordNotifier.EDIT_INTERVAL_MS;
        n.pump();
        assertEquals(List.of("100:**1.8%** placed (300 / 16,384)\nETA 56m"), sink.edits);

        // nothing new → nothing sent
        now += DiscordNotifier.EDIT_INTERVAL_MS;
        n.pump();
        assertEquals(1, sink.edits.size());

        // after progressIntervalMinutes a fresh message is posted
        now += 15 * 60_000;
        n.progress(job, 1, 0, 9000, 16384, 600);
        n.pump();
        assertEquals(2, sink.posts.size());
        assertEquals(1, sink.edits.size());
    }

    @Test
    void newTilePostsImmediately() {
        cfg.verbose = true;
        n.progress(job, 0, 0, 16000, 16384, 10);
        n.pump();
        now += 1_000;
        n.progress(job, 1, 0, 5, 16384, 9999);
        n.pump();
        assertEquals(2, sink.posts.size());
        assertEquals("Printing Pine", sink.posts.get(1).title());
    }

    @Test
    void failedEditFallsBackToNewPost() {
        cfg.verbose = true;
        n.progress(job, 0, 0, 1, 100, 10);
        n.pump();
        sink.editsFail = true;
        now += DiscordNotifier.EDIT_INTERVAL_MS;
        n.progress(job, 0, 0, 2, 100, 10);
        n.pump();
        assertEquals(1, sink.edits.size());
        now += DiscordNotifier.EDIT_INTERVAL_MS;
        n.progress(job, 0, 0, 3, 100, 10);
        n.pump();
        assertEquals(2, sink.posts.size());
    }

    @Test
    void progressWaitsWhilePostIsInFlight() {
        cfg.verbose = true;
        sink.deferred = true;
        n.progress(job, 0, 0, 1, 100, 10);
        n.pump();
        now += DiscordNotifier.EDIT_INTERVAL_MS * 2;
        n.progress(job, 0, 0, 2, 100, 10);
        n.pump();
        assertEquals(1, sink.posts.size());
        assertTrue(sink.edits.isEmpty());
        sink.pendingCallbacks.forEach(Runnable::run);
        n.pump();
        assertEquals(List.of("100:**2.0%** placed (2 / 100)\nETA 10s"), sink.edits);
    }

    @Test
    void fullQueueDropsInfosButKeepsAlerts() {
        sink.ready = false;
        for (int i = 0; i < DiscordNotifier.MAX_QUEUE + 20; i++) n.info("I" + i, "");
        assertEquals(DiscordNotifier.MAX_QUEUE, n.queued());
        n.alert("Important", "");
        assertEquals(DiscordNotifier.MAX_QUEUE, n.queued());
        sink.ready = true;
        for (int i = 0; i < 100; i++) {
            now += 60_000;
            n.pump();
        }
        assertEquals(0, n.queued());
        assertTrue(sink.posts.stream().anyMatch(c -> "Important".equals(c.title())));
        assertTrue(sink.posts.stream().noneMatch(c -> "I0".equals(c.title())
            || (c.description() != null && c.description().contains("**I0**"))));
    }

    @Test
    void verboseIsOffByDefault() {
        assertFalse(new MapArtConfig.DiscordCfg().verbose);
        assertFalse(cfg.verbose);
    }

    @Test
    void verboseOffLogsDetailsAndPostsNoProgressButKeepsEverythingElse() {
        RecordingLogger log = new RecordingLogger();
        n = new DiscordNotifier(sink, cfg, CoordFormat::real, log, () -> now);
        n.detail("Backing off", "5 server setbacks in 1.2 s");
        n.detail("Tile built", "#j0007 Pine [1,0]: 16384 blocks verified");
        n.progress(job, 1, 0, 100, 16384, 3600);
        n.alert("SAFETY STOP", "the bot died");
        n.info("Map done: Pine 1,0", "#j0007 Pine, tile 1,0");
        n.image("Map done: Pine 0,0", "#j0007 Pine, tile 0,0", "map 0,0", new byte[]{1});
        assertEquals(3, n.queued(), "details are never queued");
        n.pump();
        assertEquals(List.of("SAFETY STOP", "Map done: Pine 1,0", "Map done: Pine 0,0"),
            sink.posts.stream().map(Card::title).toList());
        assertTrue(sink.edits.isEmpty());
        // nothing is lost from the log
        List<String> info = log.at(Level.INFO);
        assertTrue(info.contains("[mapart] Backing off: 5 server setbacks in 1.2 s"), info.toString());
        assertTrue(info.contains("[mapart] Tile built: #j0007 Pine [1,0]: 16384 blocks verified"), info.toString());

        // more progress while off: still no card, no edit
        now += 30 * 60_000L;
        n.progress(job, 1, 0, 9000, 16384, 600);
        n.pump();
        assertEquals(3, sink.posts.size());
        assertTrue(sink.edits.isEmpty());
    }

    /** A verbose-only image: logged off, posted on. */
    @Test
    void aDetailImageIsPostedOnlyWhenVerbose() {
        RecordingLogger log = new RecordingLogger();
        n = new DiscordNotifier(sink, cfg, CoordFormat::real, log, () -> now);
        n.detailImage("Map done: Pine 0,0", "#j0007 Pine, tile 0,0", "map 0,0", new byte[]{1});
        assertEquals(0, n.queued(), "off: never queued");
        n.pump();
        assertTrue(sink.posts.isEmpty());
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("[mapart] Map done: Pine 0,0: #j0007")),
            log.at(Level.INFO).toString());

        cfg.verbose = true;
        n.detailImage("Map done: Pine 1,0", "#j0007 Pine, tile 1,0", "map 1,0", new byte[]{1, 2});
        n.pump();
        assertEquals(List.of("Map done: Pine 1,0"), sink.posts.stream().map(Card::title).toList());
        assertArrayEquals(new byte[]{1, 2}, sink.posts.getFirst().attachment().data());
    }

    @Test
    void verboseOnPostsDetailsAndProgressAsBefore() {
        cfg.verbose = true;
        n.detail("Backing off", "x");
        n.progress(job, 1, 0, 100, 16384, 3600);
        n.alert("SAFETY STOP", "y");
        n.pump();
        assertEquals(List.of("Backing off", "SAFETY STOP", "Printing Pine"),
            sink.posts.stream().map(Card::title).toList());
        assertEquals(Card.INFO, sink.posts.getFirst().color());
        assertNull(sink.posts.getFirst().content(), "no alert mention on a detail");
    }

    @Test
    void verboseSwitchesLive() {
        cfg.verbose = true;
        n.progress(job, 1, 0, 100, 16384, 3600);
        n.pump();
        assertEquals(1, sink.posts.size());

        cfg.verbose = false;
        now += DiscordNotifier.EDIT_INTERVAL_MS;
        n.progress(job, 1, 0, 200, 16384, 3500);
        n.detail("Map locked", "#j0007");
        n.pump();
        assertEquals(1, sink.posts.size(), "off: no edit, no detail");
        assertTrue(sink.edits.isEmpty());

        // on again, same tile, within the card interval: a fresh card, not an edit of the old one far up the channel
        cfg.verbose = true;
        now += DiscordNotifier.EDIT_INTERVAL_MS;
        n.progress(job, 1, 0, 300, 16384, 3400);
        n.detail("Clearing", "#j0007");
        n.pump();
        assertEquals(List.of("Printing Pine", "Clearing", "Printing Pine"),
            sink.posts.stream().map(Card::title).toList());
        assertTrue(sink.edits.isEmpty());
    }

    @Test
    void sinkExceptionsDoNotEscape() {
        DiscordNotifier bad = new DiscordNotifier(new DiscordNotifier.Sink() {
            @Override public boolean ready() { return true; }
            @Override public void post(Card card, Consumer<String> onPosted) { throw new IllegalStateException("perm"); }
            @Override public void edit(String id, Card card, Consumer<Boolean> onDone) { throw new IllegalStateException("perm"); }
        }, cfg, CoordFormat::real, NOPLogger.NOP_LOGGER, () -> now);
        bad.info("x", "y");
        bad.progress(job, 0, 0, 1, 2, 3);
        assertDoesNotThrow(bad::pump);
    }

    // ---------------------------------------------------------------- progress cards in the status words

    @Test
    void aJobOfSeveralMapsNamesTheMapByItsPlaceNotItsTile() {
        cfg.verbose = true;
        job.tilesX = 2;
        job.tilesZ = 2;
        for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
            Job.TileState t = new Job.TileState();
            t.x = x;
            t.z = z;
            job.tiles.add(t);
        }
        n.progress(job, 0, 1, 100, 16384, 3600);
        n.pump();
        assertEquals("Printing Pine, map 3 of 4", sink.posts.get(0).title());
        assertEquals("**0.6%** placed (100 / 16,384)\nETA 1h 00m", sink.posts.get(0).description());
    }

    /** A colour-suppression map is one map: its card counts all its passes and never names a tile or a pass. */
    @Test
    void aColourSuppressionMapShowsItsLayerAndTheWholeMapsBlocks() {
        cfg.verbose = true;
        cfg.progressIntervalMinutes = 15;
        job.name = "Fox";
        job.tilesX = 8;
        job.tilesZ = 1;
        job.suppression = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            job.suppression.add(new Job.SuppressionPass(i < 4 ? io.github.digitalisp.mapart.model.SuppressionArt.TOP
                : io.github.digitalisp.mapart.model.SuppressionArt.BOTTOM, i % 2, i % 4 < 2));
            Job.TileState t = new Job.TileState();
            t.x = i;
            t.total = 4_000;
            if (i < 4) {
                t.status = io.github.digitalisp.mapart.model.TileStatus.STORED;
                t.placed = 4_000;
            }
            job.tiles.add(t);
        }
        n.progress(job, 4, 0, 2_000, 4_000, 7_200);
        n.pump();
        assertEquals("Printing Fox, bottom layer", sink.posts.get(0).title());
        assertEquals("**56.3%** of the map placed (18,000 / 32,000)\nETA 2h 00m", sink.posts.get(0).description());
        // the next pass goes on in the same card: one map, not a new one per pass
        job.tiles.get(4).status = io.github.digitalisp.mapart.model.TileStatus.STORED;
        now += DiscordNotifier.EDIT_INTERVAL_MS;
        n.progress(job, 5, 0, 1_000, 4_000, 3_600);
        n.pump();
        assertEquals(1, sink.posts.size());
        assertEquals(1, sink.edits.size());
        assertTrue(sink.edits.get(0).contains("**65.6%** of the map placed (21,000 / 32,000)"), sink.edits.get(0));
        for (Card c : sink.posts) {
            assertFalse(c.title().contains("tile") || c.title().toLowerCase().contains("pass"), c.title());
        }
    }
}
