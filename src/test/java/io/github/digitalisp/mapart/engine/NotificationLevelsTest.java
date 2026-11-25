package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.engine.SimFakes.RecordingNotifier.Msg;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.RED;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * Which engine notices reach Discord with {@code discord.verbose} off (the default): routine steps go through
 * {@code detail} (posted only when verbose, see DiscordNotifierTest), everything else always. On a clean run that leaves
 * the job's start, a start line for each later map, one "Map done" per stored map, and the job's end.
 */
class NotificationLevelsTest {
    private static final int LONG = 60_000;

    @Test
    void routineStepsAreDetailsAndEveryMapIsOneMapDone() {
        Sim sim = new Sim();
        TileArt left = Sim.single(WHITE, 4);
        TileArt right = Sim.single(RED, 4);
        Job job = sim.addJob("j0003", "Wide", 2, 1, List.of("moss"), (x, z) -> x == 0 ? left : right);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        List<String> always = sim.notifier.messages.stream().filter(m -> !m.kind().equals("detail"))
            .map(m -> m.kind() + ": " + m.title()).toList();
        // maps are named by tile and progress is the count of stored maps (an index such as "map 2 of 2" read as progress)
        assertEquals(List.of("info: Started Wide: tile 0,0 (0 of 2 maps done)", "image: Map done: Wide 0,0",
            "info: Started Wide: tile 1,0 (1 of 2 maps done)", "image: Map done: Wide 1,0", "info: Job done: Wide"), always,
            sim.notifier.toString());

        List<String> details = sim.notifier.of("detail").stream().map(Msg::title).toList();
        for (String step : List.of("Tile built", "Map locked", "Clearing", "Tile done"))
            assertTrue(details.contains(step), step + " is a detail: " + sim.notifier);
        assertFalse(details.stream().anyMatch(t -> t.startsWith("Started")), "every map's start is posted: " + details);
        Msg second = sim.notifier.messages.stream().filter(m -> m.title().equals("Started Wide: tile 1,0 (1 of 2 maps done)"))
            .findFirst().orElseThrow();
        assertEquals("#j0003 Wide [1,0] on platform moss: 512 blocks.", second.body(), "the label names the tile once");

        List<Msg> done = sim.notifier.of("image");
        assertTrue(done.get(0).body().startsWith("Wide, tile 0,0 on moss\n"), done.get(0).body());
        assertTrue(done.get(0).body().contains("\n1 of 2 maps done, this one took "), done.get(0).body());
        assertTrue(done.get(0).body().contains("\nOutput chest "), done.get(0).body());
        assertTrue(done.get(0).body().endsWith("[map-j0003-0-0.png]"), done.get(0).body());
        assertTrue(done.get(1).body().contains("\n2 of 2 maps done"), done.get(1).body());
    }

    @Test
    void aSingleMapJobPostsMapDoneAndJobDone() {
        Sim sim = new Sim();
        TileArt art = Sim.single(WHITE, 4);
        Job job = sim.addJob("j0001", "Solo", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        List<String> always = sim.notifier.messages.stream().filter(m -> !m.kind().equals("detail"))
            .map(m -> m.kind() + ": " + m.title()).toList();
        assertEquals(List.of("info: Started Solo", "image: Map done: Solo", "info: Job done: Solo"), always,
            sim.notifier.toString());
        assertTrue(sim.notifier.of("image").getFirst().body().contains("\n1 of 1 map done"), sim.notifier.toString());
    }

}
