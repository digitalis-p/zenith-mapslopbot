package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.PacingSim.Lane;
import io.github.digitalisp.mapart.bot.impl.PacingSim.Net;
import io.github.digitalisp.mapart.bot.impl.PacingSim.Policy;
import io.github.digitalisp.mapart.bot.impl.PacingSim.Result;
import io.github.digitalisp.mapart.bot.impl.PacingSim.Server;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pacer and confirmation classifier against a simulated 2b2t connection ({@link PacingSim}): placements per second
 * and cells given up (skipped after a retry), before ({@link Policy#LEGACY}) and after ({@link Policy#NEW}) this
 * change, on the same lanes, connections and seeds. Prints the comparison.
 */
class PacingSimTest {
    private static final int SEEDS = 6;

    /** Sums over {@link #SEEDS} seeds. */
    private record Agg(double perSecond, int skipped, int serverRejected, int late, int clicks, List<Result> runs) {
        static Agg of(Policy p, Net net, Server srv, Lane lane, int start, int min, int max) {
            double rate = 0;
            int skipped = 0;
            int rejected = 0;
            int late = 0;
            int clicks = 0;
            List<Result> runs = new ArrayList<>();
            for (long seed = 1; seed <= SEEDS; seed++) {
                Result r = PacingSim.run(p, net, srv, lane, start, min, max, seed);
                runs.add(r);
                rate += r.perSecond();
                skipped += r.skipped();
                rejected += r.serverRejected();
                late += r.late();
                clicks += r.clicks();
            }
            return new Agg(rate / SEEDS, skipped, rejected, late, clicks, runs);
        }
    }

    private record Pair(Agg legacy, Agg now) {
        static Pair of(Net net, Server srv, Lane lane, int start, int min, int max) {
            Pair p = new Pair(Agg.of(Policy.LEGACY, net, srv, lane, start, min, max),
                Agg.of(Policy.NEW, net, srv, lane, start, min, max));
            System.out.printf("%-28s | %-24s | walk cap %-4s | interval start %d in [%d,%d] | before %5.2f/s, %3d skipped,"
                    + " %4d rejected by the server | after %5.2f/s, %3d skipped, %4d rejected, %3d late (x%.2f)%n",
                net.name(), srv.name(), lane.walkCap() > 0 ? String.format("%.0f/s", lane.walkCap() * 20) : "none",
                start, min, max, p.legacy.perSecond, p.legacy.skipped, p.legacy.serverRejected, p.now.perSecond,
                p.now.skipped, p.now.serverRejected, p.now.late, p.now.perSecond / p.legacy.perSecond);
            return p;
        }
    }

    @Test
    void connectionModelMatchesTheLiveRoundTrips() {
        Result r = PacingSim.run(Policy.NEW, Net.live2b2t(), Server.vanilla(), Lane.live(), 1, 1, 8, 1);
        // live replays (5 lanes, 3000 clicks): ack round trip p50 164-177 ms, p95 311-367 ms
        assertTrue(r.ackQuantile(0.5) >= 130 && r.ackQuantile(0.5) <= 200, "p50 " + r.ackQuantile(0.5));
        assertTrue(r.ackQuantile(0.95) >= 200 && r.ackQuantile(0.95) <= 400, "p95 " + r.ackQuantile(0.95));
    }

    @Test
    void stackEndsAreNoLongerRejectedAndTheLaneRunsAtFullSpeed() {
        // what the live misses were: clicks with a hotbar stack already used up by the clicks in flight
        Pair old = Pair.of(Net.live2b2t(), Server.vanilla(), Lane.live(), 2, 1, 8);
        assertTrue(old.legacy.serverRejected >= SEEDS * 4, "the old logic clicks used-up stacks");
        assertEquals(0, old.now.serverRejected, "no click with a used-up stack");
        assertEquals(0, old.now.skipped);
        assertEquals(0, old.legacy.skipped);
        assertTrue(old.now.perSecond >= 3 * old.legacy.perSecond, "default bounds [1,8]: before " + old.legacy.perSecond
            + "/s, after " + old.now.perSecond + "/s");
        for (Result r : old.now.runs) assertEquals(1, r.finalInterval(), "never slowed down");

        Pair capped = Pair.of(Net.live2b2t(), Server.vanilla(), Lane.live(), 1, 1, 2);
        assertEquals(0, capped.now.serverRejected);
        assertTrue(capped.now.perSecond >= 1.3 * capped.legacy.perSecond, "live bounds [1,2]");
        assertTrue(capped.now.perSecond >= 18, "about one placement per tick: " + capped.now.perSecond);
    }

    @Test
    void walkLimitedLaneGainsLess() {
        // the walk caps a 5-wide lane at about 11 placements/s; the pacing no longer costs anything below that
        Pair p = Pair.of(Net.live2b2t(), Server.vanilla(), new Lane(640, 6, 15, 0.55), 1, 1, 2);
        assertEquals(0, p.now.serverRejected);
        assertTrue(p.now.perSecond > p.legacy.perSecond);
        assertTrue(p.now.perSecond >= 10.5, "at the walk's cap: " + p.now.perSecond);
        Pair d = Pair.of(Net.live2b2t(), Server.vanilla(), new Lane(640, 6, 15, 0.55), 2, 1, 8);
        assertTrue(d.now.perSecond >= 1.4 * d.legacy.perSecond);
    }

    @Test
    void realRejectionsAndDropsSlowItDownWithoutLosingMoreCells() {
        Server bad = new Server("2% rejected, 1% dropped", 0.02, 0.01, 0);
        for (int[] b : new int[][]{{2, 1, 8}, {1, 1, 2}}) {
            Pair p = Pair.of(Net.live2b2t(), bad, Lane.live(), b[0], b[1], b[2]);
            assertTrue(p.now.perSecond >= 1.2 * p.legacy.perSecond);
            assertTrue(p.now.skipped <= p.legacy.skipped + 3, "same reliability: " + p.now.skipped + " vs "
                + p.legacy.skipped);
            assertTrue(p.now.runs.stream().anyMatch(r -> r.maxInterval() > b[1]), "rejections slowed it down");
        }
    }

    @Test
    void rateLimitIsFoundWithoutLosingMoreCells() {
        // a hypothetical server-side limit of 12 placements/s (2b2t showed none at 20/s)
        Server cap = new Server("cap 12/s", 0, 0, 12);
        for (int[] b : new int[][]{{2, 1, 8}, {1, 1, 2}}) {
            Pair p = Pair.of(Net.live2b2t(), cap, Lane.live(), b[0], b[1], b[2]);
            assertTrue(p.now.perSecond >= 1.1 * p.legacy.perSecond);
            assertTrue(p.now.perSecond <= 12.5, "within the limit on average");
            assertTrue(p.now.skipped <= p.legacy.skipped + 2, p.now.skipped + " vs " + p.legacy.skipped);
        }
    }

    @Test
    void lagStallsGiveLateConfirmationsNotLostCells() {
        // 2.5-4 s without answers: longer than the 40-tick window
        Pair p = Pair.of(Net.stalls(), Server.vanilla(), Lane.live(), 1, 1, 8);
        assertTrue(p.now.late > 0, "placements answered after the window count as late");
        assertEquals(0, p.now.skipped, "a late cell is not given up while its ack can still come");
        assertEquals(0, p.now.serverRejected, "no second click on a cell whose first is still in flight");
        assertTrue(p.legacy.skipped > 0, "the old logic gave up cells that were placed late");
        assertTrue(p.now.perSecond >= 1.3 * p.legacy.perSecond);
    }

    /**
     * The burst pacer ({@link Policy#BURST}, {@link PlacePacing#DEFAULT}) against the classifier it replaces as the
     * default ({@link Policy#NEW}, {@link PlacePacing#LEGACY}): random refusals (2b2t: isolated, about one event per
     * 1,000 clicks, whatever the rate) no longer cost the doubling and its recovery; a real rate limit (a hypothetical
     * 12/s cap) is still found, without losing more cells.
     */
    @Test
    void burstPacerKeepsItsSpeedThroughRandomRefusalsAndStillFindsARateLimit() {
        for (int[] b : new int[][]{{1, 1, 4}, {1, 1, 8}}) {
            for (Server srv : new Server[]{Server.vanilla(), new Server("0.1% rejected", 0.001, 0, 0),
                new Server("1.2% rejected", 0.012, 0, 0), new Server("2% rejected, 1% dropped", 0.02, 0.01, 0),
                new Server("cap 12/s", 0, 0, 12)}) {
                Agg now = Agg.of(Policy.NEW, Net.live2b2t(), srv, Lane.live(), b[0], b[1], b[2]);
                Agg burst = Agg.of(Policy.BURST, Net.live2b2t(), srv, Lane.live(), b[0], b[1], b[2]);
                String what = String.format("%-24s [%d,%d]: NEW %5.2f/s %d skipped, BURST %5.2f/s %d skipped",
                    srv.name(), b[1], b[2], now.perSecond, now.skipped, burst.perSecond, burst.skipped);
                System.out.println(what);
                assertTrue(burst.skipped <= now.skipped + 2, "no more cells lost: " + what);
                if (srv.rateCap() > 0) {
                    assertTrue(burst.perSecond <= 12.5, "within the limit on average: " + what);
                    assertTrue(burst.perSecond >= 0.95 * now.perSecond, what);
                } else if (srv.rejectRate() >= 0.01) {
                    assertTrue(burst.perSecond >= 1.08 * now.perSecond, "random refusals cost less: " + what);
                } else {
                    assertTrue(burst.perSecond >= now.perSecond - 0.05, what);
                    assertTrue(burst.perSecond >= 18.5, "about one placement per tick: " + what);
                }
            }
        }
    }

    @Test
    void sameSeedSameResult() {
        Result a = PacingSim.run(Policy.NEW, Net.stalls(), new Server("x", 0.02, 0.01, 12), Lane.live(), 1, 1, 8, 9);
        Result b = PacingSim.run(Policy.NEW, Net.stalls(), new Server("x", 0.02, 0.01, 12), Lane.live(), 1, 1, 8, 9);
        assertEquals(a.row(), b.row());
    }
}
