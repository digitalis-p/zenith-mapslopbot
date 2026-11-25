package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.discord.PresenceText;
import io.github.digitalisp.mapart.engine.SimFakes.RecordingNotifier.Msg;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colour-suppression platform above the moss tile in use: blocks with a map colour above the art layer hide the art
 * on the map (the fake map shows the topmost coloured block, like vanilla). FILL_MAP waits at the map centre holding
 * the map, counts no fill attempt and repairs nothing, and the map is locked only once it matches. The Sim art layer is
 * y64; the platform's plain glass layers sit 27 and 41 above it (y200 and y214 over the art at y173 on 2b2t).
 */
class CoverWaitTest {
    private static final int LONG = 80_000;
    private static final int GLASS = 64 + 27;
    private static final int UPPER_GLASS = 64 + 41;
    private static final String WOOL = "minecraft:red_wool";
    private static final String STAINED = "minecraft:white_stained_glass";
    private static final String START_TITLE = "Waiting for the platform above";
    private static final String HELD = "The map is held and will be locked only when it matches.";

    private static Sim sim() {
        Sim sim = new Sim();
        sim.addJob("j0070", "Covered", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        return sim;
    }

    private static Job job(Sim sim) {
        return sim.store.jobs.getFirst();
    }

    private static Pos at(Sim sim, int px, int pz, int y) {
        return new Pos(sim.moss.minX + px, y, sim.moss.minZ + pz);
    }

    /** Puts {@code id} on the {@code w} x {@code h} pixels from (px, pz) at height {@code y}; returns the positions. */
    private static List<Pos> cover(Sim sim, int px, int pz, int w, int h, int y, String id) {
        List<Pos> out = new ArrayList<>();
        for (int dz = 0; dz < h; dz++)
            for (int dx = 0; dx < w; dx++) {
                Pos p = at(sim, px + dx, pz + dz, y);
                sim.world.set(p, BlockSpec.of(id));
                out.add(p);
            }
        return out;
    }

    private static void uncover(Sim sim, List<Pos> blocks) {
        for (Pos p : blocks) sim.world.set(p, BlockSpec.AIR);
    }

    /** Both plain glass layers of the platform over the whole map and the row north of it. */
    private static void glassLayers(Sim sim) {
        for (int pz = -1; pz < 128; pz++)
            for (int px = 0; px < 128; px++) {
                sim.world.set(at(sim, px, pz, GLASS), BlockSpec.of("minecraft:glass"));
                sim.world.set(at(sim, px, pz, UPPER_GLASS), BlockSpec.of("minecraft:glass"));
            }
    }

    private static boolean waiting(Sim sim) {
        String n = sim.engine.status().note();
        return sim.phase() == Phase.FILL_MAP && n != null && n.startsWith(BuildEngine.COVER_NOTE);
    }

    private static List<Msg> infos(Sim sim, String title) {
        return sim.notifier.of("info").stream().filter(m -> m.title().equals(title)).toList();
    }

    private static List<Msg> mismatches(Sim sim) {
        return sim.notifier.of("image").stream().filter(m -> m.title().startsWith("Map mismatch")).toList();
    }

    private static String presence(Sim sim) {
        return PresenceText.describe(PresenceText.input(sim.engine.status(), sim.store.jobs, true,
            PresenceText.Link.IN_GAME, 0)).text();
    }

    private static void assertAtCentreHolding(Sim sim) {
        Pos centre = sim.moss.center;
        assertEquals(centre.x() + 0.5, sim.world.x, 1e-9, "at the map center");
        assertEquals(centre.z() + 0.5, sim.world.z, 1e-9);
        assertEquals(1, sim.inv.filledMaps.size(), sim.inv.filledMaps.toString());
        assertEquals(sim.inv.filledMaps.getFirst(), sim.maps.held, "the map is in the main hand");
        assertTrue(sim.maps.locked.isEmpty(), "nothing locked");
    }

    /** The stored map shows white carpet (SNOW, 8) at {@code (px, pz)}, normal shade. */
    private static void assertStoredWhite(Sim sim, int px, int pz) {
        FakeBot.MapItem stored = sim.output().maps.getFirst();
        assertTrue(stored.locked());
        assertEquals((byte) ((8 << 2) | 1), sim.maps.colors.get(stored.id())[pz * 128 + px]);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void waitsAtTheCentreHoldingTheMapWithoutAttemptsThenLocksOnceTheBlocksAreGone() {
        Sim sim = sim();
        sim.config.map.occludedReminderMinutes = 1;
        List<Pos> wool = cover(sim, 10, 0, 20, 4, GLASS + 1, WOOL); // 80 pixels of the art, "on the y200 glass"
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());

        assertEquals(BuildEngine.COVER_NOTE + " (80 pixels covered)", sim.engine.status().note());
        assertTrue(presence(sim).startsWith("Waiting: platform above in use (80 pixels covered) · Covered"),
            presence(sim));
        List<Msg> started = infos(sim, START_TITLE);
        assertEquals(1, started.size(), sim.notifier.toString());
        assertEquals("#j0070 Covered [0,0]: Waiting for the platform above: 80 pixels covered by blocks at y92"
            + " (e.g. red_wool). " + HELD, started.getFirst().body());
        int verifies = sim.store.entries("VERIFY");
        assertTrue(sim.runUntil(() -> sim.bot.createdMaps == 1 && sim.maps.held != null, 200), sim.state());
        assertAtCentreHolding(sim);

        // three minutes, far more polls than map.maxFillAttempts: no attempt, no repair, no new map, standing still
        int actions = sim.bot.history.size();
        AtomicBoolean left = new AtomicBoolean();
        sim.runUntil(() -> {
            if (!waiting(sim)) left.set(true);
            return false;
        }, 3_600);
        assertFalse(left.get(), "stayed in the wait: " + sim.state());
        assertEquals(actions, sim.bot.history.size(), "stands still holding the map: "
            + sim.bot.history.subList(actions, sim.bot.history.size()));
        assertEquals(verifies, sim.store.entries("VERIFY"), "nothing repaired");
        assertEquals(0, sim.renderer.diffs, "no mismatch reported");
        assertEquals(1, sim.bot.createdMaps);
        assertAtCentreHolding(sim);
        assertEquals(1, infos(sim, START_TITLE).size(), "one start line");
        List<Msg> reminders = infos(sim, "Still waiting for the platform above");
        assertTrue(reminders.size() >= 2 && reminders.size() <= 3, sim.notifier.toString());
        assertTrue(reminders.getFirst().body().contains("(waiting 1 min): Waiting for the platform above: 80 pixels"),
            reminders.getFirst().body());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());

        // the blocks go: the map is filled and locked
        uncover(sim, wool);
        long goneAt = sim.ticks;
        assertTrue(sim.runUntil(() -> !sim.maps.locked.isEmpty(), 600), sim.state());
        assertTrue(sim.ticks - goneAt < 400, "locked " + (sim.ticks - goneAt) + " ticks after the blocks went");
        assertTrue(sim.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.bot.createdMaps, "the held map was the one locked");
        assertEquals(0, sim.renderer.diffs);
        assertEquals(verifies, sim.store.entries("VERIFY"));
        assertStoredWhite(sim, 15, 1);
        assertTrue(sim.notifier.has("detail", "Map locked"));
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void aStrayRightOnTheArtLayerIsBrokenInsteadOfWaitedFor() {
        Sim sim = sim();
        // live 2026-10-25: a lost click's cyan carpet on top of the art (art y + 1); nobody takes it away
        List<Pos> stray = cover(sim, 124, 2, 1, 1, 65, "minecraft:cyan_carpet");
        sim.start();
        assertTrue(sim.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.world.block(stray.getFirst()).isAir(), "the stray was broken");
        assertEquals(0, infos(sim, START_TITLE).size(), "no cover wait: " + sim.notifier);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
        assertEquals(0, sim.renderer.diffs);
        assertStoredWhite(sim, 124, 2);
    }

    @Test
    void aStrayWithThePlatformAboveInUseIsLeftAndWaitedFor() {
        Sim sim = sim();
        List<Pos> stray = cover(sim, 124, 2, 1, 1, 65, "minecraft:cyan_carpet");
        List<Pos> wool = cover(sim, 10, 0, 20, 4, GLASS + 1, WOOL);
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());
        sim.run(1_200);
        assertTrue(waiting(sim), sim.state());
        assertFalse(sim.world.block(stray.getFirst()).isAir(), "nothing broken while someone builds above");
        uncover(sim, wool);
        uncover(sim, stray);
        assertTrue(sim.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
    }

    @Test
    void aHeldMapThatCatchesUpIsLockedWithoutAnotherFill() {
        Sim sim = sim();
        sim.bot.heldMapRefreshTicks = 20; // the map in hand keeps updating, like in vanilla
        List<Pos> wool = cover(sim, 0, 0, 128, 4, GLASS + 1, WOOL);
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim) && sim.bot.createdMaps == 1, LONG), sim.state());
        assertEquals(BuildEngine.COVER_NOTE + " (512 pixels covered)", sim.engine.status().note());
        sim.run(1_200);
        assertTrue(waiting(sim), sim.state());

        uncover(sim, wool);
        assertTrue(sim.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.bot.createdMaps);
        assertEquals(0, sim.bot.heldMaps, "locked straight from the held map: no fill attempt at all");
        assertEquals(0, sim.renderer.diffs);
        assertStoredWhite(sim, 64, 2);
    }

    @Test
    void coveredDuringTheFillTheMismatchSaysSoAndIsNotRepairedOrCounted() {
        Sim sim = sim();
        sim.config.map.maxFillAttempts = 1; // a counted mismatch would block at once
        List<Pos> wool = new ArrayList<>();
        sim.bot.beforeCreateMap = () -> wool.addAll(cover(sim, 10, 0, 20, 4, GLASS + 1, WOOL));
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());

        List<Msg> diff = mismatches(sim);
        assertEquals(1, diff.size(), sim.notifier.toString());
        assertEquals(1, sim.renderer.diffs);
        String body = diff.getFirst().body();
        assertTrue(body.startsWith("#j0070 Covered [0,0]: 80 pixels differ (not counted as a fill attempt). Waiting for"
            + " the platform above: 80 pixels covered by blocks at y92 (e.g. red_wool). " + HELD), body);
        assertFalse(body.contains("repairing"), body);
        assertEquals(0, infos(sim, START_TITLE).size(), "the mismatch post is the start line");
        int verifies = sim.store.entries("VERIFY");
        sim.run(2_400);
        assertTrue(waiting(sim), sim.state());
        assertEquals(verifies, sim.store.entries("VERIFY"), "no repair pass");
        assertEquals(1, sim.renderer.diffs);
        assertAtCentreHolding(sim);

        uncover(sim, wool);
        assertTrue(sim.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.bot.createdMaps);
        assertEquals(1, sim.renderer.diffs, "matched on the next fill");
        assertEquals(verifies, sim.store.entries("VERIFY"));
        assertStoredWhite(sim, 15, 1);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void thePlatformsPlainGlassIsInvisibleButStainedGlassCovers() {
        Sim plain = sim();
        glassLayers(plain);
        plain.start();
        AtomicBoolean noted = new AtomicBoolean();
        assertTrue(plain.runUntil(() -> {
            if (waiting(plain)) noted.set(true);
            return job(plain).status == JobStatus.DONE;
        }, LONG), plain.state());
        assertFalse(noted.get());
        assertEquals(0, infos(plain, START_TITLE).size(), plain.notifier.toString());
        assertEquals(0, plain.renderer.diffs, "glass is invisible on the map");
        assertEquals(1, plain.bot.createdMaps);
        assertStoredWhite(plain, 15, 1);

        Sim stained = sim();
        glassLayers(stained);
        List<Pos> panes = cover(stained, 40, 2, 5, 1, GLASS, STAINED); // stained glass in the y200 glass layer
        stained.start();
        assertTrue(stained.runUntil(() -> waiting(stained), LONG), stained.state());
        assertEquals(BuildEngine.COVER_NOTE + " (5 pixels covered)", stained.engine.status().note());
        assertTrue(infos(stained, START_TITLE).getFirst().body().contains(
            "5 pixels covered by blocks at y91 (e.g. white_stained_glass)"), stained.notifier.toString());
        stained.run(1_200);
        assertTrue(waiting(stained), stained.state());
        for (Pos p : panes) stained.world.set(p, BlockSpec.of("minecraft:glass"));
        assertTrue(stained.runUntil(() -> job(stained).status == JobStatus.DONE, LONG), stained.state());
        assertEquals(0, stained.renderer.diffs);
        assertStoredWhite(stained, 42, 2);
        assertTrue(stained.notifier.of("alert").isEmpty(), stained.notifier.toString());
    }

    @Test
    void aPartialCoverAtTwoLevelsAndOverTheRowNorthOfTheMap() {
        Sim sim = sim();
        List<Pos> wool = cover(sim, 0, 1, 30, 1, GLASS + 1, WOOL);
        List<Pos> panes = cover(sim, 50, 2, 10, 1, UPPER_GLASS + 1, STAINED);
        List<Pos> north = cover(sim, 70, -1, 1, 1, GLASS + 1, WOOL); // shades the first row
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());
        assertEquals(BuildEngine.COVER_NOTE + " (40 pixels covered)", sim.engine.status().note());
        assertEquals("#j0070 Covered [0,0]: Waiting for the platform above: 40 pixels covered by blocks at y92 and"
            + " y106 (e.g. red_wool, white_stained_glass); 1 more block above the row north of the map (it shades the"
            + " first row). " + HELD, infos(sim, START_TITLE).getFirst().body());

        uncover(sim, wool);
        assertTrue(sim.runUntil(() -> sim.engine.status().note().equals(BuildEngine.COVER_NOTE + " (10 pixels covered)"),
            200), sim.state());
        uncover(sim, panes);
        assertTrue(sim.runUntil(() -> sim.engine.status().note()
            .equals(BuildEngine.COVER_NOTE + " (the row north of the map covered)"), 200), sim.state());
        sim.run(600);
        assertTrue(waiting(sim), "the first row's shading still differs: " + sim.state());
        assertEquals(1, infos(sim, START_TITLE).size(), "one wait throughout");

        uncover(sim, north);
        assertTrue(sim.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertEquals(0, sim.renderer.diffs);
        assertEquals(1, sim.bot.createdMaps);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void coveredAgainRightAfterItClearedTheSameWaitGoesOnWithoutAnotherPost() {
        Sim sim = sim();
        List<Pos> wool = cover(sim, 10, 0, 20, 4, GLASS + 1, WOOL);
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());
        sim.run(600);
        // the blocks go, and are back while the map is held for the fill (someone still busy above)
        List<Pos> again = new ArrayList<>();
        sim.bot.beforeHoldMap = () -> again.addAll(cover(sim, 40, 0, 10, 2, GLASS + 2, STAINED));
        uncover(sim, wool);
        assertTrue(sim.runUntil(() -> !again.isEmpty(), 400), sim.state());
        assertTrue(sim.runUntil(() -> waiting(sim), 100), sim.state());
        assertEquals(BuildEngine.COVER_NOTE + " (20 pixels covered)", sim.engine.status().note());
        assertEquals(1, infos(sim, START_TITLE).size(), "one wait: " + sim.notifier);
        assertTrue(mismatches(sim).isEmpty(), "no second post for the same wait: " + sim.notifier);
        assertEquals(0, sim.renderer.diffs);

        uncover(sim, again);
        assertTrue(sim.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.bot.createdMaps);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void theMaxWaitBlocksWithAClearNoteAndResumeWaitsAgain() {
        Sim sim = sim();
        sim.config.map.occludedMaxWaitMinutes = 1;
        List<Pos> wool = cover(sim, 10, 0, 20, 4, GLASS + 1, WOOL);
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());
        long since = sim.ticks;
        assertTrue(infos(sim, START_TITLE).getFirst().body().endsWith(
            "The map is held and will be locked only when it matches; BLOCKED if still covered after 1 min."));
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());

        long waited = sim.ticks - since;
        assertTrue(waited >= 1_200 && waited < 1_200 + 200, "blocked after the 1 min limit, not before: " + waited);
        assertEquals("the platform above is still in use after 1 min: 80 pixels covered by blocks at y92 (e.g. red_wool)."
            + " The map would show those blocks, so it is not locked. Resume to wait again.", sim.engine.status().note());
        assertEquals(1, sim.notifier.of("alert").size(), sim.notifier.toString());
        assertTrue(presence(sim).startsWith("Needs help: the platform above is still in use after 1 min"), presence(sim));
        assertTrue(sim.maps.locked.isEmpty());
        assertEquals(0, sim.renderer.diffs);
        int actions = sim.bot.history.size();
        sim.run(3_000);
        assertEquals(Phase.BLOCKED, sim.phase(), "stays BLOCKED until a resume");
        assertEquals(1, sim.notifier.of("alert").size(), "one alert, no loop");
        assertEquals(actions, sim.bot.history.size());

        sim.engine.resume();
        assertTrue(sim.runUntil(() -> waiting(sim), 400), sim.state());
        assertEquals(2, infos(sim, START_TITLE).size(), "the wait starts over");
        uncover(sim, wool);
        assertTrue(sim.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.bot.createdMaps, "the held map survived the block");
        assertEquals(1, sim.notifier.of("alert").size(), sim.notifier.toString());
    }

    @Test
    void aRealMismatchWithoutCoverStillRepairs() {
        Sim sim = sim();
        List<Pos> glass = new ArrayList<>(cover(sim, 0, 0, 128, 4, GLASS, "minecraft:glass")); // transparent
        assertEquals(512, glass.size());
        Pos victim = sim.moss.geometry().cell(20, 1);
        sim.bot.beforeCreateMap = () -> sim.world.set(victim, BlockSpec.of(Sim.BLACK));
        sim.start();
        AtomicBoolean noted = new AtomicBoolean();
        assertTrue(sim.runUntil(() -> {
            if (waiting(sim)) noted.set(true);
            return job(sim).status == JobStatus.DONE;
        }, LONG), sim.state());
        assertFalse(noted.get(), "no cover wait");
        List<String> phases = sim.store.phaseLog;
        int check = phases.indexOf("CHECK_MAP");
        assertEquals("VERIFY", phases.get(check + 1), "the old path: CHECK_MAP → VERIFY: " + phases);
        List<Msg> diff = mismatches(sim);
        assertEquals(1, diff.size(), sim.notifier.toString());
        assertTrue(diff.getFirst().body().contains("1 pixels differ (attempt 1/3); repairing."), diff.getFirst().body());
        assertFalse(diff.getFirst().body().contains("platform above"), diff.getFirst().body());
        assertEquals(0, infos(sim, START_TITLE).size());
        assertEquals(1, sim.bot.createdMaps);
        assertEquals(2, sim.bot.placeCounts.get(victim), "the wrong block was replaced");
        assertStoredWhite(sim, 20, 1);
    }

    @Test
    void eatsDuringALongWaitAndHoldsTheMapAgain() {
        Sim sim = sim();
        List<Pos> wool = cover(sim, 10, 0, 20, 4, GLASS + 1, WOOL);
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim) && sim.maps.held != null, LONG), sim.state());
        int eatsBefore = sim.bot.eats;
        int heldBefore = sim.bot.heldMaps;
        sim.bot.eatPhases.clear();
        sim.bot.lowestFoodLevel = sim.inv.foodLevel;
        sim.bot.ticksPerHungerPoint = 100; // ten minutes: 120 hunger points, about 20 golden carrots
        AtomicBoolean left = new AtomicBoolean();
        sim.runUntil(() -> {
            if (sim.phase() != Phase.FILL_MAP) left.set(true);
            return false;
        }, 12_000);
        sim.bot.ticksPerHungerPoint = 0;

        assertFalse(left.get(), sim.state());
        int eats = sim.bot.eats - eatsBefore;
        assertTrue(eats >= 15, "ate " + eats + " times");
        assertTrue(sim.bot.lowestFoodLevel >= sim.config.build.eatFoodLevel - 2,
            "never hungry for long: lowest " + sim.bot.lowestFoodLevel);
        for (Phase p : sim.bot.eatPhases) assertEquals(Phase.FILL_MAP, p);
        assertEquals(0, sim.bot.eatTicksBesideOtherTask, "eats while no other task runs");
        assertTrue(sim.bot.heldMaps - heldBefore >= eats - 1, "the map back in hand after each meal: "
            + (sim.bot.heldMaps - heldBefore) + " holds for " + eats + " meals");
        assertTrue(sim.runUntil(() -> waiting(sim) && sim.maps.held != null, 200), sim.state());
        assertAtCentreHolding(sim);
        assertEquals(1, infos(sim, START_TITLE).size(), "eating does not restart the wait");
        assertEquals(0, sim.renderer.diffs);

        uncover(sim, wool);
        assertTrue(sim.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void pauseBackOffAndAPlayerTakingControlInterruptTheWaitWithoutRestartingIt() {
        Sim sim = sim();
        List<Pos> wool = cover(sim, 10, 0, 20, 4, GLASS + 1, WOOL);
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim) && sim.maps.held != null, LONG), sim.state());

        sim.engine.pause("checking the platform");
        sim.run(5);
        int actions = sim.bot.history.size();
        sim.run(1_200);
        assertTrue(sim.engine.status().paused());
        assertEquals(actions, sim.bot.history.size(), "paused: nothing happens");
        assertTrue(presence(sim).startsWith("Paused: checking the platform · Covered"), presence(sim));
        sim.engine.resume();
        assertTrue(sim.runUntil(() -> waiting(sim), 200), sim.state());

        sim.engine.backoff("server setbacks", 5_000);
        sim.run(2);
        assertEquals(BuildEngine.BACKOFF_NOTE, sim.engine.status().note());
        assertTrue(sim.runUntil(() -> waiting(sim), 300), sim.state());

        sim.engine.onBotTickStopped(); // a player takes control for a minute
        sim.clock.advance(60_000);
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), 300), sim.state());

        assertEquals(1, infos(sim, START_TITLE).size(), "the wait went on: " + sim.notifier);
        assertEquals(0, sim.renderer.diffs);
        assertEquals(1, sim.bot.createdMaps);
        uncover(sim, wool);
        assertTrue(sim.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    // ---------------------------------------------------------------------------------------------
    // eyeblossoms: the cover wait takes precedence, and the eyeblossom time limit does not run out under it

    private static final String OPEN = "minecraft:open_eyeblossom";
    private static final String CLOSED = "minecraft:closed_eyeblossom";

    private static void flipAll(Sim sim, String state) {
        for (int pz = 0; pz < 128; pz++)
            for (int px = 0; px < 128; px++) {
                Pos p = sim.moss.geometry().cell(px, pz);
                String id = sim.world.block(p).id();
                if ((id.equals(OPEN) || id.equals(CLOSED)) && !id.equals(state)) sim.world.set(p, BlockSpec.of(state));
            }
    }

    @Test
    void theCoverWaitTakesPrecedenceOverTheEyeblossomWaitAndDoesNotEatItsTime() {
        TileArt art = new TileArt();
        for (int px = 0; px < 128; px++) {
            art.set(px, 0, BlockSpec.of(Sim.WHITE));
            art.set(px, 1, BlockSpec.of(CLOSED));
        }
        Sim sim = new Sim();
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(CLOSED, 1728));
        sim.addJob("j0071", "Blossoms", 1, 1, List.of("moss"), (x, z) -> art);
        sim.config.map.eyeblossomWaitMinutes = 1;
        sim.start();
        // night falls as the tile is built: the eyeblossom wait starts
        AtomicLong nightAt = new AtomicLong(-1);
        assertTrue(sim.runUntil(() -> {
            if (nightAt.get() < 0 && sim.phase() == Phase.FILL_MAP) {
                flipAll(sim, OPEN);
                nightAt.set(sim.ticks);
            }
            String n = sim.engine.status().note();
            return n != null && n.startsWith(BuildEngine.EYEBLOSSOM_NOTE);
        }, LONG), sim.state());
        sim.run(600);
        assertEquals(1, infos(sim, "Waiting for eyeblossoms").size());

        // half a minute in, someone builds on the platform above for two minutes: past the eyeblossom limit
        List<Pos> wool = cover(sim, 0, 5, 64, 2, GLASS + 1, WOOL);
        assertTrue(sim.runUntil(() -> waiting(sim), 200), sim.state());
        AtomicBoolean blocked = new AtomicBoolean();
        sim.runUntil(() -> {
            if (sim.phase() == Phase.BLOCKED) blocked.set(true);
            return false;
        }, 2_400);
        assertFalse(blocked.get(), "the eyeblossom limit did not run out under the cover wait: " + sim.state());
        assertTrue(waiting(sim), sim.state());
        assertTrue(presence(sim).startsWith("Waiting: platform above in use (128 pixels covered) · Blossoms"),
            presence(sim));

        // the blocks go, the eyeblossoms are still open: the eyeblossom wait starts over
        uncover(sim, wool);
        assertTrue(sim.runUntil(() -> sim.engine.status().note().startsWith(BuildEngine.EYEBLOSSOM_NOTE), 200),
            sim.state());
        assertEquals(Phase.FILL_MAP, sim.phase());
        assertEquals(2, infos(sim, "Waiting for eyeblossoms").size(), sim.notifier.toString());
        sim.run(600);
        assertEquals(Phase.FILL_MAP, sim.phase(), "a fresh eyeblossom limit: " + sim.state());

        flipAll(sim, CLOSED);
        assertTrue(sim.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.bot.createdMaps, "the map held through the cover wait was locked");
        assertEquals(0, sim.renderer.diffs);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
        assertNotNull(sim.output().maps.getFirst());
    }
}
