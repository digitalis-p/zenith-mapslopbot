package io.github.digitalisp.mapart.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.engine.EngineControl;
import io.github.digitalisp.mapart.engine.EngineControl.EngineStatus;
import io.github.digitalisp.mapart.engine.Phase;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.stock.StockIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.digitalisp.mapart.command.Fakes.job;
import static io.github.digitalisp.mapart.command.Fakes.materials;

class QueueOpsTest {
    static final long NOW = 1_000_000_000L;

    Fakes.Store store;
    Fakes.Engine engine;
    QueueOps ops;

    @BeforeEach
    void setUp() {
        store = new Fakes.Store();
        engine = new Fakes.Engine();
        ops = new QueueOps(store, () -> engine, () -> NOW);
    }

    private void fill() {
        store.add(job("j0001", "Old", JobStatus.DONE, 1, 1, Map.of()))
            .add(job("j0002", "Pine Tree", JobStatus.ACTIVE, 2, 1, materials("minecraft:white_carpet", 1000)))
            .add(job("j0003", "Cancelled", JobStatus.CANCELLED, 1, 1, Map.of()))
            .add(job("j0004", "sizedreisen", JobStatus.QUEUED, 4, 2, materials("minecraft:white_carpet", 500, "minecraft:black_carpet", 300)))
            .add(job("j0005", "Short", JobStatus.WAITING_MATERIALS, 1, 1, materials("minecraft:red_carpet", 50)));
        store.jobs.get(4).statusDetail = "short: red_carpet x50";
        store.jobs.get(1).tiles.get(0).status = TileStatus.STORED;
    }

    @Test
    void positionsCountOnlyPendingJobs() {
        fill();
        assertEquals(1, ops.position("j0002"));
        assertEquals(2, ops.position("j0004"));
        assertEquals(3, ops.position("j0005"));
        assertEquals(0, ops.position("j0001"));
        assertEquals(0, ops.position("nope"));
    }

    @Test
    void queueRowsAndFormatting() {
        fill();
        engine.status = new EngineStatus(true, false, "", Phase.BUILD, "j0002", "Pine Tree", 1, 0, "moss",
            4096, 16384, "", NOW - 120_000);
        List<QueueOps.QueueRow> rows = ops.queueRows();
        assertEquals(3, rows.size());
        assertEquals(new QueueOps.QueueRow(1, "j0002", "Pine Tree", 2, 1, JobStatus.ACTIVE, 1, 2,
            "Printing · map 2 of 2 (25%) · 1 map done · on moss", "jared2013"), rows.get(0));

        // one short block per job, no table: readable on Discord's phone app (the owner, 2026-10-28)
        assertEquals("""
            **1. Pine Tree** (jared2013)
            `j0002` · 2x1 · ACTIVE · 1/2 maps
            Printing · map 2 of 2 (25%) · 1 map done · on moss

            **2. sizedreisen** (jared2013)
            `j0004` · 4x2 · QUEUED · 0/8 maps

            **3. Short** (jared2013)
            `j0005` · 1x1 · WAITING · 0/1 map
            short: red\\_carpet x50

            3 pending, 1 active, 1 waiting for materials.
            **Recently finished:**
            `j0003` Cancelled · CANCELLED
            `j0001` Old · DONE""", QueueOps.formatQueue(rows, ops.recentFinished(QueueOps.RECENT)));
    }

    @Test
    void emptyQueue() {
        assertEquals("Queue is empty.", QueueOps.formatQueue(List.of(), List.of()));
        assertEquals("Queue is empty.", ops.queue().body());
    }

    @Test
    void resolveByExactCaseAndNumber() {
        fill();
        assertEquals("j0004", ops.resolve("j0004").orElseThrow().id);
        assertEquals("j0004", ops.resolve("J0004").orElseThrow().id);
        assertEquals("j0004", ops.resolve("4").orElseThrow().id);
        assertEquals("j0004", ops.resolve("`j0004`").orElseThrow().id);
        assertTrue(ops.resolve("99").isEmpty());
        assertTrue(ops.resolve("").isEmpty());
        assertTrue(ops.resolve(null).isEmpty());
    }

