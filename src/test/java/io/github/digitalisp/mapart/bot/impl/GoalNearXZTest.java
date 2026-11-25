package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalNearXZTest {
    @Test
    void horizontalRadiusWithVerticalTolerance() {
        GoalNearXZ g = new GoalNearXZ(10, 64, 10, 2, 1);
        assertTrue(g.isInGoal(10, 64, 10));
        assertTrue(g.isInGoal(12, 64, 10));
        assertTrue(g.isInGoal(11, 65, 11));
        assertFalse(g.isInGoal(12, 64, 12), "diagonal 2,2 is 2.83 away");
        assertFalse(g.isInGoal(10, 66, 10), "2 above, tolerance 1");
        assertTrue(GoalNearXZ.inGoal(0, 0, 0, 0, 0));
        assertFalse(GoalNearXZ.inGoal(1, 0, 0, 0, 0));
    }
}
