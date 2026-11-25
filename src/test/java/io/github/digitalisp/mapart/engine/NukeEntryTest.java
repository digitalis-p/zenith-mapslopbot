package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code build.nukeEntryCorner}: a NUKE whose platform is not loaded walks to the art layer by the corner its
 * {@code nukeEndCorner} names (the way onto it), not to the map station, and starts breaking there; when the chunks do
 * not load there it walks on to the station as before. Synthetic coordinates.
 */
class NukeEntryTest {
    private static final int LONG = 60_000;

    private static MapArtConfig.PlatformCfg platform(String corner, String kind) {
        MapArtConfig.PlatformCfg p = new MapArtConfig.PlatformCfg();
        p.minX = 128;
        p.minZ = -128;
        p.artY = 70;
        p.nukeEndCorner = corner;
        p.kind = kind;
        return p;
    }

    @Test
    void theStandIsThreeCellsInsideTheNamedCorner() {
        assertEquals(new Pos(128 + 124, 70, -128 + 3), NukeEntry.stand(platform("NE", null)));
        assertEquals(new Pos(128 + 3, 70, -128 + 3), NukeEntry.stand(platform(" nw ", null)));
        assertEquals(new Pos(128 + 3, 70, -128 + 124), NukeEntry.stand(platform("SW", null)));
        assertEquals(new Pos(128 + 124, 70, -128 + 124), NukeEntry.stand(platform("se", null)));
        assertNull(NukeEntry.stand(platform("", null)), "no corner set");
        assertNull(NukeEntry.stand(platform("north", null)), "an unknown name");
        assertNull(NukeEntry.stand(platform(null, null)));
        assertNull(NukeEntry.stand(platform("NE", MapArtConfig.PlatformCfg.COLOR_SUPPRESSION)), "color suppression");
        assertNull(NukeEntry.stand(null));
    }

    private static Sim sim(RecordingLogger log, boolean on) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.config.build.nukeEntryCorner = on;
        sim.moss.nukeEndCorner = "NE";
        for (int i = 0; i < 20; i++) {
            sim.world.set(sim.moss.geometry().cell(100 + i % 10, 100 + i / 10), BlockSpec.of(Sim.TERRACOTTA));
        }
        sim.addJob("j0310", "Entry", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        return sim;
    }

    /** During the NUKE the platform's chunks load only with the bot within 3 blocks of {@code at} (null: never). */
    private static void loadsOnlyAt(Sim sim, Pos at) {
        MapArtConfig.PlatformCfg m = sim.moss;
        sim.world.loaded = p -> sim.phase() != Phase.NUKE || p.x() < m.minX || p.x() > m.minX + 127 || p.z() < m.minZ
            || p.z() > m.minZ + 127 || at != null && Math.hypot(sim.world.x - (at.x() + 0.5), sim.world.z - (at.z() + 0.5)) < 3;
    }

    private static int first(List<String> h, String prefix, int from) {
        for (int i = from; i < h.size(); i++) if (h.get(i).startsWith(prefix)) return i;
        return -1;
    }

    private static String tail(RecordingLogger log) {
        List<String> l = log.lines;
        return "\n" + String.join("\n", l.subList(Math.max(0, l.size() - 40), l.size()));
    }

    @Test
    void theNukeWalksToTheEntryCornerAndBreaksFromThere() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        Pos entry = NukeEntry.stand(sim.moss);
        assertNotNull(entry);
        loadsOnlyAt(sim, entry);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state() + tail(log));
        List<String> h = sim.bot.history;
        int walk = h.indexOf("walkTo " + entry);
        int brk = first(h, "break", 0);
        assertTrue(walk >= 0, "walked to the entry corner: " + h);
        assertTrue(brk > walk, "broke after it: " + h);
        for (int i = walk + 1; i < brk; i++) assertFalse(h.get(i).startsWith("walkTo"), "no walk on to the station: " + h);
        assertTrue(log.lines.stream().anyMatch(l -> l.contains("walking to " + entry.key() + " by the NE corner")),
            tail(log));
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void whenTheChunksDoNotLoadThereItWalksOnToTheStation() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        Pos entry = NukeEntry.stand(sim.moss);
        assertNotNull(entry);
        Pos station = sim.moss.mapStandAt != null ? sim.moss.mapStandAt : sim.moss.center;
        loadsOnlyAt(sim, station);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state() + tail(log));
        List<String> h = sim.bot.history;
        int walk = h.indexOf("walkTo " + entry);
        assertTrue(walk >= 0, "walked to the entry corner first: " + h);
        int on = first(h, "walkTo", walk + 1);
        int brk = first(h, "break", 0);
        assertTrue(on > walk && brk > on, "then on to the station, then the breaks: " + h);
        assertTrue(log.lines.stream().anyMatch(l -> l.contains("still not all loaded at the entry corner")), tail(log));
    }

    @Test
    void withoutTheSwitchTheLoadWalkGoesToTheStation() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, false);
        Pos entry = NukeEntry.stand(sim.moss);
        assertNotNull(entry);
        Pos station = sim.moss.mapStandAt != null ? sim.moss.mapStandAt : sim.moss.center;
        loadsOnlyAt(sim, station);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state() + tail(log));
        assertEquals(-1, sim.bot.history.indexOf("walkTo " + entry), sim.bot.history.toString());
        assertTrue(log.lines.stream().noneMatch(l -> l.contains("build.nukeEntryCorner")), tail(log));
    }
}
