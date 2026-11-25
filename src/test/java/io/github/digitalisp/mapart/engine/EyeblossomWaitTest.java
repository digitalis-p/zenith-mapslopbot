package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.discord.PresenceText;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FILL_MAP waits for the world to flip the tile's eyeblossoms to the state the art wants (their map colour follows
 * the time of day: 1.21.4 open 15 at night, closed 49 by day), standing at the map centre and polling the chunk
 * cache, then fills; it goes BLOCKED after {@code map.eyeblossomWaitMinutes}. The Sim catalog uses the 1.21.4
 * colours, so CHECK_MAP compares against them.
 */
class EyeblossomWaitTest {
    private static final String OPEN = "minecraft:open_eyeblossom";
    private static final String CLOSED = "minecraft:closed_eyeblossom";
    private static final int LONG = 80_000;

    /** Row 0 white carpet, rows 1-2 eyeblossoms in the state {@code blossom}. */
    private static TileArt art(String blossom) {
        TileArt a = new TileArt();
        for (int px = 0; px < 128; px++) {
            a.set(px, 0, BlockSpec.of(Sim.WHITE));
            a.set(px, 1, BlockSpec.of(blossom));
            a.set(px, 2, BlockSpec.of(blossom));
        }
        return a;
    }

    private static Sim sim(String blossom, TileArt art) {
        Sim sim = new Sim();
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(blossom, 1728));
        sim.addJob("j0060", "Blossoms", 1, 1, List.of("moss"), (x, z) -> art);
        return sim;
    }

    /** The world flips every eyeblossom on the moss art layer to {@code state} (night: open, day: closed). */
    private static void flipAll(Sim sim, String state) {
        for (int pz = 0; pz < 128; pz++)
            for (int px = 0; px < 128; px++) {
                Pos p = sim.moss.geometry().cell(px, pz);
                String id = sim.world.block(p).id();
                if ((id.equals(OPEN) || id.equals(CLOSED)) && !id.equals(state)) sim.world.set(p, BlockSpec.of(state));
            }
    }

    private static String presence(Sim sim) {
        return PresenceText.describe(PresenceText.input(sim.engine.status(), sim.store.jobs, true,
            PresenceText.Link.IN_GAME, 0)).text();
    }

    private static Job job(Sim sim) {
        return sim.store.jobs.getFirst();
    }

    @Test
    void waitsAtTheMapCentreUntilTheyCloseByDayThenFillsAndTheMapMatches() {
        Sim sim = sim(CLOSED, art(CLOSED));
        sim.start();
        // night falls just as the tile is built: the world opens every eyeblossom
        AtomicLong nightAt = new AtomicLong(-1);
        assertTrue(sim.runUntil(() -> {
            if (nightAt.get() < 0 && sim.phase() == Phase.FILL_MAP) {
                flipAll(sim, OPEN);
                nightAt.set(sim.ticks);
            }
            return nightAt.get() >= 0 && sim.ticks - nightAt.get() >= 2_400; // 2 min
        }, LONG), sim.state());

        assertEquals(Phase.FILL_MAP, sim.phase(), sim.state());
        assertEquals("waiting for eyeblossoms to close (day)", sim.engine.status().note());
        assertEquals(0, sim.bot.createdMaps, "no map filled while they are open");
        Pos centre = sim.moss.center;
        assertEquals(centre.x() + 0.5, sim.world.x, 1e-9, "waits at the map center");
        assertEquals(centre.z() + 0.5, sim.world.z, 1e-9);
        assertTrue(presence(sim).startsWith("Waiting for eyeblossoms to close (day) · Blossoms"), presence(sim));
        assertTrue(sim.notifier.has("info", "Waiting for eyeblossoms"), sim.notifier.toString());
        int actions = sim.bot.history.size();
        sim.run(1_200);
        assertEquals(actions, sim.bot.history.size(), "stands still while polling: " + sim.bot.history.subList(actions,
            sim.bot.history.size()));

        // day: they close, and the map is filled within a poll or two
        flipAll(sim, CLOSED);
        long dayAt = sim.ticks;
        assertTrue(sim.runUntil(() -> sim.bot.createdMaps > 0, 400), sim.state());
        assertTrue(sim.ticks - dayAt <= 200, "filled " + (sim.ticks - dayAt) + " ticks after the flip");
        assertTrue(sim.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());

        assertEquals(1, sim.bot.createdMaps);
        assertEquals(0, sim.renderer.diffs, "CHECK_MAP matched on the first fill");
        FakeBot.MapItem stored = sim.output().maps.getFirst();
        byte[] px = sim.maps.colors.get(stored.id());
        assertEquals((byte) ((49 << 2) | 1), px[128 + 5], "closed eyeblossom: TERRACOTTA_GREEN (1.21.4), normal shade");
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void waitsForNightWhenTheArtWantsThemOpen() {
        Sim sim = sim(OPEN, art(OPEN));
        sim.start();
        AtomicLong dayAt = new AtomicLong(-1);
        assertTrue(sim.runUntil(() -> {
            if (dayAt.get() < 0 && sim.phase() == Phase.FILL_MAP) {
                flipAll(sim, CLOSED);
                dayAt.set(sim.ticks);
            }
            return dayAt.get() >= 0 && sim.ticks - dayAt.get() >= 600;
        }, LONG), sim.state());
        assertEquals("waiting for eyeblossoms to open (night)", sim.engine.status().note());
        assertEquals(0, sim.bot.createdMaps);
        flipAll(sim, OPEN);
        assertTrue(sim.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        byte[] px = sim.maps.colors.get(sim.output().maps.getFirst().id());
        assertEquals((byte) ((15 << 2) | 1), px[128 + 5], "open eyeblossom: COLOR_ORANGE");
        assertEquals(0, sim.renderer.diffs);
    }

    @Test
    void timeoutAlertsAndBlocksWithoutLooping() {
        Sim sim = sim(CLOSED, art(CLOSED));
        sim.config.map.eyeblossomWaitMinutes = 1;
        sim.start();
        AtomicLong nightAt = new AtomicLong(-1);
        assertTrue(sim.runUntil(() -> {
            if (nightAt.get() < 0 && sim.phase() == Phase.FILL_MAP) nightAt.set(sim.ticks);
            if (nightAt.get() >= 0) flipAll(sim, OPEN); // a night that never ends
            return sim.phase() == Phase.BLOCKED;
        }, LONG), sim.state());

        long waited = sim.ticks - nightAt.get();
        assertTrue(waited >= 1_200 && waited < 1_200 + 400, "blocked after the 1 min limit, not before: " + waited);
        String note = sim.engine.status().note();
        assertTrue(note.contains("eyeblossoms did not close (day) within 1 min: 256 of 256 still open"), note);
        assertEquals(1, sim.notifier.of("alert").size(), sim.notifier.toString());
        assertTrue(sim.notifier.of("alert").getFirst().body().contains("eyeblossoms did not close (day)"));
        assertEquals(0, sim.bot.createdMaps);

        int actions = sim.bot.history.size();
        sim.run(6_000);
        assertEquals(Phase.BLOCKED, sim.phase(), "stays BLOCKED until a resume");
        assertEquals(1, sim.notifier.of("alert").size(), "one alert, no loop");
        assertEquals(actions, sim.bot.history.size());
        assertEquals(0, sim.bot.createdMaps);
        assertTrue(presence(sim).startsWith("Needs help: eyeblossoms did not close (day)"), presence(sim));
    }

    @Test
    void aMapWantingBothStatesBlocksAtOnce() {
        // queued before uploads rejected such art: it can never match, so no wait at all
        TileArt a = art(CLOSED);
        for (int px = 0; px < 128; px++) a.set(px, 2, BlockSpec.of(OPEN));
        Sim sim = sim(CLOSED, a);
        sim.bot.addContainer(new Pos(-88, 61, 100), Map.of(OPEN, 1728)); // the Sim catalog keeps the two items apart
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertTrue(sim.engine.status().note().contains("wants open and closed eyeblossoms"), sim.engine.status().note());
        assertFalse(sim.notifier.has("info", "Waiting for eyeblossoms"));
        assertEquals(0, sim.bot.createdMaps);
    }

    @Test
    void noWaitWithoutEyeblossomsOrWhenTheyAlreadyShowTheWantedState() {
        for (TileArt art : List.of(Sim.stripes(3, 1), art(CLOSED))) {
            Sim sim = sim(CLOSED, art);
            sim.start();
            AtomicLong fillAt = new AtomicLong(-1);
            AtomicBoolean noted = new AtomicBoolean();
            assertTrue(sim.runUntil(() -> {
                if (fillAt.get() < 0 && sim.phase() == Phase.FILL_MAP) fillAt.set(sim.ticks);
                String n = sim.engine.status().note();
                if (n != null && n.startsWith(BuildEngine.EYEBLOSSOM_NOTE)) noted.set(true);
                return sim.bot.createdMaps > 0;
            }, LONG), sim.state());
            assertTrue(sim.ticks - fillAt.get() < 200, "filled right away: " + (sim.ticks - fillAt.get()) + " ticks");
            assertFalse(noted.get());
            assertTrue(sim.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
            assertFalse(sim.notifier.has("info", "Waiting for eyeblossoms"), sim.notifier.toString());
            assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
        }
    }
}
