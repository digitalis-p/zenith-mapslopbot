package io.github.digitalisp.mapart.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSource;
import com.zenith.command.api.DiscordCommandSource;
import com.zenith.discord.Embed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.CoordFormat;
import io.github.digitalisp.mapart.engine.Notifier;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.Pos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parses (never executes) the brigadier tree: execution writes ZenithProxy embeds whose colours read
 * ZenithProxy's global config, which must not be initialised in unit tests.
 */
class MapArtCommandTest {
    MapArtConfig config;
    Fakes.Store store;
    Fakes.Engine engine;
    List<Runnable> background;
    List<String> notes;
    int syncs;
    int gateChecks;
    MapArtCommand cmd;
    CommandDispatcher<CommandContext> dispatcher;

    static final class Source implements CommandSource {
        final boolean owner;

        Source(boolean owner) {
            this.owner = owner;
        }

        @Override
        public String name() {
            return "Test";
        }

        @Override
        public boolean validateAccountOwner(CommandContext ctx) {
            return owner;
        }

        @Override
        public void logEmbed(CommandContext ctx, Embed embed) { }
    }

    final class RecordingNotifier implements Notifier {
        @Override public void info(String t, String b) { notes.add("info:" + t); }
        @Override public void alert(String t, String b) { notes.add("alert:" + t); }
        @Override public void image(String t, String b, String f, byte[] p) { notes.add("image:" + t + ":" + f); }
        @Override public void progress(Job j, int x, int z, int p, int t, long e) { }
    }

    @BeforeEach
    void setUp() {
        config = new MapArtConfig();
        store = new Fakes.Store();
        engine = new Fakes.Engine();
        background = new ArrayList<>();
        notes = new ArrayList<>();
        QueueOps ops = new QueueOps(store, () -> engine, () -> 0L);
        SubmissionPipelineTest.FakeSubmissions submissions = new SubmissionPipelineTest.FakeSubmissions();
        SubmissionPipeline pipeline = new SubmissionPipeline(submissions, new Fakes.Catalog(), config, store,
            new SubmissionPipelineTest.FakeRenderer(), (id, b) -> 0, () -> engine, NOPLogger.NOP_LOGGER);
        Notifier notifier = new RecordingNotifier();
        // Production gates with Command::validateAccountOwner, whose "Not Authorized" path colours the embed
        // from ZenithProxy's global config; this gate asks the fake source directly instead.
        cmd = new MapArtCommand(config, ops, pipeline, null, () -> syncs++, background::add, () -> notifier,
            () -> CoordFormat.of(config), NOPLogger.NOP_LOGGER, ctx -> {
                gateChecks++;
                return ctx.getSource().validateAccountOwner(ctx);
            });
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(cmd.register());
    }

    private boolean parses(String input, boolean owner) {
        ParseResults<CommandContext> p = dispatcher.parse(input, CommandContext.create(input, new Source(owner)));
        return p.getExceptions().isEmpty() && !p.getReader().canRead() && p.getContext().getCommand() != null;
    }

    @Test
    void readOnlySubcommandsParseForAnyone() {
        for (String s : List.of("mapart", "mapart status", "mapart queue", "mapart stock", "mapart stock white carpet",
            "mapart site"))
            assertTrue(parses(s, false), s);
        assertEquals(0, gateChecks);
    }

    @Test
    void stopIsOpenToEveryoneWithoutAnOwnerCheck() {
        for (String s : List.of("mapart stop", "mapart stop disconnect", "mapart kill", "mapart kill disconnect",
            "mapart halt", "mapart halt disconnect", "mapart STOP", "mapart Stop Disconnect", "mapart stop now!!")) {
            assertTrue(parses(s, false), "non-owner: " + s);
            assertTrue(parses(s, true), "owner: " + s);
        }
        assertEquals(0, gateChecks, "stop never consults the owner gate");
    }

    @Test
    void usageListsStopFirst() {
        String first = cmd.commandUsage().getUsageLines().getFirst();
        assertTrue(first.startsWith("stop [disconnect]"), first);
        assertTrue(first.contains("kill") && first.contains("halt"), first);
    }

    @Test
    void mutatingSubcommandsNeedAccountOwner() {
        List<String> inputs = List.of("mapart on", "mapart off", "mapart pause", "mapart pause eating lunch",
            "mapart resume", "mapart cancel j0001", "mapart move j0001 2", "mapart retry j0001", "mapart scan",
            "mapart submit /tmp/some dir/art.nbt", "mapart discord", "mapart discord on", "mapart discord off",
            "mapart discord reload", "mapart verbose", "mapart verbose on", "mapart verbose off",
            "mapart resubstitute j0001", "mapart resubstitute j0001 apply");
        for (String s : inputs) {
            assertTrue(parses(s, true), "owner: " + s);
            assertFalse(parses(s, false), "non-owner: " + s);
        }
    }