    @Test
    void cancel() {
        fill();
        Reply r = ops.cancel("j0002");
        assertEquals(Reply.Tone.SUCCESS, r.tone());
        assertTrue(r.body().contains("active"), r.body());
        assertEquals(JobStatus.CANCELLED, store.get("j0002").orElseThrow().status);
        assertEquals(List.of("queueChanged"), engine.calls);

        assertTrue(ops.cancel("j0001").isError());
        assertTrue(ops.cancel("zzz").isError());
        assertEquals(1, engine.calls.size());
    }

    @Test
    void renameKeepsBracketsAndRomanNumerals() {
        fill();
        Reply r = ops.rename("j0002", "Ａｂａｄｄｏｎ 〘 Last Art Ⅱ 〙");
        assertEquals(Reply.Tone.SUCCESS, r.tone());
        assertEquals("Ａｂａｄｄｏｎ 〘 Last Art Ⅱ 〙", store.get("j0002").orElseThrow().name);
        assertTrue(ops.rename("j0002", "<@123456789012345678> ``").isError());
        assertEquals("Ａｂａｄｄｏｎ 〘 Last Art Ⅱ 〙", store.get("j0002").orElseThrow().name);
        assertTrue(ops.rename("zzz", "x").isError());
    }

    @Test
    void cancelOfABlockedJobSaysWhenThePlatformIsCleared() {
        fill();
        engine.status = new EngineStatus(true, false, "", Phase.BLOCKED, "j0002", "Pine Tree", 1, 0, "moss",
            4096, 16384, "LOCK: no glass panes", NOW - 120_000);
        String body = ops.cancel("j0002").body();
        assertTrue(body.contains("leaves BLOCKED and clears the platform now"), body);
        assertFalse(body.contains("after resume"), body);

        store.get("j0004").orElseThrow().status = JobStatus.ACTIVE;
        engine.status = new EngineStatus(true, true, "lunch", Phase.BLOCKED, "j0004", "sizedreisen", 0, 0, "moss",
            0, 16384, "", NOW - 120_000);
        body = ops.cancel("j0004").body();
        assertTrue(body.contains("The engine is paused: it will clear the platform after resume."), body);

        store.get("j0005").orElseThrow().status = JobStatus.ACTIVE;
        engine.status = new EngineStatus(true, true, "SAFETY STOP: the bot died", Phase.BUILD, "j0005", "Short", 0, 0,
            "moss", 0, 16384, "", NOW - 120_000);
        body = ops.cancel("j0005").body();
        assertTrue(body.contains("safety-stopped: it will clear the platform after resume."), body);
    }

    @Test
    void moveUsesOneBasedPositionsAndClamps() {
        fill();
        Reply r = ops.move("j0005", 1);
        assertFalse(r.isError(), r.body());
        assertEquals(1, ops.position("j0005"));
        assertEquals("Moved j0005 to #1", r.title());
        assertTrue(r.body().contains("active job keeps building"), r.body());

        r = ops.move("j0005", 99);
        assertEquals("Moved j0005 to #3", r.title());
        assertTrue(ops.move("j0005", 0).isError());
        assertTrue(ops.move("j0003", 1).isError());
        assertEquals(2, engine.calls.stream().filter("queueChanged"::equals).count());
    }

    @Test
    void retryRequeuesFailedAndWaiting() {
        fill();
        Job w = store.get("j0005").orElseThrow();
        w.tiles.get(0).status = TileStatus.FAILED;
        Reply r = ops.retry("j0005");
        assertEquals(Reply.Tone.SUCCESS, r.tone());
        assertEquals(JobStatus.QUEUED, w.status);
        assertEquals("", w.statusDetail);
        assertEquals(TileStatus.PENDING, w.tiles.get(0).status);
        assertTrue(r.body().contains("1 failed tile(s) reset"), r.body());
        assertEquals(1, store.saves);
        assertEquals(List.of("queueChanged"), engine.calls);

        assertTrue(ops.retry("j0004").isError());  // QUEUED
        assertTrue(ops.retry("j0001").isError());  // DONE
    }

    @Test
    void engineActions() {
        assertEquals(Reply.Tone.WARN, ops.pause("lunch").tone());
        ops.resume();
        ops.scan();
        assertEquals(List.of("pause:lunch", "resume", "scan"), engine.calls);
        ops.pause("  ");
        assertEquals("pause:paused by command", engine.calls.get(3));

        QueueOps noEngine = new QueueOps(store, () -> null, () -> NOW);
        assertTrue(noEngine.pause("x").isError());
        assertTrue(noEngine.resume().isError());
        assertTrue(noEngine.scan().isError());
        assertEquals(Reply.Tone.WARN, noEngine.status().tone());
    }

