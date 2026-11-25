package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the bot backs off to after a hit on a creeper ({@link CreeperRetreat}). */
class CreeperRetreatTest {
    /** A floor of full blocks at y = 63 from x = -20..+20, z = -20..+20 (feet at y 64), all loaded; the rest unloaded. */
    private static final class Floor implements EscapePlanner.Cells {
        int minX = -20, maxX = 20, minZ = -20, maxZ = 20;
        final List<Pos> zone = new ArrayList<>();

        @Override
        public boolean isLoaded(Pos p) {
            return p.x() >= minX - 1 && p.x() <= maxX + 1 && p.z() >= minZ - 1 && p.z() <= maxZ + 1;
        }

        @Override
        public double collisionTop(Pos p) {
            return p.y() == 63 && p.x() >= minX && p.x() <= maxX && p.z() >= minZ && p.z() <= maxZ ? 1.0 : 0;
        }

        @Override
        public boolean hasFluid(Pos p) {
            return false;
        }

        @Override
        public boolean allowed(Pos feet) {
            return !zone.contains(feet);
        }
    }

    private static List<double[]> at(double x, double z) {
        return List.<double[]>of(new double[]{x, z});
    }

    @Test
    void theTargetIsOnTheFarSideAndBeyondTheDistance() {
        Floor w = new Floor();
        Pos bot = new Pos(0, 64, 0);
        List<CreeperRetreat.Candidate> plan = CreeperRetreat.plan(w, bot, at(2.5, 0.5), 9);
        assertFalse(plan.isEmpty());
        CreeperRetreat.Candidate first = plan.getFirst();
        assertTrue(first.farSide());
        assertTrue(first.feet().x() < 0, "away from a creeper on the +x side: " + first.feet().key());
        assertTrue(Math.hypot(first.feet().x() + 0.5 - 2.5, first.feet().z() + 0.5 - 0.5) >= 9);
        assertTrue(first.distSq() <= 11 * 11, "the shortest walk that does it: " + first.feet().key());
        for (CreeperRetreat.Candidate c : plan) {
            assertTrue(c.feet().y() == 64 || c.feet().y() == 63, "the bot's level or one lower");
            assertTrue(Math.hypot(c.feet().x() + 0.5 - 2.5, c.feet().z() - 0.0) >= 9);
        }
    }

    @Test
    void onlyLoadedFloorIsChosenNoGoZonesAndEdgesKept() {
        Floor w = new Floor();
        w.minX = -4; // the floor ends 4 blocks behind the bot: the far side has no 9 blocks of floor
        Pos bot = new Pos(0, 64, 0);
        List<CreeperRetreat.Candidate> plan = CreeperRetreat.plan(w, bot, at(2.5, 0.5), 9);
        assertFalse(plan.isEmpty(), "other directions still have floor");
        for (CreeperRetreat.Candidate c : plan) {
            assertTrue(c.feet().x() >= -4 && c.feet().x() <= 20 && c.feet().z() >= -20 && c.feet().z() <= 20,
                "on the floor: " + c.feet().key());
        }
        assertFalse(plan.getFirst().farSide() && plan.getFirst().feet().x() > 0);
        // a no-go zone cell is never a target
        Floor z = new Floor();
        z.zone.add(new Pos(-7, 64, 0));
        z.zone.add(new Pos(-6, 64, 0));
        for (CreeperRetreat.Candidate c : CreeperRetreat.plan(z, bot, at(2.5, 0.5), 9)) {
            assertFalse(z.zone.contains(c.feet()));
        }
        // no floor around at all: nothing
        Floor none = new Floor();
        none.minX = none.maxX = none.minZ = none.maxZ = 100;
        assertTrue(CreeperRetreat.plan(none, bot, at(2.5, 0.5), 9).isEmpty());
        assertTrue(CreeperRetreat.plan(w, bot, List.of(), 9).isEmpty());
    }

    @Test
    void everyCreeperIsKeptAwayFrom() {
        Floor w = new Floor();
        List<double[]> two = List.of(new double[]{2.5, 0.5}, new double[]{-8.5, 0.5});
        for (CreeperRetreat.Candidate c : CreeperRetreat.plan(w, new Pos(0, 64, 0), two, 9)) {
            assertTrue(CreeperRetreat.clear(c.feet(), two, 9), c.feet().key());
        }
    }

    @Test
    void thePathRuleRefusesWalkingPastTheCreeperAndDroppingOffTheFloor() {
        List<double[]> cs = at(5.5, 0.5);
        List<Pos> away = List.of(new Pos(0, 64, 0), new Pos(-1, 64, 0), new Pos(-2, 64, 0), new Pos(-3, 64, 1));
        assertNull(CreeperRetreat.pathRefusal(away, cs));
        List<Pos> past = List.of(new Pos(0, 64, 0), new Pos(1, 64, 0), new Pos(2, 64, 0), new Pos(3, 64, 0),
            new Pos(4, 64, 1), new Pos(5, 64, 2), new Pos(6, 64, 3));
        assertNotNull(CreeperRetreat.pathRefusal(past, cs));
        List<Pos> edge = List.of(new Pos(0, 64, 0), new Pos(-1, 64, 0), new Pos(-2, 63, 0), new Pos(-3, 62, 0));
        assertNotNull(CreeperRetreat.pathRefusal(edge, cs), "two blocks down is off the floor");
        List<Pos> step = List.of(new Pos(0, 64, 0), new Pos(-1, 63, 0));
        assertNull(CreeperRetreat.pathRefusal(step, cs), "a step down is fine");
        // started right next to it: walking away is fine, the cells nearer than the start are not
        List<Pos> out = List.of(new Pos(4, 64, 0), new Pos(3, 64, 0), new Pos(2, 64, 0));
        assertNull(CreeperRetreat.pathRefusal(out, cs));
        assertNull(CreeperRetreat.pathRefusal(List.of(), cs));
    }
}
