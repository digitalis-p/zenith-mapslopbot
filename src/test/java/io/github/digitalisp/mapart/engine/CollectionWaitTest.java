package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The item collection run-on after the clear: off after a quiet period without items, the cap, no count. */
class CollectionWaitTest {
    private static final OptionalInt NONE = OptionalInt.empty();

    private static OptionalInt n(int items) {
        return OptionalInt.of(items);
    }

    private static void waits(CollectionWait w, long at, OptionalInt items, String note) {
        CollectionWait.Step s = w.poll(at, items);
        assertFalse(s.done(), "done at " + at + ": " + s.text());
        assertEquals(note, s.text());
    }

    private static void done(CollectionWait w, long at, OptionalInt items, String line) {
        CollectionWait.Step s = w.poll(at, items);
        assertTrue(s.done(), "still waiting at " + at + ": " + s.text());
        assertEquals(line, s.text());
    }

    @Test
    void offAQuietPeriodAfterTheLastItemWent() {
        CollectionWait w = new CollectionWait(10_000, 30_000, 3_000);
        waits(w, 10_000, n(5), "5 items left, at most 30 s");
        waits(w, 11_000, n(1), "1 item left, at most 29 s");
        waits(w, 12_000, n(0), "no items left, off in 3 s");
        waits(w, 13_000, n(0), "no items left, off in 2 s");
        waits(w, 14_000, n(0), "no items left, off in 1 s");
        done(w, 15_000, n(0), "collection: 0 items left after 2.0 s; carts off after 5.0 s");
    }

    @Test
    void noItemsAtTheClearGoesOffAfterTheQuietPeriodOnly() {
        CollectionWait w = new CollectionWait(0, 30_000, 3_000);
        waits(w, 0, n(0), "no items left, off in 3 s");
        waits(w, 2_950, n(0), "no items left, off in 1 s");
        done(w, 3_000, n(0), "collection: 0 items left after 0.0 s; carts off after 3.0 s");
    }

    @Test
    void anItemSeenAgainStartsTheQuietPeriodOver() {
        CollectionWait w = new CollectionWait(0, 30_000, 3_000);
        waits(w, 0, n(0), "no items left, off in 3 s");
        waits(w, 2_000, n(0), "no items left, off in 1 s");
        waits(w, 2_500, n(1), "1 item left, at most 28 s");
        waits(w, 3_000, n(0), "no items left, off in 3 s");
        waits(w, 5_000, n(0), "no items left, off in 1 s");
        done(w, 6_000, n(0), "collection: 0 items left after 3.0 s; carts off after 6.0 s");
    }

    @Test
    void theCapEndsTheWaitWithItemsLeft() {
        CollectionWait w = new CollectionWait(0, 30_000, 3_000);
        waits(w, 0, n(12), "12 items left, at most 30 s");
        waits(w, 29_000, n(4), "4 items left, at most 1 s");
        done(w, 30_000, n(4), "collection: cap reached with 4 items left; carts off");
        CollectionWait one = new CollectionWait(0, 30_000, 3_000);
        done(one, 30_000, n(1), "collection: cap reached with 1 item left; carts off");
    }

    @Test
    void withoutACountTheCapIsAFixedRunOn() {
        CollectionWait w = new CollectionWait(0, 30_000, 3_000);
        waits(w, 0, NONE, "30 s left");
        waits(w, 10_000, NONE, "20 s left");
        waits(w, 29_001, NONE, "1 s left");
        done(w, 30_000, NONE, "collection: fixed run-on of 30 s over (no item count); carts off");
    }

    @Test
    void aPollWithoutACountBreaksTheQuietPeriod() {
        CollectionWait w = new CollectionWait(0, 30_000, 3_000);
        waits(w, 0, n(0), "no items left, off in 3 s");
        waits(w, 2_000, NONE, "28 s left");
        waits(w, 3_000, n(0), "no items left, off in 3 s");
        done(w, 6_000, n(0), "collection: 0 items left after 3.0 s; carts off after 6.0 s");

        CollectionWait lost = new CollectionWait(0, 30_000, 3_000);
        waits(lost, 0, n(7), "7 items left, at most 30 s");
        done(lost, 30_000, NONE, "collection: cap reached, items left unknown (no item count now); carts off");
    }

    @Test
    void noQuietPeriodLeavesAtTheFirstEmptyCount() {
        done(new CollectionWait(0, 30_000, 0), 1_000, n(0), "collection: 0 items left after 1.0 s; carts off after 1.0 s");
        done(new CollectionWait(0, 30_000, -5_000), 0, n(0), "collection: 0 items left after 0.0 s; carts off after 0.0 s");
    }

    @Test
    void aQuietPeriodLongerThanTheCapEndsAtTheCap() {
        CollectionWait w = new CollectionWait(0, 5_000, 10_000);
        waits(w, 0, n(0), "no items left, off in 5 s");
        done(w, 5_000, n(0), "collection: cap reached with 0 items left; carts off");
    }
}