    @Test
    void badArgumentsDoNotParse() {
        for (String s : List.of("mapart move j0001", "mapart move j0001 0", "mapart move j0001 x", "mapart cancel",
            "mapart frobnicate", "mapart submit", "mapart discord maybe", "mapart verbose maybe",
            "mapart resubstitute", "mapart resubstitute j0001 now"))
            assertFalse(parses(s, true), s);
    }

    @Test
    void usageListsEverySubcommand() {
        List<String> lines = cmd.commandUsage().getUsageLines();
        for (String sub : List.of("on/off", "status", "queue", "pause", "resume", "cancel", "move", "retry", "scan",
            "stock", "submit", "resubstitute", "discord on/off", "discord reload", "verbose", "site"))
            assertTrue(lines.stream().anyMatch(l -> l.startsWith(sub)), sub);
        assertEquals("mapart", cmd.commandUsage().getName());
    }

    @Test
    void toggleUpdatesConfigAndSyncsModule() {
        Reply r = cmd.setEnabled(true);
        assertTrue(config.enabled);
        assertEquals(1, syncs);
        assertEquals(Reply.Tone.SUCCESS, r.tone());
        cmd.setEnabled(false);
        assertFalse(config.enabled);
        assertEquals(2, syncs);
    }

    @Test
    void verboseSwitchesTheDiscordSetting() {
        assertFalse(config.discord.verbose, "off by default");
        Reply on = cmd.setVerbose(true);
        assertTrue(config.discord.verbose);
        assertEquals("Verbose posts on", on.title());
        assertEquals(Reply.Tone.SUCCESS, on.tone());
        assertTrue(on.body().contains("`mapart verbose off`"), on.body());
        cmd.setVerbose(false);
        assertFalse(config.discord.verbose);
    }

    /** {@code mapart elytra on|off}: the elytra legs' switch at runtime, off being the kill switch. */
    @Test
    void elytraSwitchesTheElytraLegs() {
        assertFalse(config.build.elytraLegs, "off by default");
        Reply on = cmd.setElytra(true);
        assertTrue(config.build.elytraLegs);
        assertEquals("Elytra legs on", on.title());
        assertTrue(on.body().contains("`mapart elytra off`"), on.body());
        assertTrue(on.body().contains("Arcs: off."), on.body());
        config.build.elytraArcLegs = true;
        assertTrue(cmd.setElytra(true).body().contains("Arcs: on, from 60 blocks."));
        Reply off = cmd.setElytra(false);
        assertFalse(config.build.elytraLegs);
        assertEquals("Elytra legs off", off.title());
        assertTrue(off.body().contains("stay in the inventory"), off.body());
    }

    /** {@code mapart elytra short|hops on|off}: stage 5's switches at runtime; off is still the kill switch. */
    @Test
    void elytraShortAndHopsSwitchAtRuntime() {
        assertFalse(config.build.elytraShortLandings, "off by default");
        assertFalse(config.build.elytraLongHops, "off by default");
        config.build.elytraLegs = true;
        Reply r = cmd.setElytraShort(true);
        assertTrue(config.build.elytraShortLandings);
        assertTrue(r.body().contains("Short landings: on, from 30 blocks."), r.body());
        r = cmd.setElytraLongHops(true);
        assertTrue(config.build.elytraLongHops);
        assertTrue(r.body().contains("Long hops: on."), r.body());
        Reply off = cmd.setElytra(false);
        assertFalse(config.build.elytraLegs, "the kill switch");
        assertTrue(off.body().contains("no flight while the legs are off"), off.body());
        cmd.setElytraShort(false);
        cmd.setElytraLongHops(false);
        assertFalse(config.build.elytraShortLandings);
        assertFalse(config.build.elytraLongHops);
    }

    /** {@code mapart elytra kittrip on|off}: {@code build.elytraKitFarTrip} at runtime, off by default. */
    @Test
    void elytraKitFarTripSwitchesAtRuntime() {
        assertFalse(config.build.elytraKitFarTrip, "off by default");
        config.build.elytraLegs = true;
        assertTrue(cmd.setElytra(true).body().contains("Kit trip to a far rocket chest from a color-suppression job: off."));
        Reply r = cmd.setElytraKitFarTrip(true);
        assertTrue(config.build.elytraKitFarTrip);
        assertTrue(r.body().contains("Kit trip to a far rocket chest from a color-suppression job: on."), r.body());
        cmd.setElytraKitFarTrip(false);
        assertFalse(config.build.elytraKitFarTrip);
    }

