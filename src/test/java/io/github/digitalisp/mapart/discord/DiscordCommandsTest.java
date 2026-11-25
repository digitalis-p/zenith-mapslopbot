package io.github.digitalisp.mapart.discord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;
import io.github.digitalisp.mapart.command.CommandLine;
import io.github.digitalisp.mapart.command.Fakes;
import io.github.digitalisp.mapart.command.QueueOps;
import io.github.digitalisp.mapart.command.Reply;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.CoordFormat;
import io.github.digitalisp.mapart.model.JobStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiscordCommandsTest {
    Fakes.Store store;
    Fakes.Engine engine;
    DiscordCommands commands;

    @BeforeEach
    void setUp() {
        store = new Fakes.Store();
        engine = new Fakes.Engine();
        store.add(Fakes.job("j0001", "One", JobStatus.QUEUED, 1, 1, Map.of()))
            .add(Fakes.job("j0002", "Two", JobStatus.FAILED, 1, 1, Map.of()))
            .add(Fakes.job("j0003", "Three", JobStatus.QUEUED, 1, 1, Map.of()));
        commands = new DiscordCommands(new QueueOps(store, () -> engine, () -> 0L), CoordFormat::real,
            NOPLogger.NOP_LOGGER);
    }

    private Reply run(String text) {
        return commands.handle(CommandLine.parse(text, "!map").orElseThrow(), "!map", "jared2013");
    }

    @Test
    void helpListsCommandsWithPrefix() {
        Reply r = run("!map");
        assertEquals("Map art commands", r.title());
        for (String c : List.of("queue", "status", "cancel <id>", "move <id> <pos>", "pause [reason]", "resume",
            "stock [filter]", "scan", "retry <id>", "verbose [on|off]"))
            assertTrue(r.body().contains("`!map " + c + "`"), c);
        assertTrue(DiscordCommands.help(".art").body().contains("`.art queue`"));
        assertTrue(r.body().contains("optional text = job name (max 50 chars), e.g. `verity`"), r.body());
    }

    @Test
    void submitHintSaysTheTextIsTheJobName() {
        Reply r = run("!map submit");
        assertEquals("How to submit", r.title());
        assertTrue(r.body().contains("Optional text = job name (max 50 chars), e.g. `verity`"), r.body());
        assertTrue(r.body().contains("`!map queue`"), r.body());
    }

    @Test
    void dispatchesToQueueOps() {
        assertTrue(run("!map queue").body().contains("j0001"));
        assertEquals("Map art status", run("!map status").title());
        assertEquals("Cancelled j0001", run("!map cancel j0001").title());
        assertEquals("Moved j0003 to #1", run("!map move j0003 1").title());
        assertEquals("Requeued j0002", run("!map retry 2").title());
        assertEquals("Stock scan requested", run("!map scan").title());
        assertEquals("Resuming", run("!map resume").title());
        assertTrue(run("!map stock carpet").title().startsWith("Material stock: carpet"));
    }

    @Test
    void pauseRecordsWhoAndWhy() {
        run("!map pause");
        run("!map pause clearing   the lane");
        assertEquals(List.of("pause:paused by jared2013 (Discord)", "pause:clearing   the lane (jared2013)"), engine.calls);
    }

    @Test
    void missingArgumentsShowUsage() {
        Reply r = run("!map move j0001");
        assertTrue(r.isError());
        assertEquals("Usage: `!map move <id> <position>`", r.body());
        assertTrue(run("!map move j0001 abc").isError());
        assertEquals("Usage: `!map cancel <id>`", run("!map cancel").body());
        assertEquals("Usage: `!map retry <id>`", run("!map retry").body());
    }

    @Test
    void helpListsStopFirst() {
        String[] lines = run("!map help").body().split("\n");
        assertTrue(lines[0].startsWith("`!map stop` **halt NOW**"), lines[0]);
        for (String s : List.of("`!map kill`", "`!map halt`", "Anyone may use it"))
            assertTrue(lines[0].contains(s), s + " in " + lines[0]);
        assertFalse(lines[0].contains("\uD83D\uDED1"), "no reaction stop: " + lines[0]);
        assertTrue(lines[1].startsWith("`!map stop disconnect`"), lines[1]);
        assertTrue(lines[1].contains("no auto-reconnect"), lines[1]);
        assertTrue(run("!map help").body().contains("`!map pause [reason]` pause after the current action"));
        assertFalse(run("!map help").body().contains("safe point"));
    }

    @Test
    void stopAndItsAliasesHaltAtOnce() {
        for (String text : List.of("!map stop", "!map kill", "!map halt", "!map STOP", "!MAP Halt")) {
            CommandLine cmd = CommandLine.parse(text, "!map").orElseThrow();
            assertTrue(DiscordCommands.isStop(cmd), text);
            engine.calls.clear();
            Reply r = run(text);
            assertEquals("STOPPED", r.title(), text);
            assertTrue(r.body().startsWith("Bot halted, Baritone stopped, pause saved. `!map resume` to continue."),
                r.body());
            assertEquals(List.of("stop:manual stop by jared2013 (Discord)"), engine.calls, text);
        }
        for (String text : List.of("!map pause", "!map status", "!map", "!map stopp"))
            assertFalse(DiscordCommands.isStop(CommandLine.parse(text, "!map").orElseThrow()), text);
    }

    @Test
    void stopDisconnectCallsTheServerLink() {
        List<String> links = new ArrayList<>();
        DiscordCommands withLink = new DiscordCommands(new QueueOps(store, () -> engine, () -> 0L, () -> {
            links.add("disconnect");
            return "Disconnected from the server. No auto-reconnect.";
        }, Runnable::run), CoordFormat::real, NOPLogger.NOP_LOGGER);
        for (String text : List.of("!map stop disconnect", "!map kill dc", "!map halt DISCONNECT")) {
            Reply r = withLink.handle(CommandLine.parse(text, "!map").orElseThrow(), "!map", "jared2013");
            assertEquals("STOPPED", r.title(), text);
            assertTrue(r.body().contains("Disconnected from the server. No auto-reconnect."), r.body());
        }
        assertEquals(3, links.size());
        assertEquals(3, engine.calls.size(), "every disconnect also halts");

        // anything else after stop still stops, but never disconnects
        Reply r = withLink.handle(CommandLine.parse("!map stop now please", "!map").orElseThrow(), "!map", "jared2013");
        assertEquals("STOPPED", r.title());
        assertEquals(3, links.size());
        assertTrue(r.body().contains("`now please` ignored; `!map stop disconnect` also disconnects."), r.body());
    }

    @Test
    void pauseReplyPointsToStop() {
        Reply r = run("!map pause");
        assertTrue(r.body().contains("typically within one tick"), r.body());
        assertTrue(r.body().contains("`!map stop`"), r.body());
    }

    @Test
    void verboseShowsAndSwitchesTheSettingAndSavesIt() {
        MapArtConfig.DiscordCfg cfg = new MapArtConfig.DiscordCfg();
        int[] saves = {0};
        DiscordCommands withCfg = new DiscordCommands(new QueueOps(store, () -> engine, () -> 0L), cfg,
            () -> saves[0]++, CoordFormat::real, NOPLogger.NOP_LOGGER);
        java.util.function.Function<String, Reply> say =
            text -> withCfg.handle(CommandLine.parse(text, "!map").orElseThrow(), "!map", "jared2013");

        Reply shown = say.apply("!map verbose");
        assertEquals("Verbose posts off", shown.title());
        assertEquals(Reply.Tone.INFO, shown.tone());
        assertTrue(shown.body().contains("\"Map done\" per stored map"), shown.body());
        assertTrue(shown.body().contains("`!map verbose on`"), shown.body());
        assertEquals(0, saves[0], "showing saves nothing");

        Reply on = say.apply("!map verbose ON");
        assertTrue(cfg.verbose);
        assertEquals("Verbose posts on", on.title());
        assertEquals(Reply.Tone.SUCCESS, on.tone());
        assertEquals(1, saves[0]);
        assertEquals("Verbose posts on", say.apply("!map verbose").title());

        assertEquals("Verbose posts off", say.apply("!map verbose off").title());
        assertFalse(cfg.verbose);
        assertEquals(2, saves[0]);

        Reply bad = say.apply("!map verbose maybe");
        assertTrue(bad.isError());
        assertTrue(bad.body().contains("`!map verbose [on|off]`"), bad.body());
        assertFalse(cfg.verbose);
        assertEquals(2, saves[0]);

        // without the settings (the old constructor): an error, never a silent no-op
        assertTrue(run("!map verbose on").isError());
    }

    @Test
    void unknownCommand() {
        Reply r = run("!map dance");
        assertTrue(r.isError());
        assertTrue(r.body().contains("`dance`"));
        assertTrue(r.body().contains("!map help"));
    }
}