    @Test
    void rocketChestReportComesFromTheEngineAndIsNullSafe() {
        assertEquals("", ops.rocketChestReport(), "the default: nothing to say");
        assertEquals("", new QueueOps(store, () -> null, () -> NOW).rocketChestReport());
        RecordingEngine found = new RecordingEngine(new ArrayList<>()) {
            @Override public String rocketChestReport() { return "Rocket chest (build.elytraLegs on): listed at 1,2,3"; }
        };
        assertEquals("Rocket chest (build.elytraLegs on): listed at 1,2,3",
            new QueueOps(store, () -> found, () -> NOW).rocketChestReport());
        RecordingEngine broken = new RecordingEngine(new ArrayList<>()) {
            @Override public String rocketChestReport() { throw new IllegalStateException("engine broke"); }
        };
        assertEquals("", new QueueOps(store, () -> broken, () -> NOW).rocketChestReport());
    }

    @Test
    void mapCounterButtonComesFromTheEngineAndIsNullSafe() {
        assertNull(ops.mapCounterButton(), "the default: not found");
        assertNull(new QueueOps(store, () -> null, () -> NOW).mapCounterButton());
        Pos button = new Pos(12, 65, 1);
        RecordingEngine found = new RecordingEngine(new ArrayList<>()) {
            @Override public Pos mapCounterButton() { return button; }
        };
        assertEquals(button, new QueueOps(store, () -> found, () -> NOW).mapCounterButton());
        RecordingEngine broken = new RecordingEngine(new ArrayList<>()) {
            @Override public Pos mapCounterButton() { throw new IllegalStateException("engine broke"); }
        };
        assertNull(new QueueOps(store, () -> broken, () -> NOW).mapCounterButton());
        assertNull(new QueueOps(store, () -> { throw new IllegalStateException("no engine yet"); }, () -> NOW)
            .mapCounterButton());
    }

    @Test
    void pauseSaysWhenItActsAndPointsToStop() {
        Reply r = ops.pause("lunch", "!map");
        assertEquals(Reply.Tone.WARN, r.tone());
        assertTrue(r.body().contains("after its current action, typically within one tick"), r.body());
        assertTrue(r.body().contains("For an immediate halt use `!map stop`."), r.body());
        assertTrue(r.body().contains("`!map resume` continues."), r.body());
        assertFalse(r.body().contains("safe point"), r.body());
        assertTrue(ops.pause("x").body().contains("use `stop`"), "no prefix: bare sub-command");
    }

    @Test
    void stopHaltsAtOnceAndSaysSo() {
        Reply r = ops.stop("jared2013 (Discord)", false, "!map");
        assertEquals(List.of("stop:manual stop by jared2013 (Discord)"), engine.calls);
        assertEquals("STOPPED", r.title());
        assertTrue(r.body().startsWith("Bot halted, Baritone stopped, pause saved. `!map resume` to continue."),
            r.body());
        assertFalse(r.isError());
        assertTrue(ops.stop("  ", false, "mapart").body().contains("`mapart resume` to continue."));
        assertEquals("stop:manual stop by command", engine.calls.get(1));
    }

    @Test
    void stopDisconnectHaltsFirstThenDisconnects() {
        List<String> order = new ArrayList<>();
        EngineControl recording = new RecordingEngine(order);
        QueueOps o = new QueueOps(store, () -> recording, () -> NOW, () -> {
            order.add("disconnect");
            return "Disconnected from the server. No auto-reconnect.";
        }, Runnable::run);
        Reply r = o.stop("jared2013 (Discord)", true, "!map");
        assertEquals(List.of("stop:manual stop by jared2013 (Discord)", "disconnect"), order);
        assertEquals("STOPPED", r.title());
        assertTrue(r.body().contains("Disconnected from the server. No auto-reconnect."), r.body());

        order.clear();
        o.stop("jared2013", false, "!map");
        assertEquals(List.of("stop:manual stop by jared2013"), order, "plain stop never disconnects");
    }

