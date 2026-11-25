package io.github.digitalisp.mapart.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;
import io.github.digitalisp.mapart.command.SubmissionPipeline.Accepted;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.discord.Card;
import io.github.digitalisp.mapart.discord.DiscordCommands;
import io.github.digitalisp.mapart.discord.SubmissionCards;
import io.github.digitalisp.mapart.engine.CoordFormat;
import io.github.digitalisp.mapart.engine.EngineControl.EngineStatus;
import io.github.digitalisp.mapart.engine.Phase;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.digitalisp.mapart.command.Fakes.job;
import static io.github.digitalisp.mapart.command.Fakes.materials;

/**
 * Every {@code !map} reply readable on Discord's phone app (the owner, 2026-10-28: "can u have all discord commands be readable
 * on mobile"). A code block there shows about 32 characters before it wraps or scrolls, so no line in one may be longer;
 * the replies carry no column tables (runs of spaces that line numbers up), and no line is a run-on.
 */
class MobileRepliesTest {
    static final long NOW = 1_000_000_000L;
    /** A code block line on a phone. */
    static final int CODE_MAX = 32;
    /** A line of normal text: it wraps, but a line this long is a paragraph that should be split. */
    static final int LINE_MAX = 200;
    private static final Pattern FENCE = Pattern.compile("```(?:[a-z]*\\n)?(.*?)```", Pattern.DOTALL);
    private static final Pattern COLUMNS = Pattern.compile("\\S {3,}\\S");

    Fakes.Store store;
    Fakes.Engine engine;
    QueueOps ops;
    DiscordCommands commands;

    @BeforeEach
    void setUp() {
        store = new Fakes.Store();
        engine = new Fakes.Engine();
        ops = new QueueOps(store, () -> engine, () -> NOW);
        MapArtConfig.DiscordCfg cfg = new MapArtConfig.DiscordCfg();
        commands = new DiscordCommands(ops, cfg, () -> { }, CoordFormat::real, NOPLogger.NOP_LOGGER);
        store.add(job("j0001", "Old", JobStatus.DONE, 1, 1, Map.of()))
            .add(job("j0002", "Embrace the Winter", JobStatus.ACTIVE, 5, 6,
                materials("minecraft:white_carpet", 13_048, "minecraft:blue_terracotta", 13_048)))
            .add(job("j0003", "Cancelled", JobStatus.CANCELLED, 1, 1, Map.of()))
            .add(job("j0004", "a map art name of the longest kind there is, 50 ch", JobStatus.QUEUED, 4, 2,
                materials("minecraft:white_carpet", 500, "minecraft:black_carpet", 300)))
            .add(job("j0005", "Short", JobStatus.WAITING_MATERIALS, 1, 1, materials("minecraft:red_carpet", 50)))
            .add(job("j0006", "Failed", JobStatus.FAILED, 1, 1, Map.of()));
        store.jobs.get(4).statusDetail = "short: red_carpet x50";
        store.jobs.get(1).tiles.get(0).status = TileStatus.STORED;
        store.stock.update(new Pos(0, 64, 0), Map.of("minecraft:white_carpet", 2000, "minecraft:black_carpet", 10,
            "minecraft:stone", 64), 3, 27, NOW - 3_600_000);
        engine.status = new EngineStatus(true, false, "", Phase.BUILD, "j0002", "Embrace the Winter", 0, 3, "soul",
            8_192, 16_384, "lanes 6-7 of the tile", NOW - 120_000);
    }

    private Reply run(String text) {
        return commands.handle(CommandLine.parse(text, "!map").orElseThrow(), "!map", "jared2013");
    }

    @Test
    void everyCommandReplyFitsAPhone() {
        List<String> cmds = List.of("!map help", "!map queue", "!map status", "!map stock", "!map stock carpet",
            "!map stock stone", "!map stock zzz", "!map scan", "!map submit", "!map verbose", "!map verbose on",
            "!map verbose off", "!map verbose maybe", "!map pause", "!map resume", "!map move j0004 1",
            "!map move j0004", "!map retry j0006", "!map retry j0004", "!map cancel j0005", "!map cancel nope",
            "!map stop", "!map stop disconnect", "!map stop now please", "!map dance");
        for (String c : cmds) {
            Reply r = run(c);
            assertFits(c, r.title());
            assertFits(c, r.body());
        }
    }

    @Test
    void submissionCardsFitAPhone() {
        Job j = job("j0007", "Pine_Tree", JobStatus.QUEUED, 2, 1, Map.of());
        j.platformIds = new ArrayList<>(List.of("moss", "soul"));
        j.submitterName = "jared2013";
        List<QueueOps.MaterialRow> rows = List.of(new QueueOps.MaterialRow("minecraft:white_carpet", 8192, 12000),
            new QueueOps.MaterialRow("minecraft:black_carpet", 4000, 1200),
            new QueueOps.MaterialRow("minecraft:decorated_pot", 4, 0, true));
        Card c = SubmissionCards.of(new Accepted(j, 2, 12192, rows, true, List.of("Ignored 1.21.5 data version"), null),
            List.of());
        assertFits("accepted", c.description());
        assertTrue(c.fields().stream().noneMatch(Card.Field::inline), "no inline fields: " + c.fields());
        assertFits("rejected", SubmissionCards.rejected(List.of("Not flat: 3 top heights")).description());
    }

    /** No code block line over {@link #CODE_MAX}, no column table, no line over {@link #LINE_MAX}. */
    static void assertFits(String what, String text) {
        if (text == null) return;
        Matcher m = FENCE.matcher(text);
        while (m.find())
            for (String line : m.group(1).split("\n"))
                assertTrue(line.length() <= CODE_MAX, what + ": code block line of " + line.length() + " chars: " + line);
        String plain = FENCE.matcher(text).replaceAll("");
        for (String line : plain.split("\n")) {
            assertTrue(line.length() <= LINE_MAX, what + ": line of " + line.length() + " chars: " + line);
            assertFalse(COLUMNS.matcher(line).find(), what + ": a column table: " + line);
        }
    }
}
