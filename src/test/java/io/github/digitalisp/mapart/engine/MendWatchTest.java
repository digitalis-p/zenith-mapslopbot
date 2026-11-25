package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mend watchdog's clock. */
class MendWatchTest {
    private static final String PICK = "tool:minecraft:diamond_pickaxe";
    private static final String FEET = "armor:8";

    @Test
    void overdueOnceDueForTheLimitWithoutAMend() {
        MendWatch w = new MendWatch();
        w.due(PICK, 100);
        w.due(PICK, 500); // still the same episode: the clock keeps its start
        assertEquals(400, w.dueFor(PICK, 500));
        assertFalse(w.overdue(PICK, 1299, 1200));
        assertTrue(w.overdue(PICK, 1300, 1200));
        assertFalse(w.overdue(PICK, 1300, 0), "a limit of 0 switches the watchdog off");
        assertFalse(w.overdue(FEET, 99_999, 1200), "never due: never overdue");
    }

    @Test
    void aMendRestartsTheClockAndFineStopsIt() {
        MendWatch w = new MendWatch();
        w.due(PICK, 0);
        w.mended(PICK, 1000);
        assertFalse(w.overdue(PICK, 2000, 1200));
        assertTrue(w.overdue(PICK, 2200, 1200));
        w.fine(PICK);
        assertEquals(0, w.dueFor(PICK, 5000));
        assertFalse(w.overdue(PICK, 99_999, 1200));
        w.mended(FEET, 10); // not due: no clock starts
        assertEquals(0, w.dueFor(FEET, 5000));
    }

    @Test
    void eachEpisodeIsReportedOnce() {
        MendWatch w = new MendWatch();
        w.due(FEET, 0);
        assertTrue(w.report(FEET));
        assertFalse(w.report(FEET));
        w.mended(FEET, 100); // a mend raised it: a later overdue spell is reported again
        assertTrue(w.report(FEET));
        w.fine(FEET);
        assertTrue(w.report(FEET));
    }

    @Test
    void retainDropsItemsNoLongerCarriedOfThatPrefixOnly() {
        MendWatch w = new MendWatch();
        w.due(PICK, 0);
        w.due("tool:minecraft:diamond_axe", 0);
        w.due(FEET, 0);
        w.report("tool:minecraft:diamond_axe");
        w.retain("tool:", Set.of(PICK));
        assertTrue(w.overdue(PICK, 2000, 1200));
        assertFalse(w.overdue("tool:minecraft:diamond_axe", 2000, 1200));
        assertTrue(w.report("tool:minecraft:diamond_axe"), "its report flag went with it");
        assertTrue(w.overdue(FEET, 2000, 1200), "another prefix is untouched");
        w.clear();
        assertFalse(w.overdue(FEET, 2000, 1200));
    }
}