    @Test
    void stopDisconnectWorksWithoutAnEngine() {
        List<String> order = new ArrayList<>();
        QueueOps o = new QueueOps(store, () -> null, () -> NOW, () -> {
            order.add("disconnect");
            return "Already disconnected; no reconnect pending.";
        }, Runnable::run);
        Reply r = o.stop("jared2013", true, "!map");
        assertEquals(List.of("disconnect"), order);
        assertTrue(r.isError());
        assertEquals("STOP INCOMPLETE", r.title());
        assertTrue(r.body().contains("Engine not loaded"), r.body());
        assertTrue(r.body().contains("Already disconnected"), r.body());
    }

    @Test
    void stuckEngineHoldsNeitherTheReplyNorTheDisconnect() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch halted = new CountDownLatch(1);
        List<String> order = new ArrayList<>();
        EngineControl stuck = new RecordingEngine(order) {
            @Override
            public void emergencyStop(String reason) {
                try {
                    release.await(10, TimeUnit.SECONDS); // a tick that holds the engine
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                halted.countDown();
            }
        };
        QueueOps o = new QueueOps(store, () -> stuck, () -> NOW, () -> {
            order.add("disconnect");
            return "Disconnected from the server. No auto-reconnect.";
        }, Runnable::run, 100);
        long t0 = System.nanoTime();
        Reply r = o.stop("jared2013", true, "!map");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 2_000, "reply took " + ms + " ms");
        assertEquals(List.of("disconnect"), order, "disconnect still happens");
        assertEquals("STOP INCOMPLETE", r.title());
        assertTrue(r.body().contains("did not confirm the halt within 0.1 s"), r.body());
        assertTrue(r.body().contains("Next: ZenithProxy `disconnect`"), r.body());

