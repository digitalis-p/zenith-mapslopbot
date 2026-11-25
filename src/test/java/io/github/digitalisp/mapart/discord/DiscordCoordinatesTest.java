package io.github.digitalisp.mapart.discord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.command.CommandLine;
import io.github.digitalisp.mapart.command.Fakes;
import io.github.digitalisp.mapart.command.QueueOps;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.CoordFormat;
import io.github.digitalisp.mapart.engine.EngineControl.EngineStatus;
import io.github.digitalisp.mapart.engine.Phase;
import io.github.digitalisp.mapart.engine.TeleportGuard;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Every Discord path shows coordinates per {@code discord.coordinates}; logs keep the real ones. */
class DiscordCoordinatesTest {
    // Synthetic base far from spawn, built arithmetically (not a real coordinate). Origin = moss map centre.
    static final int OX = -128 * 7813;
    static final int OZ = -128 * 15625;
    static final Pattern FIVE_DIGITS = Pattern.compile("\\d{5,}");
    static final String MENTION = "<@&123456789012345678>";

    MapArtConfig config;
    DiscordNotifierTest.FakeSink sink;
    RecordingLogger log;
    DiscordNotifier notifier;
    final Pos stuck = new Pos(OX + 5, 173, OZ - 7);

    @BeforeEach
    void setUp() {
        config = new MapArtConfig();
        config.discord.coordinates = "RELATIVE";
        config.discord.alertMention = MENTION;
        MapArtConfig.PlatformCfg moss = new MapArtConfig.PlatformCfg();
        moss.minX = OX - 64;
        moss.minZ = OZ - 64;
        moss.artY = 173;
        config.site.platforms.add(moss);
        sink = new DiscordNotifierTest.FakeSink();
        log = new RecordingLogger();
        notifier = new DiscordNotifier(sink, config.discord, () -> CoordFormat.of(config), log, () -> 0L);
    }

    private static void assertNoRealNumbers(String text) {
        assertFalse(FIVE_DIGITS.matcher(text).find(), "real coordinate left in: " + text);
    }

    private Card sendAlert(String title, String body) {
        notifier.alert(title, body);
        notifier.pump();
        return sink.posts.getLast();
    }

    private List<String> teleportHalts() {
        List<String> halts = new ArrayList<>();
        MapArtConfig.SafetyCfg safety = new MapArtConfig.SafetyCfg();
        TeleportGuard g = new TeleportGuard(safety, () -> List.of(config.site.platforms.getFirst().geometry()),
            () -> 0L, new TeleportGuard.Sink() {
                @Override public void halt(String reason) { halts.add(reason); }
                @Override public void warn(String title, String body) { }
                @Override public void backoff(String reason, long millis) { }
            });
        g.botTicksStarted();
        for (int i = 0; i <= safety.startGraceTicks; i++) g.tick(OX + 1.5, 174, OZ + 0.5, false);
        g.teleport(false, OX + 21.5, false, 174, false, OZ + 0.5, false);
        g.teleport(true, 5000, true, 0, true, 0, false);
        return halts;
    }

    @Test
    void teleportGuardHaltIsRelativeInDiscordAndRealInTheLog() {
        List<String> halts = teleportHalts();
        assertEquals(2, halts.size(), halts.toString());

        Card near = sendAlert("SAFETY STOP", halts.get(0) + "\nAll bot actions halted.");
        assertEquals("server teleported the bot 20.0 blocks: 1.5, 174.0, 0.5 -> 21.5, 174.0, 0.5\n"
            + "All bot actions halted.", near.description());
        assertEquals(MENTION, near.content());
        String logged = log.at(Level.WARN).getFirst();
        assertTrue(logged.contains(CoordFormat.point(OX + 1.5, 174, OZ + 0.5)), logged);

        Card far = sendAlert("SAFETY STOP", halts.get(1));
        assertEquals("server teleported the bot over 1,000 blocks: 21.5, 174.0, 0.5 -> " + CoordFormat.FAR,
            far.description());
    }

