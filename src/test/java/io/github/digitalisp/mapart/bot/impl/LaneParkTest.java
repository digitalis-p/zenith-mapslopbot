package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine stops a lane to eat ({@link LaneRun#requestPark}): no click after the request, parked only once nothing
 * waits for the server, and the same lane finishes after {@link LaneRun#resume}, also when the bot was moved meanwhile
 * (food fetched from a chest). Simulated platform with full blocks and carpets, server confirmations delayed.
 */
class LaneParkTest {
    private static final PlacementListener NONE = new PlacementListener() {
        @Override public void placed(Placement placement) { }
        @Override public void skipped(Placement placement, String reason) { }
    };

    private static int shown(TileRunner r, Lane lane) {
        int n = 0;
        for (Placement p : lane.placements())
            if (TopFaceRules.equivalent(p.block(), r.world.getOrDefault(p.pos(), BlockSpec.AIR))) n++;
        return n;
    }

    private static final class Probe {
        long requestedAt = -1;
        long parkedAt = -1;
        int clicksAtRequest;
        int clicksAtPark;
        int acceptedAtPark;
        int shownAtPark;
        int baritoneAtResume;
    }

    /** Runs the first lane of a mixed art, asks for a park a third of the way in, resumes 200 ticks later. */
    private static Probe run(TileRunner r, Lane lane, boolean moveWhileParked, TaskStatus[] out) {
        return run(r, lane, moveWhileParked, 0, out);
    }

    private static Probe run(TileRunner r, Lane lane, boolean moveWhileParked, double arrivalShift, TaskStatus[] out) {
        Probe pr = new Probe();
        int third = lane.placements().size() / 3;
        out[0] = r.sim.runLane(lane, r.params(), NONE, 60_000, (t, run) -> {
            if (pr.requestedAt < 0 && r.sim.accepted >= third) {
                pr.requestedAt = t;
                pr.clicksAtRequest = r.sim.clicks;
                run.requestPark();
            }
            if (!run.parked()) return true;
            if (pr.parkedAt < 0) {
                pr.parkedAt = t;
                pr.clicksAtPark = r.sim.clicks;
                pr.acceptedAtPark = r.sim.accepted;
                pr.shownAtPark = shown(r, lane);
                if (moveWhileParked) r.sim.teleport(r.sim.x + 9, r.sim.y, r.sim.z + 12); // to the food chest and back
            }
            if (t - pr.parkedAt < 200) return false; // the engine does not tick a parked task
            pr.baritoneAtResume = r.sim.baritoneWalks;
            r.sim.arrivalShiftX = arrivalShift;
            run.resume();
            return true;
        });
        return pr;
    }

    private static Lane firstLane(TileRunner r, TileArt art) {
        Lane lane = r.plan(art).getFirst();
        Pos f = lane.from();
        r.sim.teleport(f.x() + 0.5, f.y(), f.z() + 0.5);
        return lane;
    }

    @Test
    void parksAtASafePointAndFinishesTheSameLane() {
        TileRunner r = new TileRunner(31);
        r.sim.confirmDelay = 6; // the server takes a while: the park waits for what was sent
        TileArt art = TileRunner.noise(31, 0, 0, 128, 10, 0.3);
        Lane lane = firstLane(r, art);
        TaskStatus[] s = new TaskStatus[1];
        Probe pr = run(r, lane, false, s);

        assertTrue(pr.parkedAt > 0, "parked");
        assertTrue(r.sim.log.stream().anyMatch(l -> l.contains("stopped at a safe point (RUN")), "mid-walk\n"
            + String.join("\n", r.sim.log));
        assertEquals(pr.clicksAtRequest, pr.clicksAtPark, "no click after the park request");
        assertEquals(pr.acceptedAtPark, pr.shownAtPark, "nothing waited for the server when parked");
        assertTrue(pr.parkedAt - pr.requestedAt <= r.build.placeConfirmTicks + 10,
            "parked " + (pr.parkedAt - pr.requestedAt) + " ticks after the request");
        assertEquals(TaskStatus.SUCCESS, s[0], String.join("\n", r.sim.log));
        assertEquals(lane.placements().size(), shown(r, lane), "the whole lane placed after the stop\n" + r.sim.log);
        assertEquals(0, r.sim.wrongClicks);
        assertEquals(0, r.sim.boxRejects);
        assertEquals(0, r.sim.fellOff);
    }

    /** Live 2026-10-13, remu [1,2] after a fight: the walk back arrived off the park point; the run walked on anyway. */
    @Test
    void aWalkBackThatArrivesOffTheStopTriesOnceMoreAndWalksOn() {
        TileRunner r = new TileRunner(33);
        TileArt art = TileRunner.noise(33, 0, 0, 128, 10, 0.3);
        Lane lane = firstLane(r, art);
        TaskStatus[] s = new TaskStatus[1];
        Probe pr = run(r, lane, true, 1.2, s);
        String log = String.join("\n", r.sim.log);
        assertTrue(pr.parkedAt > 0, "parked");
        assertEquals(TaskStatus.SUCCESS, s[0], log);
        assertTrue(log.contains("once more, to within 1.5 blocks of the stop"), log);
        assertTrue(!log.contains("left to stand spots after a stop"), log);
        assertEquals(lane.placements().size(), shown(r, lane), "the whole lane placed\n" + log);
        assertEquals(0, r.sim.wrongClicks);
    }

    @Test
    void walksBackWhenMovedWhileParked() {
        TileRunner r = new TileRunner(32);
        TileArt art = TileRunner.noise(32, 0, 0, 128, 10, 0.3);
        Lane lane = firstLane(r, art);
        TaskStatus[] s = new TaskStatus[1];
        Probe pr = run(r, lane, true, s);

        assertTrue(pr.parkedAt > 0, "parked");
        assertEquals(TaskStatus.SUCCESS, s[0], String.join("\n", r.sim.log));
        assertTrue(r.sim.baritoneWalks > pr.baritoneAtResume, "walked back with Baritone\n" + String.join("\n", r.sim.log));
        assertTrue(r.sim.log.stream().anyMatch(l -> l.contains("moved; walking back")), String.join("\n", r.sim.log));
        assertEquals(lane.placements().size(), shown(r, lane), "the whole lane placed\n" + String.join("\n", r.sim.log));
        assertEquals(0, r.sim.wrongClicks);
        assertEquals(0, r.sim.boxRejects);
    }
}
