package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The run row plan and its key controller (a small physics model of the bot's walk). */
class RowRunTest {
    /** The soul sand platform's run row, tile-relative: z -3 at feet y 174 (absolute cells in the real config). */
    private static final List<Pos> ROWS = List.of(new Pos(0, 174, -3));

    @Test
    void aWalkWithStartAndGoalInTheBandRunsTheRow() {
        // prime (64, -4) to the stand row (126, -4): one row out from the walkway to the run row z -3
        RowRun.Plan p = RowRun.plan(ROWS, 64.5, 174, -3.5, 126, -4, 6, 12);
        assertNotNull(p);
        assertEquals(new Pos(64, 174, -3), p.start());
        assertEquals(126, p.goalX());
        assertEquals(1, p.dir());
        RowRun.Plan back = RowRun.plan(ROWS, 126.5, 174, -5.5, 70, -2, 6, 12);
        assertEquals(-1, back.dir());
    }

    @Test
    void outOfTheBandOrTooShortOrAnotherLevelIsAPlainWalk() {
        assertNull(RowRun.plan(ROWS, 64.5, 174, -3.5, 126, 30, 6, 12), "the goal is on the tile");
        assertNull(RowRun.plan(ROWS, 64.5, 174, 40.5, 126, -4, 6, 12), "the start is on the tile");
        assertNull(RowRun.plan(ROWS, 64.5, 174, -3.5, 70, -4, 6, 12), "6 blocks");
        assertNull(RowRun.plan(ROWS, 64.5, 150, -3.5, 126, -4, 6, 12), "another level");
        assertNull(RowRun.plan(List.of(), 64.5, 174, -3.5, 126, -4, 6, 12), "no run row");
    }

    /** The bot's walk in blocks per tick: forward adds 0.13 along the facing (soul speed makes it faster), friction 0.546. */
    private static final class Bot {
        double x = 64.5;
        double z = -3.5 + 0.4; // starts 0.4 off the centre line
        double vx;
        double vz;
        int ticks;

        void step(RowRun.Keys k, double drift) {
            double dir = k.yaw() < 0 ? 1 : -1;
            vx = vx * 0.546 + (k.forward() ? 0.16 * dir : 0);
            // right of an east facing bot is +z, of a west facing bot -z
            vz = vz * 0.546 + k.strafeRight() * 0.1 * dir + drift;
            x += vx;
            z += vz;
            ticks++;
        }
    }

    @Test
    void theKeyWalkKeepsTheFeetOnTheCentreLineAndStopsAtTheGoal() {
        RowRun.Plan p = RowRun.plan(ROWS, 64.5, 174, -3.5, 126, -4, 6, 12);
        RowRun.Controller c = new RowRun.Controller(p, 0.3);
        Bot b = new Bot();
        double worst = 0;
        boolean done = false;
        for (int i = 0; i < 400 && !done; i++) {
            RowRun.Keys k = c.tick(b.x, b.z, b.vx);
            assertNull(k.abort(), "tick " + i);
            if (k.done()) {
                done = true;
                break;
            }
            b.step(k, 0.012); // a steady push to the side
            if (i > 15) worst = Math.max(worst, Math.abs(b.z - (-3 + 0.5)));
        }
        assertTrue(done, "reached the goal x");
        assertTrue(worst < 0.35, "lateral error " + worst);
        assertTrue(Math.abs(b.x - 126.5) < 1.0, "stopped at " + b.x);
        assertTrue(b.ticks < 400, "ticks " + b.ticks);
    }

    @Test
    void aBotThatDoesNotMoveIsAbandonedAfterTwentyTicks() {
        RowRun.Plan p = RowRun.plan(ROWS, 64.5, 174, -3.5, 126, -4, 6, 12);
        RowRun.Controller c = new RowRun.Controller(p, 0.3);
        String abort = null;
        for (int i = 0; i < 40 && abort == null; i++) abort = c.tick(64.5, -2.5, 0).abort();
        assertNotNull(abort);
        assertTrue(abort.startsWith("no progress"), abort);
    }

    @Test
    void aBotFarOffTheRowIsAbandoned() {
        RowRun.Plan p = RowRun.plan(ROWS, 64.5, 174, -3.5, 126, -4, 6, 12);
        RowRun.Keys k = new RowRun.Controller(p, 0.3).tick(80, -1.0, 0.2);
        assertNotNull(k.abort());
        assertTrue(k.abort().startsWith("off the row"), k.abort());
    }

    @Test
    void theStrafeCorrectionIsTowardsTheCentreWhicheverWayItFaces() {
        RowRun.Plan east = RowRun.plan(ROWS, 64.5, 174, -3.5, 126, -4, 6, 12);
        // east facing: the right key moves to +z; the bot is north (z -3.0) of the centre -2.5: needs +z
        assertEquals(1, new RowRun.Controller(east, 0.3).tick(70, -3.0, 0.2).strafeRight());
        RowRun.Plan west = RowRun.plan(ROWS, 126.5, 174, -3.5, 70, -4, 6, 12);
        // west facing: right is -z; the bot is south (z -2.1) of the centre: needs -z, so the right key
        assertEquals(1, new RowRun.Controller(west, 0.3).tick(100, -2.1, -0.2).strafeRight());
        assertEquals(0, new RowRun.Controller(east, 0.3).tick(70, -2.5, 0.2).strafeRight());
    }
}