        release.countDown();
        assertTrue(halted.await(5, TimeUnit.SECONDS), "the late halt still runs");
        Reply plain = o.stop("jared2013", false, "!map");
        assertEquals("STOPPED", plain.title(), plain.body());
    }

    @Test
    void stopRunsOnTheStopThreadAndDisconnectsOnlyAfterIt() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        ExecutorService tick = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "fake-tick-loop"));
        try {
            EngineControl e = new RecordingEngine(order) {
                @Override
                public void emergencyStop(String reason) {
                    order.add("stop on " + Thread.currentThread().getName());
                }
            };
            QueueOps o = new QueueOps(store, () -> e, () -> NOW, () -> {
                order.add("disconnect on " + (Thread.currentThread().getName().equals("fake-tick-loop")
                    ? "the tick loop" : "another thread"));
                return "Disconnected from the server. No auto-reconnect.";
            }, tick);
            Reply r = o.stop("jared2013", true, "!map");
            assertEquals("STOPPED", r.title(), r.body());
            assertEquals(List.of("stop on fake-tick-loop", "disconnect on another thread"), order);
        } finally {
            tick.shutdownNow();
        }
    }

    @Test
    void stuckTickLoopStillRepliesAndDisconnects() {
        List<String> order = new ArrayList<>();
        // a loop that never gets to the queued stop (a tick that never ends)
        Executor neverRuns = r -> { };
        QueueOps o = new QueueOps(store, () -> engine, () -> NOW, () -> {
            order.add("disconnect");
            return "Disconnected from the server. No auto-reconnect.";
        }, neverRuns, 100);
        Reply r = o.stop("jared2013", true, "!map");
        assertEquals("STOP INCOMPLETE", r.title());
        assertEquals(List.of("disconnect"), order);
        assertTrue(r.body().contains("Disconnected from the server."), r.body());
        Reply plain = o.stop("jared2013", false, "!map");
        assertTrue(plain.body().contains("Next: `!map stop disconnect`"), plain.body());
    }

    /** Records pause/stop calls into a shared list (to check ordering against the server link). */
    static class RecordingEngine implements EngineControl {
        final List<String> order;

        RecordingEngine(List<String> order) {
            this.order = order;
        }

        @Override public void pause(String reason) { order.add("pause:" + reason); }
        @Override public void emergencyStop(String reason) { order.add("stop:" + reason); }
        @Override public void resume() { order.add("resume"); }
        @Override public EngineStatus status() { return null; }
        @Override public void requestStockScan() { }
        @Override public void queueChanged() { }
    }

    @Test
    void statusFormatting() {
        EngineStatus running = new EngineStatus(true, false, "", Phase.BUILD, "j0002", "Pine_Tree", 1, 0, "moss",
            7400, 16384, "lane 3", NOW - 12 * 60_000);
        assertEquals("""
            **State:** running
            **Doing:** Printing · 45%
            **Phase:** BUILD for 12m
            **Job:** `j0002` Pine\\_Tree on moss
            **Placed:** 7,400 / 16,384 (45.2%)
            **Note:** lane 3""", QueueOps.formatStatus(running, NOW));

        EngineStatus paused = new EngineStatus(true, true, "lunch", Phase.RESTOCK, null, null, 0, 0, null, 0, 0, "", 0);
        assertTrue(QueueOps.formatStatus(paused, NOW).startsWith("**State:** paused: lunch\n**Doing:** Restocking\n**Phase:** RESTOCK"));
        EngineStatus blocked = new EngineStatus(true, false, "", Phase.BLOCKED, null, null, 0, 0, null, 0, 0, "clear failed", 0);
        assertTrue(QueueOps.formatStatus(blocked, NOW).contains("BLOCKED"));
        EngineStatus off = new EngineStatus(false, false, "", Phase.IDLE, null, null, 0, 0, null, 0, 0, "", 0);
        assertTrue(QueueOps.formatStatus(off, NOW).startsWith("**State:** disabled"));

        engine.status = blocked;
        assertEquals(Reply.Tone.WARN, ops.status().tone());
        engine.status = running;
        fill();
        Reply r = ops.status();
        assertEquals(Reply.Tone.INFO, r.tone());
        assertTrue(r.body().contains("\n**Queue:** 3 pending (1 waiting for materials)\n"), r.body());
        // which build runs: version, commit, build time (from the jar; tests have no build-info file)
        assertTrue(r.body().endsWith("\n**Build:** " + Fmt.escape(io.github.digitalisp.mapart.BuildInfo.current().describe())),
            r.body());
        assertTrue(r.body().contains("**Build:** " + io.github.digitalisp.mapart.BuildConstants.VERSION + " ("), r.body());
    }

    @Test
    void materialRowsMarkShortages() {
        StockIndex stock = new StockIndex();
        stock.update(new Pos(0, 64, 0), Map.of("minecraft:white_carpet", 12000), 0, 27, NOW);
        stock.update(new Pos(1, 64, 0), Map.of("minecraft:black_carpet", 1200), 0, 27, NOW);
        List<QueueOps.MaterialRow> rows = QueueOps.materialRows(
            materials("minecraft:black_carpet", 4000, "minecraft:white_carpet", 8192), stock);
        assertEquals(List.of(new QueueOps.MaterialRow("minecraft:white_carpet", 8192, 12000),
            new QueueOps.MaterialRow("minecraft:black_carpet", 4000, 1200)), rows);
        assertTrue(rows.get(1).isShort());
        assertEquals(2800, rows.get(1).shortBy());

        String table = QueueOps.formatMaterials(rows, 4000);
        assertEquals("""
            **Materials** (in stock / needed):
            **white carpet**: 12,000 / 8,192
            ! **black carpet**: 1,200 / 4,000, 2,800 missing""", table);
    }

    @Test
    void materialRowsFlagItemsTheBaseHasNoChestFor() {
        StockIndex stock = new StockIndex();
        stock.update(new Pos(0, 64, 0), Map.of("minecraft:white_carpet", 100), 0, 27, NOW);
        stock.update(new Pos(1, 64, 0), Map.of("minecraft:black_carpet", 64), 0, 27, NOW);
        stock.update(new Pos(1, 64, 0), Map.of(), 27, 27, NOW); // emptied: still the black carpet chest
        List<QueueOps.MaterialRow> rows = QueueOps.materialRows(materials("minecraft:white_carpet", 50,
            "minecraft:black_carpet", 300, "minecraft:decorated_pot", 4), stock);
        assertEquals(List.of(new QueueOps.MaterialRow("minecraft:black_carpet", 300, 0, false),
            new QueueOps.MaterialRow("minecraft:white_carpet", 50, 100, false),
            new QueueOps.MaterialRow("minecraft:decorated_pot", 4, 0, true)), rows);
        assertTrue(rows.get(2).isShort());

        assertEquals("""
            **Materials** (in stock / needed):
            ! **black carpet**: 0 / 300, 300 missing
            **white carpet**: 100 / 50
            ✗ **decorated pot**: 0 / 4, 4 missing""", QueueOps.formatMaterials(rows, 4000));
        // nothing scanned yet: unknown, not "no chest"
        assertTrue(QueueOps.materialRows(materials("minecraft:decorated_pot", 4), new StockIndex()).stream()
            .noneMatch(QueueOps.MaterialRow::unstocked));
    }

    @Test
    void materialTableRespectsLimit() {
        List<QueueOps.MaterialRow> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) rows.add(new QueueOps.MaterialRow("minecraft:item_" + i, 100 - i, 0));
        String table = QueueOps.formatMaterials(rows, 1000);
        assertTrue(table.length() <= 1000, "length " + table.length());
        assertTrue(table.matches("(?s).*\n… and \\d+ more"), table);
        long shown = table.lines().count() - 2; // the header and the "… and N more" line
        assertEquals(100, shown + Integer.parseInt(table.replaceAll("(?s).*… and (\\d+) more", "$1")),
            "every item shown or counted");
    }

    @Test
    void stockRowsFilterAndSortShortagesFirst() {
        fill();
        store.stock.update(new Pos(0, 64, 0), Map.of("minecraft:white_carpet", 2000, "minecraft:black_carpet", 10,
            "minecraft:stone", 64), 3, 27, NOW - 3_600_000);
        List<QueueOps.StockRow> rows = ops.stockRows(null);
        // queued = pending jobs only (ACTIVE j0002, QUEUED j0004, WAITING j0005)
        assertEquals(new QueueOps.StockRow("minecraft:red_carpet", 0, 50), rows.get(1));
        assertEquals(new QueueOps.StockRow("minecraft:black_carpet", 10, 300), rows.get(0));
        assertEquals(new QueueOps.StockRow("minecraft:stone", 64, 0), rows.get(2));
        assertEquals(new QueueOps.StockRow("minecraft:white_carpet", 2000, 1500), rows.get(3));

        assertEquals(List.of("minecraft:black_carpet"), ops.stockRows("BLACK").stream().map(QueueOps.StockRow::item).toList());

        Reply r = ops.stock("carpet");
        assertEquals(Reply.Tone.WARN, r.tone());
        // plain wording: what is short and how many to get, what is enough (live 2026-10-15: the old table of every
        // item with a "per map" column was unreadable)
        assertTrue(r.body().startsWith("Each map's blocks come back after it is cleared"), r.body());
        assertTrue(r.body().contains("**Short (2):** in stock / needed\n**black carpet**: 10 / 300, 290 missing\n"
            + "**red carpet**: 0 / 50, 50 missing\n"), r.body());
        assertTrue(r.body().contains("**Enough (1):** in stock / needed\n**white carpet**: 2,000 / 1,500\n"), r.body());
        assertTrue(r.body().endsWith("\n_1 chests indexed; the oldest was last looked into 1h 00m ago._"), r.body());
        assertFalse(r.body().contains("stone"));
        assertFalse(r.body().contains("_carpet"), r.body());

        // by default only the short items
        Reply all = ops.stock(null);
        assertTrue(all.body().contains("**Short (2):**"), all.body());
        assertFalse(all.body().contains("white carpet"), all.body());
        assertFalse(all.body().contains("stone"), all.body());
        assertTrue(all.body().contains("`stock <name>` shows any item"), all.body());
        Reply st = ops.stock("stone");
        assertTrue(st.body().contains("**In stock, not used by the queued maps (1):** stone 64"), st.body());

        assertTrue(ops.stock("zzz").body().contains("No matching items."));
        assertTrue(new QueueOps(new Fakes.Store(), () -> engine, () -> NOW).stock(null).body()
            .startsWith("No chests scanned yet."));
    }

    // ---------------------------------------------------------------- the status words (StatusWords)

    /** A colour-suppression job as stored: 8 passes as tiles (i,0), the top layer's four first, 4,000 blocks each. */
    static Job fox(int passesDone) {
        Job j = job("j0004", "Fox", JobStatus.ACTIVE, 8, 1, Map.of());
        j.suppression = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            j.suppression.add(new Job.SuppressionPass(i < 4 ? io.github.digitalisp.mapart.model.SuppressionArt.TOP
                : io.github.digitalisp.mapart.model.SuppressionArt.BOTTOM, i % 2, i % 4 < 2));
            Job.TileState t = j.tiles.get(i);
            t.total = 4_000;
            if (i < passesDone) {
                t.status = TileStatus.STORED;
                t.placed = 4_000;
            }
        }
        return j;
    }

    @Test
    void statusOfAJobOfSeveralMapsNamesTheMapNotTheTile() {
        fill();
        EngineStatus s = new EngineStatus(true, false, "", Phase.BUILD, "j0002", "Pine Tree", 1, 0, "moss",
            7400, 16384, "", NOW - 12 * 60_000, 1_500, 0, 0);
        assertEquals("""
            **State:** running
            **Doing:** Printing · map 2 of 2 (45%) · 1 map done · map \\~25m left
            **Phase:** BUILD for 12m
            **Job:** `j0002` Pine Tree on moss
            **Placed:** 7,400 / 16,384 (45.2%)""", QueueOps.formatStatus(s, store.get("j0002").orElseThrow(), NOW));
        engine.status = s;
        assertTrue(ops.status().body().contains("**Doing:** Printing · map 2 of 2 (45%) · 1 map done"), ops.status().body());
        assertFalse(ops.status().body().contains("tile"), ops.status().body());
    }

    @Test
    void statusOfAColourSuppressionMapIsOneMap() {
        Job fox = fox(2);
        EngineStatus print = new EngineStatus(true, false, "", Phase.BUILD, "j0004", "Fox", 2, 0, "cs", 2_000, 4_000, "",
            NOW - 12 * 60_000, 7_800, 0, 0);
        assertEquals("""
            **State:** running
            **Doing:** Printing · top layer · 31% · \\~2h 10m left
            **Phase:** BUILD for 12m
            **Job:** `j0004` Fox, color suppression (one map) on cs
            **Placed:** 10,000 / 32,000 (31.3% of the whole map)""", QueueOps.formatStatus(print, fox, NOW));

        Job ridden = fox(7);
        ridden.tiles.get(7).placed = 4_000;
        EngineStatus ride = new EngineStatus(true, false, "", Phase.RIDE, "j0004", "Fox", 7, 0, "cs", 4_000, 4_000,
            "riding the muncher", NOW - 41 * 60_000, -1, NOW - 41 * 60_000, 75 * 60);
        String text = QueueOps.formatStatus(ride, ridden, NOW);
        assertTrue(text.contains("**Doing:** Riding the muncher · 41 of \\~75 min (54%)\n"), text);
        assertTrue(text.contains("**Placed:** 32,000 / 32,000 (100.0% of the whole map)"), text);
        assertFalse(text.contains("tile") || text.contains("pass ") || text.contains("7,0"), text);

        EngineStatus wait = new EngineStatus(true, false, "", Phase.INSPECT, "j0004", "Fox", 4, 0, "cs", 0, 4_000,
            "waiting for the muncher to clear the bottom layer: 1240 blocks left", NOW, -1, 0, 0);
        assertTrue(QueueOps.formatStatus(wait, fox(4), NOW).contains(
            "**Doing:** Waiting for the muncher to clear the bottom layer (1,240 blocks left) · 50%\n"));
    }

    @Test
    void queueShowsAColourSuppressionMapAsOneMap() {
        store.add(fox(2)).add(job("j0005", "Next", JobStatus.QUEUED, 1, 1, Map.of()));
        Job queued = fox(0);
        queued.id = "j0006";
        queued.name = "Hike";
        queued.status = JobStatus.QUEUED;
        store.add(queued);
        engine.status = new EngineStatus(true, false, "", Phase.BUILD, "j0004", "Fox", 2, 0, "cs", 2_000, 4_000, "",
            NOW, 7_800, 0, 0);
        assertEquals("""
            **1. Fox** (jared2013)
            `j0004` · color suppression · ACTIVE · 31% printed
            Printing · top layer · 31% · \\~2h 10m left · on cs

            **2. Next** (jared2013)
            `j0005` · 1x1 · QUEUED · 0/1 map

            **3. Hike** (jared2013)
            `j0006` · color suppression · QUEUED · not started

            3 pending, 1 active.""", QueueOps.formatQueue(ops.queueRows(), List.of()));
    }
}
