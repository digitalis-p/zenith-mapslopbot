package io.github.digitalisp.mapart.engine;

import net.dv8tion.jda.api.OnlineStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.discord.PresenceText;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.BLACK;
import static io.github.digitalisp.mapart.engine.Sim.LIME;
import static io.github.digitalisp.mapart.engine.Sim.RED;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * Partial build ({@code build.partialBuild}; live 2026-10-11: ellen map 2 idled
 * about 3 hours short of cyan_terracotta x1122 and crimson_nylium x1). A tile short of some items builds every cell the
 * stock covers, waits on its platform for the rest, and is filled, locked and stored only once complete.
 */
class PartialBuildTest {
    private static final int LONG = 80_000;
    /** Sim ticks per minute of clock (50 ms per tick). */
    private static final int MINUTE = 1_200;
    /** The sorter's lime chest in the material region, empty unless a test fills it. */
    private static final Pos LIME_CHEST = new Pos(-90, 61, 104);
    private static final Set<Phase> MAP_PHASES = EnumSet.of(Phase.FILL_MAP, Phase.CHECK_MAP, Phase.LOCK, Phase.RENAME,
        Phase.STORE);

    /** {@code rows} rows of white/red/black carpet stripes with every 4th column lime: 1/4 of the cells are lime. */
    static TileArt mixed(int rows) {
        TileArt a = new TileArt();
        String[] cols = {WHITE, RED, BLACK};
        for (int pz = 0; pz < rows; pz++)
            for (int px = 0; px < 128; px++) a.set(px, pz, BlockSpec.of(px % 4 == 3 ? LIME : cols[px % 3]));
        return a;
    }

    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.modules.state.put("AntiAFK", false); // as on the owner's proxy: the plugin's keep-alive does the swinging
        sim.config.build.waitingRecheckMinutes = 2;
        sim.bot.addContainer(LIME_CHEST, Map.of());
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log != null ? log : Sim.LOG);
        return sim;
    }

    private static boolean waiting(Sim sim) {
        String n = sim.engine.status().note();
        return sim.phase() == Phase.STOCK_CHECK && n != null && n.startsWith(BuildEngine.MATERIALS_NOTE);
    }

    private static Pos cell(Sim sim, int px, int pz) {
        return new Pos(sim.moss.minX + px, sim.moss.artY, sim.moss.minZ + pz);
    }

    /** Lime blocks and other art blocks placed on moss for the {@link #mixed} art of {@code rows} rows. */
    private static int[] placed(Sim sim, int rows) {
        int lime = 0, other = 0;
        for (int pz = 0; pz < rows; pz++)
            for (int px = 0; px < 128; px++) {
                BlockSpec b = sim.world.block(cell(sim, px, pz));
                if (b.isAir()) continue;
                if (b.id().equals(LIME)) lime++;
                else other++;
            }
        return new int[]{lime, other};
    }

    private static int limeWithdrawn(Sim sim) {
        Pattern p = Pattern.compile(Pattern.quote(LIME) + "=(\\d+)");
        int n = 0;
        for (String h : sim.bot.history) {
            if (!h.startsWith("withdraw ")) continue;
            Matcher m = p.matcher(h);
            if (m.find()) n += Integer.parseInt(m.group(1));
        }
        return n;
    }

    private static OnlineStatus presence(Sim sim) {
        return PresenceText.statusFor(PresenceText.input(sim.engine.status(), sim.store.list(), true,
            PresenceText.Link.IN_GAME, 0));
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void aTileShortOfOneItemBuildsEveryOtherCellWaitsThenFinishesWhenItArrives() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.build.nearRecheckAll = false; // the targeted look itself (on: every near container)
        TileArt art = mixed(10); // 1280 cells, 320 lime: the base has no lime yet
        Job job = sim.addJob("j0901", "Mixed", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        List<Phase> seen = new ArrayList<>();
        assertTrue(sim.runUntil(() -> {
            seen.add(sim.phase());
            return waiting(sim);
        }, LONG), sim.state());
        long waitStart = sim.clock.millis();
        int[] before = placed(sim, 10);
        System.out.println("[partial build] sim: " + before[1] + " blocks placed before the wait, "
            + (sim.ticks) + " ticks; waiting for " + sim.engine.status().note());
        assertEquals(960, before[1], "every cell but the lime ones is placed");
        assertEquals(0, before[0]);
        assertEquals(JobStatus.WAITING_MATERIALS, job.status);
        assertTrue(job.statusDetail.contains("320 lime carpet"), job.statusDetail);
        assertEquals("waiting for materials: 320 lime carpet", sim.engine.status().note());
        assertEquals(OnlineStatus.IDLE, presence(sim), "the presence shows the wait as idle");
        assertTrue(seen.stream().noneMatch(MAP_PHASES::contains), "no map before the tile is complete: " + seen);
        // the partial build is announced in the start line (no ping); the ping comes only once nothing can be built
        assertTrue(sim.notifier.of("info").stream().anyMatch(m -> m.title().equals("Started Mixed, partly")
            && m.body().contains("Building the 960 of 1,280 blocks in stock now; it needs 320 more lime carpet before the"
            + " map is filled.")), sim.notifier.toString());
        var alerts = sim.notifier.of("alert");
        assertEquals(1, alerts.size(), sim.notifier.toString());
        assertEquals("Out of lime carpet", alerts.getFirst().title());
        assertTrue(alerts.getFirst().body().startsWith("Mixed needs 320 more lime carpet; no chest at this base holds it."
            + " Put 5 stacks in a chest on the moss platform. Everything else on the map is built (960 of 1,280). Nothing to"
            + " build until then."), alerts.getFirst().body());
        assertEquals(0, sim.bot.count("withdraw " + LIME_CHEST), "nothing planned from the empty lime chest");

        // the wait: chests looked at again (the lime chest, never a full rescan), keep-alive, no map, no BLOCKED
        long limeLooks = sim.bot.count("scan " + LIME_CHEST);
        long scans = sim.bot.count("scan ");
        int keepAlives = sim.bot.keepAlives.size();
        List<Phase> during = new ArrayList<>();
        for (int i = 0; i < 7 * MINUTE; i++) {
            sim.tick();
            during.add(sim.phase());
        }
        assertTrue(waiting(sim), sim.state());
        assertTrue(during.stream().allMatch(p -> p == Phase.STOCK_CHECK), "it stays on the tile: " + Set.copyOf(during));
        assertTrue(sim.bot.count("scan " + LIME_CHEST) >= limeLooks + 3, "a look every 2 min: " + sim.bot.history);
        assertTrue(sim.bot.count("scan ") - scans <= 2L * (sim.bot.count("scan " + LIME_CHEST) - limeLooks),
            "targeted looks only: " + (sim.bot.count("scan ") - scans) + " scans");
        assertTrue(sim.bot.keepAlives.size() > keepAlives, "the keep-alive runs while waiting");
        assertTrue(sim.bot.keepAlives.stream().skip(keepAlives).allMatch(k -> k.phase() == Phase.STOCK_CHECK));
        assertEquals(1, sim.notifier.of("alert").size(), "the shortage is alerted once: " + sim.notifier);
        assertTrue(sim.output().maps.isEmpty());

        // the lime arrives: the next look finds it, the rest is placed, then VERIFY -> FILL_MAP as usual
        sim.bot.containers.get(LIME_CHEST).items.put(LIME, 1728);
        long added = sim.clock.millis();
        long[] fillAt = {-1};
        boolean[] fullAtStore = {false};
        assertTrue(sim.runUntil(() -> {
            if (fillAt[0] < 0 && sim.phase() == Phase.FILL_MAP) fillAt[0] = sim.clock.millis();
            if (sim.phase() == Phase.STORE && sim.matchesArt(sim.moss, art)) fullAtStore[0] = true;
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertTrue(fillAt[0] >= added, "filled only after the lime came");
        assertTrue(fullAtStore[0], "the art was complete when the map was stored");
        assertEquals(1, sim.output().maps.size());
        assertTrue(sim.bot.placeCounts.values().stream().allMatch(n -> n == 1), "no cell placed twice");
        assertEquals(1280, sim.bot.placeCounts.size());
        assertTrue(sim.notifier.of("info").stream().anyMatch(m -> m.title().equals("Materials available")), sim.notifier.toString());
        assertTrue(sim.store.entries("VERIFY") <= 2, "the wait counted no repair pass: " + sim.store.phaseLog);
        System.out.println("[partial build] sim: waited " + (added - waitStart) / 1000 + " s; done "
            + (sim.clock.millis() - added) / 1000 + " s after the lime arrived");
    }

    @Test
    void restockNeverPlansForTheCellsTheStockCannotCover() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.bot.containers.get(LIME_CHEST).items.put(LIME, 100); // 100 of the 320 lime cells
        Job job = sim.addJob("j0902", "Some lime", 1, 1, List.of("moss"), (x, z) -> mixed(10));
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());
        int[] p = placed(sim, 10);
        assertEquals(100, p[0], "every lime the stock had is placed");
        assertEquals(960, p[1]);
        assertEquals(100, limeWithdrawn(sim), "lime withdrawn: only what the chest held, " + sim.bot.history);
        assertEquals("waiting for materials: 220 lime carpet", sim.engine.status().note());
        List<String> info = log.at(Level.INFO);
        assertTrue(info.stream().anyMatch(l -> l.contains("restock: leaving out 220 cells the stock cannot cover")), info.toString());
        assertTrue(info.stream().noneMatch(l -> l.contains("withdrawals came up short")), "no planned withdrawal came up short");
        assertTrue(info.stream().noneMatch(l -> l.startsWith("INFO build round") && l.contains(" 0 placed")),
            "every build round placed something: " + info);
        assertTrue(log.at(Level.WARN).stream().anyMatch(l -> l.contains("building the 1060 of 1280 cells")), log.lines.toString());
        assertEquals(JobStatus.WAITING_MATERIALS, job.status);
    }

    @Test
    void aWaitingTileSurvivesARestartWithoutFillingTheMap() {
        Sim sim = sim(null);
        Job job = sim.addJob("j0903", "Restart", 1, 1, List.of("moss"), (x, z) -> mixed(6));
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());
        sim.engine.onBotTickStopped();
        sim.engine.shutdown();
        sim.newEngine();
        sim.start();
        List<Phase> seen = new ArrayList<>();
        assertTrue(sim.runUntil(() -> {
            seen.add(sim.phase());
            return waiting(sim);
        }, LONG), sim.state());
        assertTrue(seen.stream().noneMatch(MAP_PHASES::contains), seen.toString());
        assertTrue(sim.notifier.of("alert").stream().filter(m -> m.title().startsWith("Out of")).count() <= 2,
            "alerted again after the restart at most once more: " + sim.notifier);
        sim.bot.containers.get(LIME_CHEST).items.put(LIME, 1728);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.bot.placeCounts.values().stream().allMatch(n -> n == 1), "nothing rebuilt");
    }

    @Test
    void aRealShortageOfEverythingStillIdlesAsBefore() {
        Sim sim = sim(null);
        Job job = sim.addJob("j0904", "All lime", 1, 1, List.of("moss"), (x, z) -> Sim.single(LIME, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.WAITING_MATERIALS && sim.phase() == Phase.IDLE, LONG),
            sim.state());
        sim.run(MINUTE);
        assertEquals(0, sim.bot.placed, "nothing to build");
        assertEquals(0, sim.store.entries("GEAR"), sim.store.phaseLog.toString());
        assertTrue(sim.notifier.of("alert").getFirst().body().startsWith("Nothing to build: the queued map is short of materials. Put"
            + " them in the storage chests;"),
            sim.notifier.toString());
        assertEquals("Waiting for materials (1 job)", PresenceText.describe(PresenceText.input(sim.engine.status(),
            sim.store.list(), true, PresenceText.Link.IN_GAME, 0)).text());
    }

    @Test
    void theKillSwitchHoldsTheWholeJobAsBefore() {
        Sim sim = sim(null);
        sim.config.build.partialBuild = false;
        sim.config.build.skipShortTiles = false;
        Job a = sim.addJob("j0905", "Mixed", 1, 1, List.of("moss"), (x, z) -> mixed(10));
        Job b = sim.addJob("j0906", "Plain", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> b.status == JobStatus.DONE && sim.phase() == Phase.IDLE, LONG), sim.state());
        assertEquals(JobStatus.WAITING_MATERIALS, a.status);
        assertEquals("short: 320 lime carpet", a.statusDetail);
        assertEquals(256, sim.bot.placed, "only the plain job was built");
        var alerts = sim.notifier.of("alert");
        assertEquals(1, alerts.size(), "one ping, when nothing was left to build: " + sim.notifier);
        assertEquals("Out of lime carpet", alerts.getFirst().title());
    }

    @Test
    void aFreshTileBelowTheMinimumShareWaitsWhole() {
        Sim sim = sim(null);
        sim.bot.containers.get(LIME_CHEST).items.put(LIME, 64); // 64 of 256: a quarter
        Job job = sim.addJob("j0907", "Lime", 1, 1, List.of("moss"), (x, z) -> Sim.single(LIME, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.WAITING_MATERIALS && sim.phase() == Phase.IDLE, LONG),
            sim.state());
        assertEquals(0, sim.bot.placed);
        // with no minimum the same tile builds the quarter the stock covers, then waits on the platform
        Sim any = sim(null);
        any.config.build.partialBuildMinShare = 0;
        any.bot.containers.get(LIME_CHEST).items.put(LIME, 64);
        any.addJob("j0907", "Lime", 1, 1, List.of("moss"), (x, z) -> Sim.single(LIME, 2));
        any.start();
        assertTrue(any.runUntil(() -> waiting(any), LONG), any.state());
        assertEquals(64, any.bot.placed);
    }

    @Test
    void aTrickleOfTheMissingItemWaitsForTheRestInsteadOfATrip() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Job job = sim.addJob("j0916", "Trickle", 1, 1, List.of("moss"), (x, z) -> mixed(10));
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());
        int gears = sim.store.entries("GEAR");
        sim.bot.containers.get(LIME_CHEST).items.put(LIME, 40); // 40 of the 320 lime: below a stack, not worth a trip
        sim.run(5 * MINUTE);
        assertTrue(waiting(sim), sim.state());
        assertEquals(gears, sim.store.entries("GEAR"), "no trip for a few lime blocks: " + sim.store.phaseLog);
        assertEquals(0, placed(sim, 10)[0]);
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.contains("40 blocks in stock: not worth a trip until 64"
            + " blocks or all of an item is there")), log.lines.toString());
        sim.bot.containers.get(LIME_CHEST).items.put(LIME, 320); // all of it: one trip places it
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(gears + 1, sim.store.entries("GEAR"), "one more trip: " + sim.store.phaseLog);
    }

    /** {@code build.partialTripMinCells} 0: the rule before 2026-10-13, a full load or all of an item. */
    @Test
    void withNoTripMinimumOnlyAFullLoadOrAllOfAnItemIsWorthATrip() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.build.partialTripMinCells = 0;
        Job job = sim.addJob("j0923", "Old rule", 1, 1, List.of("moss"), (x, z) -> mixed(10));
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());
        int gears = sim.store.entries("GEAR");
        sim.bot.containers.get(LIME_CHEST).items.put(LIME, 100); // 100 of the 320 lime: below a full load
        sim.run(5 * MINUTE);
        assertTrue(waiting(sim), sim.state());
        assertEquals(gears, sim.store.entries("GEAR"), "no trip below a full load: " + sim.store.phaseLog);
        assertEquals(0, placed(sim, 10)[0]);
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.contains("100 blocks in stock: not worth a trip until a"
            + " load's worth or all of an item is there")), log.lines.toString());
        sim.bot.containers.get(LIME_CHEST).items.put(LIME, 320);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(gears + 1, sim.store.entries("GEAR"), "one more trip: " + sim.store.phaseLog);
    }

    /** RESTOCK rounds: one per trip to the chests. */
    private static int trips(Sim sim) {
        return sim.store.entries("RESTOCK");
    }

    @Test
    void aTileShortOfOneItemTakesAboutAsManyTripsAsAFullyStockedOne() {
        TileArt full = mixed(40); // 5120 cells, 1280 lime
        TileArt rest = new TileArt(); // the same without the lime cells
        for (int pz = 0; pz < 40; pz++)
            for (int px = 0; px < 128; px++) if (px % 4 != 3) rest.set(px, pz, full.get(px, pz));
        // fully stocked, whole tile
        Sim a = sim(null);
        a.bot.containers.get(LIME_CHEST).items.put(LIME, 1728);
        Job ja = a.addJob("j0917", "Full", 1, 1, List.of("moss"), (x, z) -> full);
        a.start();
        assertTrue(a.runUntil(() -> ja.status == JobStatus.DONE, LONG), a.state());
        // fully stocked, the tile without its lime cells
        Sim r = sim(null);
        Job jr = r.addJob("j0918", "Rest", 1, 1, List.of("moss"), (x, z) -> rest);
        r.start();
        assertTrue(r.runUntil(() -> jr.status == JobStatus.DONE, LONG), r.state());
        // no lime at the base: the rest is built, then the lime once it comes
        Sim b = sim(null);
        Job jb = b.addJob("j0919", "Short", 1, 1, List.of("moss"), (x, z) -> full);
        b.start();
        assertTrue(b.runUntil(() -> waiting(b), LONG), b.state());
        int before = trips(b);
        b.bot.containers.get(LIME_CHEST).items.put(LIME, 1728);
        assertTrue(b.runUntil(() -> jb.status == JobStatus.DONE, LONG), b.state());
        int after = trips(b) - before;
        System.out.println("[partial build] trips (RESTOCK rounds): fully stocked " + trips(a) + "; the rest alone "
            + trips(r) + "; short of lime " + before + " before the wait + " + after + " after the lime came");
        assertTrue(before <= trips(r), "the rest takes no more trips than the same cells fully stocked: " + before
            + " vs " + trips(r));
        assertTrue(before + after <= trips(a) + 2, "in all about as many as fully stocked: " + (before + after)
            + " vs " + trips(a));
    }

    // ---- stock-aware SELECT (build.skipShortTiles) ----------------------------------------------------

    /** Names of the stored maps, in the order they reached the output chest. */
    private static List<String> storedOrder(Sim sim) {
        List<String> out = new ArrayList<>();
        for (FakeBot.MapItem m : sim.output().maps) out.add(m.name());
        return out;
    }

    @Test
    void aShortMapIsSkippedForAFullyStockedLaterMapOfTheSameJobThenFinished() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        // ellen-like: map 2 needs lime (none at the base), maps 1 and 3 are fully stocked
        Job job = sim.addJob("j0911", "Ellen", 3, 1, List.of("moss"),
            (x, z) -> x == 1 ? mixed(6) : x == 0 ? Sim.stripes(4, 0) : Sim.single(RED, 3));
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());
        assertEquals(List.of("Ellen 0,0", "Ellen 2,0"), storedOrder(sim), "map 3 before the short map 2");
        assertSame(job.tiles.get(1), job.tiles.stream().filter(t -> t.status == TileStatus.ACTIVE).findFirst().orElseThrow());
        assertEquals(576, placed(sim, 6)[1], "map 2 partly built once nothing else is buildable");
        var alerts = sim.notifier.of("alert");
        assertEquals(1, alerts.size(), "one ping, once map 2 waits with nothing else to build: " + sim.notifier);
        assertTrue(alerts.getFirst().body().startsWith("Ellen tile 1,0 needs 192 more lime carpet; no chest at this base"
            + " holds it. Put 3 stacks in a chest on the moss platform."),
            alerts.getFirst().body());
        assertTrue(alerts.getFirst().body().contains("Everything else on the tile is built (576 of 768)."), alerts.getFirst().body());
        // map 3 was taken instead of map 2: its start line says so
        assertTrue(sim.notifier.of("info").stream().anyMatch(m -> m.title().equals(
            "Started Ellen: tile 2,0 (1 of 3 maps done) instead of tile 1,0")
            && m.body().contains("Skipped for now: tile 1,0 needs 192 more lime carpet.")), sim.notifier.toString());
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.contains("[2,0] is fully stocked")), log.lines.toString());
        // the lime arrives: map 2 is finished, the job is done once
        sim.bot.containers.get(LIME_CHEST).items.put(LIME, 1728);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(List.of("Ellen 0,0", "Ellen 2,0", "Ellen 1,0"), storedOrder(sim));
        var mapDone = sim.notifier.messages.stream().filter(m -> m.title().startsWith("Map done")).toList();
        assertEquals(3, mapDone.size(), sim.notifier.toString());
        assertTrue(mapDone.get(1).body().contains("2 of 3 maps done"), mapDone.get(1).body());
        assertTrue(mapDone.get(2).body().contains("3 of 3 maps done"), mapDone.get(2).body());
        var jobDone = sim.notifier.messages.stream().filter(m -> m.title().startsWith("Job done")).toList();
        assertEquals(1, jobDone.size(), sim.notifier.toString());
        assertTrue(jobDone.getFirst().body().contains("Maps: Ellen 0,0, Ellen 1,0, Ellen 2,0"), jobDone.getFirst().body());
        assertTrue(job.tiles.stream().allMatch(t -> t.status == TileStatus.STORED && t.startedAt > 0
            && t.finishedAt >= t.startedAt));
    }

    @Test
    void aFullyStockedLaterJobGoesBeforeAShortEarlierOne() {
        Sim sim = sim(null);
        Job a = sim.addJob("j0912", "Short", 1, 1, List.of("moss"), (x, z) -> mixed(6));
        Job b = sim.addJob("j0913", "Stocked", 1, 1, List.of("moss"), (x, z) -> Sim.single(BLACK, 2));
        sim.start();
        int[] before = {-1};
        assertTrue(sim.runUntil(() -> {
            if (b.status == JobStatus.DONE && before[0] < 0) before[0] = sim.bot.placed;
            return waiting(sim);
        }, LONG), sim.state());
        assertEquals(256, before[0], "only B's blocks were placed before B was done");
        assertEquals(a.id, sim.engine.status().jobId(), "then A is built as far as the stock allows");
        assertEquals(576, placed(sim, 6)[1]);
    }

    @Test
    void withEveryMapShortTheOneWithTheMostBlocksInStockIsBuiltPartly() {
        Sim sim = sim(null);
        // map 1: 3 of 4 columns lime (the base has none); map 2: 1 of 4 lime, so more of it is in stock
        TileArt mostlyLime = new TileArt();
        for (int pz = 0; pz < 6; pz++)
            for (int px = 0; px < 128; px++) mostlyLime.set(px, pz, BlockSpec.of(px % 4 == 0 ? WHITE : LIME));
        Job job = sim.addJob("j0914", "AllShort", 2, 1, List.of("moss"), (x, z) -> x == 0 ? mostlyLime : mixed(6));
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());
        assertEquals(1, sim.engine.status().tileX(), "map 2 (576 of 768 in stock) before map 1 (192 of 768)");
        assertEquals(576, placed(sim, 6)[1]);
        assertEquals(TileStatus.PENDING, job.tiles.get(0).status, "map 1 holds no platform");
        assertTrue(sim.output().maps.isEmpty());
    }

    @Test
    void withoutStockAwareSelectionTheMapsAreBuiltInOrderAsBefore() {
        Sim sim = sim(null);
        sim.config.build.skipShortTiles = false;
        sim.config.build.partialBuild = false;
        Job job = sim.addJob("j0915", "InOrder", 3, 1, List.of("moss"),
            (x, z) -> x == 1 ? mixed(6) : x == 0 ? Sim.stripes(4, 0) : Sim.single(RED, 3));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.WAITING_MATERIALS && sim.phase() == Phase.IDLE, LONG),
            sim.state());
        sim.run(MINUTE);
        assertEquals(List.of("InOrder 0,0"), storedOrder(sim), "map 3 waits behind the short map 2");
        assertEquals(TileStatus.ACTIVE, job.tiles.get(1).status, "map 2 stays the job's current map, as before");
        assertEquals(TileStatus.PENDING, job.tiles.get(2).status);
        assertEquals(0, placed(sim, 6)[1]);
    }

    @Test
    void aMapSetAsideForAnotherIsNamedInThatMapsStartLineAndOnlyNothingToBuildPings() {
        Sim sim = sim(null);
        // map 1 needs lime (none at the base), map 2 is fully stocked; the stock index is empty at the first SELECT, so
        // map 1 is tried first and set aside by STOCK_CHECK (live 2026-10-12: ellen map 16 for map 12)
        Job job = sim.addJob("j0920", "Pair", 2, 1, List.of("moss"), (x, z) -> x == 0 ? mixed(6) : Sim.single(RED, 3));
        sim.start();
        assertTrue(sim.runUntil(() -> job.tiles.get(1).status == TileStatus.STORED, LONG), sim.state());
        assertTrue(sim.notifier.of("alert").isEmpty(), "set aside while another map is built: no ping " + sim.notifier);
        var start = sim.notifier.of("info").stream().filter(m -> m.title().startsWith("Started Pair: tile 1,0"))
            .findFirst().orElseThrow();
        assertEquals("Started Pair: tile 1,0 (0 of 2 maps done) instead of tile 0,0", start.title());
        assertTrue(start.body().contains("\nSkipped for now: tile 0,0 needs 192 more lime carpet.\nIt is checked"
            + " again when the next map is chosen."), start.body());
        assertTrue(sim.notifier.messages.stream().noneMatch(m -> m.title().startsWith("Started Pair: tile 0,0")
            && sim.notifier.messages.indexOf(m) < sim.notifier.messages.indexOf(start)),
            "map 1 was not announced as started before it was set aside: " + sim.notifier);
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());
        assertTrue(sim.notifier.of("info").stream().anyMatch(m -> m.title().equals("Started Pair: tile 0,0, partly (1 of 2 maps done)")),
            sim.notifier.toString());
        var alerts = sim.notifier.of("alert");
        assertEquals(1, alerts.size(), sim.notifier.toString());
        assertEquals("Out of lime carpet", alerts.getFirst().title());
        for (var m : sim.notifier.messages) {
            assertFalse(m.body().contains("minecraft:") || m.body().contains("→") || m.title().contains("minecraft:"),
                "human wording: " + m);
        }
    }

    @Test
    void severalMapsSkippedBeforeOneIsChosenAreListedInItsStartLine() {
        Sim sim = sim(null);
        Job job = sim.addJob("j0921", "Row", 3, 1, List.of("moss"),
            (x, z) -> x == 2 ? Sim.single(RED, 2) : x == 0 ? mixed(6) : Sim.single(LIME, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> job.tiles.get(2).status == TileStatus.STORED, LONG), sim.state());
        var start = sim.notifier.of("info").stream().filter(m -> m.title().startsWith("Started Row: tile 2,0"))
            .findFirst().orElseThrow();
        assertEquals("Started Row: tile 2,0 (0 of 3 maps done) instead of tile 0,0 and tile 1,0", start.title());
        assertTrue(start.body().contains("Skipped for now: tile 0,0 needs 192 more lime carpet."), start.body());
        assertTrue(start.body().contains("Skipped for now: tile 1,0 needs 128 more lime carpet."), start.body());
        assertTrue(start.body().endsWith("They are checked again when the next map is chosen."), start.body());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void nothingToBuildListsTheWaitingMapsAndTheJobsShoppingListOnce() {
        Sim sim = sim(null);
        sim.config.build.waitingRecheckMinutes = 1;
        Job job = sim.addJob("j0922", "Limes", 2, 1, List.of("moss"), (x, z) -> Sim.single(LIME, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.WAITING_MATERIALS && sim.phase() == Phase.IDLE, LONG),
            sim.state());
        sim.run(5 * MINUTE); // re-checks: no second ping for the same items
        var alerts = sim.notifier.of("alert");
        assertEquals(1, alerts.size(), sim.notifier.toString());
        assertEquals("Out of lime carpet", alerts.getFirst().title());
        // both maps need 256: materials come back after each map's clear, so the job needs no more than the map line
        assertEquals("Nothing to build: the queued map is short of materials. Put them in the storage chests; the bot"
            + " looks again every 1 min.\nLimes tile 0,0 needs:\n- lime carpet: 256 more needed (4 stacks; no chest at this"
            + " base holds it)",
            alerts.getFirst().body());
        assertEquals(0, sim.bot.placed);
        assertTrue(sim.notifier.of("info").stream().noneMatch(m -> m.title().startsWith("Started")),
            "nothing started: " + sim.notifier);
    }

    @Test
    void theJobsRestockListIsTheLargestMapLeftNotTheSum() {
        // live 2026-10-15 (Galileo, 78 maps left): the list summed every map left (359,465 black carpet); materials are
        // reused map to map, so it is the largest map's need less what is on hand
        Sim sim = sim(null);
        sim.config.build.waitingRecheckMinutes = 1;
        Job job = sim.addJob("j0923", "Steps", 3, 1, List.of("moss"),
            (x, z) -> x == 1 ? Sim.single(LIME, 3) : Sim.single(LIME, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.WAITING_MATERIALS && sim.phase() == Phase.IDLE, LONG),
            sim.state());
        var alerts = sim.notifier.of("alert");
        assertEquals(1, alerts.size(), sim.notifier.toString());
        String body = alerts.getFirst().body();
        assertTrue(body.contains("\nFor the rest of Steps (3 maps left; each map's blocks come back after its clear, so"
            + " this is for the largest map left), restock:\n- lime carpet: 384 more needed"), body);
        assertFalse(body.contains("896"), body); // the old sum: 256 + 384 + 256
    }

    @Test
    void largestMapNeedsIsThePerItemMaximumOverTheMapsLeft() {
        // live 2026-10-15: !map stock compared the stock with the summed materials of every queued map
        Sim sim = sim(null);
        Job job = sim.addJob("j0924", "Steps", 3, 1, List.of("moss"),
            (x, z) -> x == 1 ? Sim.single(LIME, 3) : Sim.single(LIME, 2));
        sim.start();
        Map<String, Integer> peak = sim.engine.largestMapNeeds(List.of(job));
        assertEquals(384, peak.get(LIME), peak.toString());
        job.tiles.get(1).status = TileStatus.STORED; // the big map is done: the largest left is 256
        assertEquals(256, sim.engine.largestMapNeeds(List.of(job)).get(LIME));
    }

    @Test
    void shortageText() {
        assertEquals("cyan terracotta", ShortageText.item("minecraft:cyan_terracotta"));
        assertEquals("199 more cyan terracotta", ShortageText.needs(Map.of("minecraft:cyan_terracotta", 199)));
        assertEquals("1,122 more cyan terracotta and 1 more crimson nylium",
            ShortageText.needs(Map.of("minecraft:crimson_nylium", 1, "minecraft:cyan_terracotta", 1122)));
        assertEquals("40 more a, 30 more b, 20 more c and 1 more materials",
            ShortageText.needs(Map.of("a", 40, "b", 30, "c", 20, "d", 10)));
        assertEquals("\n- cyan terracotta: 199 more needed (about 4 stacks)\n- crimson nylium: 1 more needed (no chest at this base holds it)",
            ShortageText.lines(Map.of("minecraft:cyan_terracotta", 199, "minecraft:crimson_nylium", 1),
                Set.of("minecraft:crimson_nylium")));
        Job j = new Job();
        j.id = "j0002";
        j.name = "ellen";
        for (int i = 0; i < 28; i++) {
            Job.TileState t = new Job.TileState();
            t.x = i % 4;
            t.z = i / 4;
            j.tiles.add(t);
        }
        Job.TileState t16 = j.tiles.get(15);
        // by tile, never by index: "map 16 of 28" read as 15 maps done while maps are built out of order
        assertEquals("tile 3,3", ShortageText.mapOf(j, t16));
        assertEquals("ellen tile 3,3", ShortageText.fullMap(j, t16));
        assertEquals("tile 3,3", ShortageText.mapIn(j, j, t16));
        assertEquals("0 of 28 maps done", ShortageText.done(j));
        for (int i = 20; i < 26; i++) j.tiles.get(i).status = io.github.digitalisp.mapart.model.TileStatus.STORED;
        assertEquals("6 of 28 maps done", ShortageText.done(j));
        Job solo = new Job();
        solo.id = "j0009";
        solo.name = "Solo";
        solo.tiles.add(new Job.TileState());
        assertEquals("the map", ShortageText.mapOf(solo, solo.tiles.getFirst()));
        assertEquals("Solo", ShortageText.fullMap(solo, solo.tiles.getFirst()));
    }

    // ---- the pure rules ---------------------------------------------------------------------------

    @Test
    void decide() {
        assertEquals(PartialBuild.Decision.BUILD, PartialBuild.decide(true, 0.5, 100, 0, false, true));
        assertEquals(PartialBuild.Decision.BUILD, PartialBuild.decide(false, 0.5, 100, 0, true, true));
        assertEquals(PartialBuild.Decision.HOLD, PartialBuild.decide(false, 0.5, 100, 1, true, true), "kill switch");
        assertEquals(PartialBuild.Decision.PARTIAL, PartialBuild.decide(true, 0.5, 100, 50, false, false), "half covered");
        assertEquals(PartialBuild.Decision.HOLD, PartialBuild.decide(true, 0.5, 100, 51, false, true), "below the share");
        assertEquals(PartialBuild.Decision.PARTIAL, PartialBuild.decide(true, 0.5, 100, 99, true, true),
            "started: anything worth a trip");
        assertEquals(PartialBuild.Decision.WAIT, PartialBuild.decide(true, 0.5, 100, 99, true, false), "started: no trickling");
        assertEquals(PartialBuild.Decision.PARTIAL, PartialBuild.decide(true, 0, 100, 99, false, false), "no minimum");
        assertEquals(PartialBuild.Decision.HOLD, PartialBuild.decide(true, 0, 100, 100, false, true), "nothing coverable");
        assertEquals(PartialBuild.Decision.WAIT, PartialBuild.decide(true, 0.5, 100, 100, true, true), "started: wait on it");
    }

    @Test
    void worthATrip() {
        int load = PartialBuild.loadCells(5);
        assertEquals(31 * 64, load);
        // the rule before 2026-10-13 (no minimum): 100 more of 320 lime is a trickle, all 320 is worth the trip
        assertFalse(PartialBuild.worthATrip(Map.of(LIME, 320), Map.of(LIME, 220), load, 0));
        assertTrue(PartialBuild.worthATrip(Map.of(LIME, 3000), Map.of(LIME, 1000), load, 0), "a full load's worth");
        assertTrue(PartialBuild.worthATrip(Map.of(LIME, 320, WHITE, 5), Map.of(LIME, 320), load, 0), "all of white");
        assertFalse(PartialBuild.worthATrip(Map.of(LIME, 320, WHITE, 5), Map.of(LIME, 320, WHITE, 1), load, 0));
        // build.partialTripMinCells (default 64, one stack): 100 lime is worth the trip, 63 is not
        assertTrue(PartialBuild.worthATrip(Map.of(LIME, 320), Map.of(LIME, 220), load, 64), "100 >= 64");
        assertTrue(PartialBuild.worthATrip(Map.of(LIME, 320), Map.of(LIME, 256), load, 64), "exactly a stack");
        assertFalse(PartialBuild.worthATrip(Map.of(LIME, 320), Map.of(LIME, 257), load, 64), "63 < 64");
        assertTrue(PartialBuild.worthATrip(Map.of(LIME, 3000), Map.of(LIME, 1000), load, 5000), "a minimum above a"
            + " load: the full load still counts");
        assertEquals(64, PartialBuild.tripCells(load, 64));
        assertEquals(load, PartialBuild.tripCells(load, 0));
        assertEquals(load, PartialBuild.tripCells(load, 5000));
    }

    @Test
    void carriedCellsArePerItemTheSmallerOfNeedAndInventory() {
        assertEquals(Map.of(LIME, 100), PartialBuild.carried(Map.of(LIME, 320, WHITE, 5), Map.of(LIME, 100)));
        assertEquals(Map.of(LIME, 320, WHITE, 5),
            PartialBuild.carried(Map.of(LIME, 320, WHITE, 5), Map.of(LIME, 999, WHITE, 64, RED, 64)));
        assertTrue(PartialBuild.carried(Map.of(LIME, 320), Map.of(RED, 64)).isEmpty(), "nothing needed is carried");
        assertTrue(PartialBuild.carried(Map.of(LIME, 320), Map.of(LIME, 0)).isEmpty());
    }

    @Test
    void withoutShortLeavesOutEachShortItemsLastCellsInBuildOrder() {
        Lane l0 = lane(0, WHITE, LIME, LIME, WHITE);
        Lane l1 = lane(1, LIME, RED, LIME);
        Lane l2 = lane(2, LIME, LIME);
        List<Lane> lanes = List.of(l0, l1, l2);
        assertSame(lanes, PartialBuild.withoutShort(lanes, Map.of()));
        List<Lane> out = PartialBuild.withoutShort(lanes, Map.of(LIME, 3)); // 6 lime cells: the first 3 stay
        assertEquals(2, out.size(), "the all-lime last lane is dropped");
        assertSame(l0, out.get(0), "an untouched lane stays as it is");
        assertEquals(List.of(LIME, RED), out.get(1).placements().stream().map(Placement::itemId).toList());
        assertEquals(l1.from(), out.get(1).from());
        List<Lane> none = PartialBuild.withoutShort(lanes, Map.of(LIME, 99));
        assertEquals(2, none.size());
        assertTrue(none.stream().flatMap(l -> l.placements().stream()).noneMatch(p -> p.itemId().equals(LIME)));
        assertSame(lanes, PartialBuild.withoutShort(lanes, Map.of(BLACK, 5)), "an item no lane uses changes nothing");
        assertEquals("1,122 cyan terracotta, 1 crimson nylium",
            PartialBuild.items(Map.of("minecraft:crimson_nylium", 1, "minecraft:cyan_terracotta", 1122)));
        assertEquals(Map.of(WHITE, 4, LIME, 1), PartialBuild.minus(Map.of(WHITE, 4, LIME, 3, RED, 2), Map.of(LIME, 2, RED, 2)));
        assertNotNull(PartialBuild.minus(Map.of(), Map.of(LIME, 1)));
        assertFalse(PartialBuild.minus(Map.of(LIME, 1), Map.of(LIME, 1)).containsKey(LIME));
    }

    private static Lane lane(int index, String... items) {
        List<Placement> ps = new ArrayList<>();
        for (int i = 0; i < items.length; i++)
            ps.add(new Placement(new Pos(index, 64, i), BlockSpec.of(items[i]), items[i]));
        return new Lane(index, new Pos(index, 64, 0), new Pos(index, 64, items.length - 1), List.copyOf(ps));
    }
}