    @Test
    void blockedAlertWithAPosIsRelative() {
        Card c = sendAlert("Map art blocked", "#j0042 Safety [0,0] on moss\nPhase BUILD: cannot break 1 blocks on "
            + "the art layer, e.g. minecraft:bedrock at " + stuck + "\nFix it, then `!map resume`.");
        assertTrue(c.description().contains("minecraft:bedrock at [5,173,-7]"), c.description());
        assertTrue(c.description().contains("[0,0] on moss"), c.description());
        assertNoRealNumbers(c.description());
        assertTrue(log.at(Level.WARN).getFirst().contains(stuck.toString()), "log keeps the real position");
        assertEquals(1, log.lines.size(), "no safety-net warning for a Pos: " + log.lines);
    }

    @Test
    void mergedInfosAreConvertedBeforeTheyAreTruncated() {
        notifier.info("Tile built", "x".repeat(595) + " at " + stuck);
        notifier.info("Map locked", "chest " + stuck.key());
        notifier.info("Map stored", "output chest " + stuck.key());
        notifier.pump();
        assertEquals(1, sink.posts.size());
        String d = sink.posts.getFirst().description();
        assertNoRealNumbers(d);
        assertFalse(d.contains("-10"), d);
        assertTrue(d.contains("output chest 5,173,-7"), d);
    }

    @Test
    void mapStatusAndQueueAreRelative() {
        Fakes.Store store = new Fakes.Store();
        Fakes.Engine engine = new Fakes.Engine();
        String halt = teleportHalts().getFirst();
        String note = "cannot reach " + stuck + ": pathing to GoalBlock{x=" + (OX + 3) + ",y=70,z=" + (OZ - 4)
            + "} failed";
        engine.status = new EngineStatus(true, true, "SAFETY STOP: " + halt, Phase.BLOCKED, "j0042", "Safety", 0, 0,
            "moss", 10, 100, note, 0);
        Job waiting = Fakes.job("j0043", "Next", JobStatus.QUEUED, 1, 1, Map.of());
        waiting.statusDetail = "x".repeat(74) + " " + stuck;   // the queue table cuts this inside the X value
        store.add(waiting);
        DiscordCommands commands = new DiscordCommands(new QueueOps(store, () -> engine, () -> 0L),
            () -> CoordFormat.of(config), log);

        String status = commands.handle(CommandLine.parse("!map status", "!map").orElseThrow(), "!map", "jared2013").body();
        assertTrue(status.contains("1.5, 174.0, 0.5 -> 21.5, 174.0, 0.5"), status);
        assertTrue(status.contains("cannot reach [5,173,-7]: pathing to GoalBlock{x=3,y=70,z=-4} failed"), status);
        assertNoRealNumbers(status);

        String queue = commands.handle(CommandLine.parse("!map queue", "!map").orElseThrow(), "!map", "jared2013").body();
        assertTrue(queue.contains(" […"), queue);
        assertFalse(queue.contains("-10"), queue);
    }

    @Test
    void realModeLeavesDiscordTextUnchanged() {
        config.discord.coordinates = "REAL";
        String body = "Phase BUILD: cannot break 1 blocks, e.g. minecraft:bedrock at " + stuck + ", lane "
            + (OX - 28) + ".." + (OX - 21) + ", " + teleportHalts().getLast();
        assertEquals(body, sendAlert("Map art blocked", body).description());
        assertEquals(1, log.lines.size(), log.lines.toString());
    }

    @Test
    void safetyNetConvertsUnknownFormatsAndWarnsWithTheTitle() {
        notifier.info("Odd format", "lane " + (OX - 28) + ".." + (OX - 21));
        notifier.pump();
        assertEquals("lane -28..-21", sink.posts.getFirst().description());
        assertTrue(log.at(Level.WARN).stream().anyMatch(l -> l.contains("\"Odd format\"")), log.lines.toString());
    }

    @Test
    void cardMapTextCoversFieldsAndFooterAndKeepsContentAndAttachment() {
        CoordFormat f = CoordFormat.of(config);
        byte[] png = {1, 2};
        Card c = Card.builder().content(MENTION).title("Queued j0001").description("at " + stuck)
            .field("Chest", stuck.key(), true).footer("near " + stuck).attachment("preview.png", png).build()
            .mapText(t -> f.discord(t, "card", log));
        assertEquals("at [5,173,-7]", c.description());
        assertEquals("5,173,-7", c.fields().getFirst().value());
        assertEquals("near [5,173,-7]", c.footer());
        assertEquals(MENTION, c.content());
        assertSame(png, c.attachment().data());
    }
}
