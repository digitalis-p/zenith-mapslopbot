package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fast nuke in a pointed-dripstone field ({@code build.nukePocketGuard}) on the art of the live blocks (2026-10-12,
 * ellen [0,6] on moss, fixtures/art/ellen_0_6.json: 6,744 targets, 2,056 pointed dripstone, 77 pots, dense fields and
 * checkerboards). The simulator's dripstone is a thin column (the box passes beside it, stands on it only over it) that
 * Baritone neither walks through nor onto. Tile cell (col, row) is (x, z) here.
 * <ul>
 *   <li>12:44: the nuke began with the bot's box against a dripstone cluster at col 112, row 2; every pathfinder step
 *       was cancelled ("MovementTraverse has taken too long"), the watchdog tripped, BLOCKED with nothing broken.
 *       Breaking the dripstone next to it freed it.</li>
 *   <li>13:25: 6,002 of 6,254 broken, then BLOCKED with 252 left near the platform centre, all inside
 *       dripstone-walled pockets the guard rightly would not walk into ("leaving ... for later" again and again).</li>
 * </ul>
 */
class NukeFieldTest {
    static final int Y = NukeRunTest.Y;
    static final Pos WEDGE = new Pos(112, Y, 2);
    static NukePocketsTest.Art art;

    @BeforeAll
    static void load() throws Exception {
        art = NukePocketsTest.load("/fixtures/art/ellen_0_6.json");
    }

    static NukeRunTest.Scenario scenario(String name, boolean guard, double x, double z) {
        NukeRunTest.Scenario sc = new NukeRunTest.Scenario(name);
        sc.blocks = new LinkedHashMap<>(art.targets());
        sc.others = new LinkedHashMap<>(art.others());
        sc.pocketGuard = guard;
        sc.startX = x;
        sc.startZ = z;
        sc.maxTicks = 80_000;
        sc.perTick = 1; // live: build.nukeStartsPerTick 1
        sc.client = c -> {
            c.lowIds = art.lowIds();
            c.wallIds = NukePocketsTest.WALL_IDS;
            c.clearableIds = NukePocketsTest.WALL_IDS;
            c.pathing = true;
            c.elevation = true;
        };
        return sc;
    }

    static long wallsBroken(NukeRunTest.Result r) {
        return r.server().destroys.stream().filter(d -> NukePocketsTest.WALL_IDS.contains(d.block().id())).count();
    }

    static void print(NukeRunTest.Result r) {
        System.out.println(r.line() + "; walls broken " + wallsBroken(r) + ", watchdog trips " + r.run().watchdogTrips()
            + (r.run().failure().isEmpty() ? "" : "; " + r.run().failure()));
        r.sim().log.stream().filter(l -> l.contains("broke ")).limit(8).forEach(l -> System.out.println("    " + l));
    }

    @Test
    void theWholeFieldIsNukedBreakingOnlyTheDripstoneInTheWay() {
        // from the north-east corner, where the nuke starts after the map counter
        NukeRunTest.Result r = scenario("[0,6] whole art, guard on", true, 120.5, 0.5).run();
        print(r);
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(r.targets().size(), r.run().done());
        assertEquals(0, r.run().watchdogTrips(), r.run().summaryLine("x"));
        // the pocket targets are reached by breaking into their pockets, a few dozen of the 2,133 walls
        assertTrue(r.sim().log.stream().anyMatch(l -> l.contains("breaking into the pocket of")), "no break-in");
        assertTrue(wallsBroken(r) < 100, "walls broken " + wallsBroken(r));
        r.assertRules(1);
    }

    @Test
    void aBotWedgedAgainstADripstoneClusterBreaksTheWallsAgainstItsBox() {
        // 12:44: the box against the cluster around col 112, row 2 (the bot at x .5, z .1 of its cell); every step is
        // cancelled while a dripstone touches the box there
        for (boolean guard : new boolean[]{false, true}) {
            NukeRunTest.Scenario sc = scenario("wedged at the cluster, guard " + (guard ? "on" : "off"), guard,
                WEDGE.x() + 0.5, WEDGE.z() + 0.1);
            var client = sc.client;
            sc.client = c -> {
                client.accept(c);
                c.wedged = () -> Math.hypot(c.x - (WEDGE.x() + 0.5), c.z - (WEDGE.z() + 0.1)) < 0.3
                    && c.blockNearBox(0.15, b -> NukePocketsTest.WALL_IDS.contains(b.id()));
            };
            NukeRunTest.Result r = sc.run();
            print(r);
            if (!guard) {
                assertEquals(TaskStatus.FAILED, r.status());
                assertTrue(r.run().failure().contains("no progress"), r.run().failure());
                continue;
            }
            assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
            assertEquals(r.targets().size(), r.run().done());
            assertTrue(r.sim().log.stream().anyMatch(l -> l.contains("wedged at " + WEDGE)), "not unwedged");
            assertTrue(wallsBroken(r) < 100, "walls broken " + wallsBroken(r));
        }
    }
}
