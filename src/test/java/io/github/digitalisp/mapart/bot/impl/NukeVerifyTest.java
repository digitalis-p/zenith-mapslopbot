package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.DigServer.BlockType;
import io.github.digitalisp.mapart.model.Pos;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VERIFY's breaks of wrong blocks with the fast nuke, the rest of the art standing ({@code artCleared} false), on the art
 * of the live block (2026-10-12 17:29-17:33, ellen [0,2], fixtures/art/ellen_0_2.json; col, row = x, z here):
 * <ul>
 *   <li>a wrong blue carpet at col 60, row 126 sits in a checkerboard of carpets and correct purple terracotta: the
 *       pocket guard counted the terracotta (art, no target) as walls, so every walk there was refused ("steps into a
 *       pocket walled in by blocks that are no target"), passes 2-4 were spent on it, BLOCKED;</li>
 *   <li>the wedge escape broke correct carpets around the bot ("broke gray_carpet ... the clear removes them anyway"),
 *       which VERIFY then had to place again.</li>
 * </ul>
 * Full blocks that are no target are climbed; carpets, plates and candles are never walls; a wall that is art and must
 * go is logged as art to re-place.
 */
class NukeVerifyTest {
    static final int Y = NukeRunTest.Y;
    static final Pos WRONG = new Pos(60, Y, 126);
    /** The wrong light gray carpet of the [1,3] block (purple terracotta wanted). */
    static final Pos WRONG_13 = new Pos(72, Y, 126);
    static NukePocketsTest.Art art;

    @BeforeAll
    static void load() throws Exception {
        art = NukePocketsTest.load("/fixtures/art/ellen_0_2.json");
    }

    /** The whole art placed, the cells of {@code wrong} holding another block: those are the targets. */
    static NukeRunTest.Scenario verify(String name, Map<Pos, BlockType> wrong, double x, double z) {
        return verify(art, name, wrong, x, z);
    }

    /** {@link #verify(String, Map, double, double)} on another tile's art. */
    static NukeRunTest.Scenario verify(NukePocketsTest.Art art, String name, Map<Pos, BlockType> wrong, double x,
                                       double z) {
        NukeRunTest.Scenario sc = new NukeRunTest.Scenario(name);
        Map<Pos, BlockType> others = new LinkedHashMap<>(art.art());
        others.keySet().removeAll(wrong.keySet());
        sc.blocks = new LinkedHashMap<>(wrong);
        sc.others = others;
        sc.artCleared = false;
        sc.startX = x;
        sc.startZ = z;
        sc.maxTicks = 20_000;
        sc.perTick = 1;
        sc.client = c -> {
            c.lowIds = art.lowIds();
            c.wallIds = NukePocketsTest.WALL_IDS;
            c.clearableIds = NukePocketsTest.WALL_IDS;
            c.pathing = true;
            c.elevation = true;
        };
        return sc;
    }

    static long brokenOtherThanTargets(NukeRunTest.Result r) {
        return r.server().destroys.stream().filter(d -> !r.targets().contains(d.pos())).count();
    }

    @Test
    void aWrongCarpetAmongCorrectTerracottaIsReachedOverTheirTops() {
        BlockType blue = new BlockType("minecraft:blue_carpet", 0.1f, null, false);
        NukeRunTest.Result r = verify("wrong blue carpet, col 60 row 126", Map.of(WRONG, blue), 60.5, 100.5).run();
        System.out.println(r.line() + "; " + r.run().failure());
        r.sim().log.stream().filter(l -> l.startsWith("Fast nuke")).forEach(l -> System.out.println("    " + l));
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(1, r.run().done());
        assertFalse(r.sim().log.stream().anyMatch(l -> l.contains("steps into a pocket")), r.sim().log.toString());
        assertEquals(0, brokenOtherThanTargets(r), "only the wrong block");
    }

    @Test
    void aWedgeNeverBreaksCarpetsAndArtItBreaksIsToRePlace() {
        // the live wedge at col 11, row 31 among carpets and gray terracotta: wedged for the first 700 ticks
        BlockType blue = new BlockType("minecraft:blue_carpet", 0.1f, null, false);
        Pos far = new Pos(20, Y, 40);
        NukeRunTest.Scenario sc = verify("wedged among carpets", Map.of(far, blue), 11.5, 31.5);
        var client = sc.client;
        java.util.concurrent.atomic.AtomicInteger wedgedTicks = new java.util.concurrent.atomic.AtomicInteger();
        sc.client = c -> {
            client.accept(c);
            c.wedged = () -> wedgedTicks.incrementAndGet() < 700;
        };
        NukeRunTest.Result r = sc.run();
        System.out.println(r.line() + "; " + r.run().failure());
        r.sim().log.stream().filter(l -> l.startsWith("Fast nuke")).forEach(l -> System.out.println("    " + l));
        // the watchdog trips while wedged (it outlasts 300 ticks here): no wall touches the box, nothing is broken; the
        // only target is left for VERIFY's next round
        assertTrue(r.sim().log.stream().anyMatch(l -> l.contains("Fast nuke watchdog")), r.sim().log.toString());
        assertEquals(0, brokenOtherThanTargets(r), "nothing but targets broken");
        long lowBroken = r.server().destroys.stream()
            .filter(d -> !r.targets().contains(d.pos()) && art.lowIds().contains(d.block().id())).count();
        assertEquals(0, lowBroken, "carpets, plates, candles are no walls");
        assertTrue(r.sim().log.stream().noneMatch(l -> l.contains("the clear removes them anyway")),
            "outside the NUKE nothing is cleared afterwards");
    }

    /**
     * Live 2026-10-12 19:20, ellen [1,3] (fixtures/art/ellen_1_3.json): a light gray carpet placed where purple
     * terracotta belongs (col 72, row 126, the second last row), in a hole of cyan terracotta; the bot on the tops at
     * 71.8, 126.0, the carpet under its box. Four VERIFY rounds of 300 ticks sent nothing, BLOCKED.
     */
    @Test
    void aWrongCarpetUnderTheBotsBoxIsBroken() throws Exception {
        NukePocketsTest.Art a13 = NukePocketsTest.load("/fixtures/art/ellen_1_3.json");
        BlockType carpet = new BlockType("minecraft:light_gray_carpet", 0.1f, null, false);
        NukeRunTest.Scenario sc = verify(a13, "wrong carpet under the box, [1,3]", Map.of(WRONG_13, carpet), 71.8, 126.0);
        sc.startOnTop = true;
        NukeRunTest.Result r = sc.run();
        System.out.println(r.line() + "; " + r.run().failure());
        r.sim().log.stream().filter(l -> l.startsWith("Fast nuke")).forEach(l -> System.out.println("    " + l));
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(1, r.run().done());
        assertTrue(r.sim().log.stream().noneMatch(l -> l.contains("Fast nuke watchdog")), r.sim().log.toString());
        assertEquals(0, brokenOtherThanTargets(r), "only the wrong block");
        assertTrue(r.sim().ticks < 200, "broken from where the bot stands or one step off: " + r.sim().ticks + " ticks");
    }

    /**
     * The same, but no face of a block under the bot's box can be aimed at from where it stands (the live stall's
     * shape: 300 ticks without a click): the bot steps off onto a free cell beside it and breaks it from there.
     */
    @Test
    void aTargetUnderTheBoxNotAimableFromThereIsBrokenAfterOneStepOff() throws Exception {
        NukePocketsTest.Art a13 = NukePocketsTest.load("/fixtures/art/ellen_1_3.json");
        BlockType carpet = new BlockType("minecraft:light_gray_carpet", 0.1f, null, false);
        NukeRunTest.Scenario sc = verify(a13, "under the box, hidden from there", Map.of(WRONG_13, carpet), 71.8,
            126.0);
        sc.startOnTop = true;
        var client = sc.client;
        sc.client = c -> {
            client.accept(c);
            c.hideUnderBox = true;
            c.inGoalNoStep = true;
        };
        NukeRunTest.Result r = sc.run();
        System.out.println(r.line() + "; " + r.run().failure());
        r.sim().log.stream().filter(l -> l.startsWith("Fast nuke")).forEach(l -> System.out.println("    " + l));
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(1, r.run().done());
        assertTrue(r.sim().log.stream().noneMatch(l -> l.contains("Fast nuke watchdog")), r.sim().log.toString());
        assertEquals(0, brokenOtherThanTargets(r), "only the wrong block");
    }
}