    @Test
    void discordWithoutClientReportsError() {
        assertTrue(cmd.setDiscord(true).isError());
        assertTrue(config.discord.enabled);
        assertTrue(cmd.reloadDiscord().isError());
        assertTrue(cmd.statusReply().body().contains("**Discord bot:** not available"));
        assertTrue(cmd.statusReply().body().contains("**Builder:** off"));
    }

    @Test
    void submitRunsInBackgroundAndNotifies(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("art_0_0.nbt"), new byte[]{1, 2, 3});
        Reply r = cmd.submit("\"" + dir + "\"");
        assertFalse(r.isError());
        assertEquals(1, background.size());
        assertTrue(store.jobs.isEmpty());
        background.get(0).run();
        assertEquals(1, store.jobs.size());
        assertEquals("terminal", store.jobs.get(0).submitterId);
        assertEquals(List.of("image:Queued j0001: sizedreisen:preview.png"), notes);
        assertEquals(List.of("queueChanged"), engine.calls);
    }

    @Test
    void resubstituteApplyNeedsTheBuilderPausedAndRunsInTheBackground() {
        store.add(Fakes.job("j0001", "ellen", io.github.digitalisp.mapart.model.JobStatus.ACTIVE, 1, 1, java.util.Map.of()));
        assertTrue(cmd.resubstitute("j0009", false).isError(), "unknown job");
        config.enabled = true;
        Reply refused = cmd.resubstitute("j0001", true);
        assertTrue(refused.isError(), "the engine may read a tile at any moment");
        assertTrue(refused.body().contains("mapart pause"), refused.body());
        assertTrue(background.isEmpty());

        Reply dry = cmd.resubstitute("1", false);
        assertFalse(dry.isError(), "a dry run never needs a pause");
        assertTrue(dry.title().startsWith("Dry run"), dry.title());
        config.paused = true;
        assertFalse(cmd.resubstitute("j0001", true).isError());
        assertEquals(2, background.size());
        background.forEach(Runnable::run); // the fake store has no tile files: the run reports, nothing changes
        assertEquals(2, notes.size(), notes.toString());
        assertTrue(engine.calls.isEmpty(), engine.calls.toString());
    }

    @Test
    void submitEchoesThePathAsTyped(@TempDir Path dir) {
        String p = dir.resolve("C_carpet_flat_2x1_split__mapart.zip").toString();
        Reply r = cmd.submit(p);
        assertEquals("Submitting `" + p + "`", r.title(), "inline code: the terminal's markdown keeps the underscores");
    }

    @Test
    void submitOfMissingPathOnlyLogs(@TempDir Path dir) {
        cmd.submit(dir.resolve("missing.nbt").toString());
        background.get(0).run();
        assertTrue(store.jobs.isEmpty());
        assertTrue(notes.isEmpty());
    }

    @Test
    void unquote() {
        assertEquals("a b", MapArtCommand.unquote("\"a b\""));
        assertEquals("a b", MapArtCommand.unquote("'a b'"));
        assertEquals("\"a", MapArtCommand.unquote("\"a"));
        assertEquals("x", MapArtCommand.unquote("x"));
    }

    @Test
    void siteCheckIsRelativeOnlyInZenithProxyDiscord() {
        int ox = -128 * 7813, oz = -128 * 15625;   // synthetic base, not a real coordinate
        MapArtConfig.PlatformCfg moss = new MapArtConfig.PlatformCfg();
        moss.minX = ox - 64;
        moss.minZ = oz - 64;
        moss.center = new Pos(ox, 179, oz);
        config.site.platforms.add(moss);
        config.site.anvil = new Pos(ox + 3, 175, oz - 72);
        Reply site = SiteCheck.reply(config);
        assertTrue(site.body().contains(new Pos(ox, 179, oz).key()), "real by default");

        assertSame(site, cmd.forSource(new DiscordCommandSource(), site), "REAL is the default");
        config.discord.coordinates = "RELATIVE";
        String discord = cmd.forSource(new DiscordCommandSource(), site).body();
        assertTrue(discord.contains("map area -64,-64 to 63,63"), discord);
        assertTrue(discord.contains("center 0,179,0"), discord);
        assertTrue(discord.contains("anvil: 3,175,-72"), discord);
        assertFalse(Pattern.compile("\\d{5,}").matcher(discord).find(), discord);
        assertSame(site, cmd.forSource(new Source(true), site), "terminal and in-game keep real coordinates");
    }
}
