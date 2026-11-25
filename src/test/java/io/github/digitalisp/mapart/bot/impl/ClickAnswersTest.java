package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The answer to the last container click ({@link ClickAnswers}, {@code build.containerAnswerWaits}): a slot update of a
 * slot it changed, a cursor update or the menu's contents, each showing what the click left; never another slot's update
 * (the sorter's hoppers), never the answer to an earlier click, never another menu.
 */
class ClickAnswersTest {
    private static final int MENU = 7;
    /** A top-up's last click: the rest goes back into chest slot 0 (not empty), the cursor is empty. */
    private static final Map<Integer, Boolean> PUT_BACK = Map.of(0, false);

    @Test
    void theClickedSlotShowingWhatTheClickLeftIsTheAnswer() {
        ClickAnswers a = new ClickAnswers();
        long t = a.sent(MENU, PUT_BACK, true);
        assertFalse(a.answered(t));
        a.slot(MENU, 0, false);
        assertTrue(a.answered(t));
    }

    @Test
    void hopperUpdatesOfOtherSlotsAreNoAnswer() {
        ClickAnswers a = new ClickAnswers();
        long t = a.sent(MENU, PUT_BACK, true);
        for (int s = 1; s < 27; s++) {
            a.slot(MENU, s, false);
            a.slot(MENU, s, true);
        }
        assertFalse(a.answered(t), "26 chest slots changed by hoppers");
        assertEquals(52, a.ignored());
    }

    @Test
    void theAnswerToAnEarlierClickIsNoAnswer() {
        ClickAnswers a = new ClickAnswers();
        a.sent(MENU, Map.of(0, true), false);   // pick up slot 0
        a.sent(MENU, Map.of(60, false), false); // onto the partial stack: the cursor keeps its old count
        long last = a.sent(MENU, PUT_BACK, true);
        a.slot(MENU, 0, true);  // the pick-up's answer: slot 0 empty
        a.cursor(false);        // the middle click's: the cursor holds the rest
        a.slot(MENU, 60, false);
        assertFalse(a.answered(last));
        a.cursor(true);
        assertTrue(a.answered(last), "the cursor emptied: the last click's answer");
    }

    @Test
    void theMenusContentsAnswerWhenTheyShowTheClick() {
        ClickAnswers a = new ClickAnswers();
        long t = a.sent(MENU, Map.of(3, true), null); // a shift-click of chest slot 3 (whole stack)
        boolean[] before = new boolean[63];
        before[3] = false; // an earlier shift-click's resync: slot 3 still full
        a.content(MENU, before, true);
        assertFalse(a.answered(t));
        boolean[] after = new boolean[63];
        after[3] = true;
        a.content(MENU, after, true);
        assertTrue(a.answered(t));
    }

    @Test
    void anotherMenuIsNoAnswerAndALaterAnswerCoversTheEarlierClicks() {
        ClickAnswers a = new ClickAnswers();
        long gate = a.sent(MENU, PUT_BACK, true);
        a.slot(MENU + 1, 0, false);
        assertFalse(a.answered(gate), "another menu's slot 0");
        long last = a.sent(MENU, Map.of(5, true), null);
        assertFalse(a.answered(gate));
        boolean[] c = new boolean[63];
        c[5] = true;
        a.content(MENU, c, true);
        assertTrue(a.answered(last));
        assertTrue(a.answered(gate), "answers come in order: the last one's means the gate's is in");
        assertFalse(a.answered(0), "no click sent");
    }

    @Test
    void aShiftClickPredictsNoCursor() {
        ClickAnswers a = new ClickAnswers();
        long t = a.sent(MENU, Map.of(3, true), null);
        a.cursor(true);
        assertFalse(a.answered(t), "a cursor update says nothing about a shift-click");
        a.slot(MENU, 3, true);
        assertTrue(a.answered(t));
    }
}
