package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.PlaceTracker.Outcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PlaceTracker} with the BURST pacing ({@link PlacePacing#DEFAULT}): stack ends told apart, settle ticks. */
class StackEndTrackerTest {
    private final AdaptivePacer pacer = burstPacer();
    private final PlaceTracker<String> t = new PlaceTracker<>(pacer, 40, 1, true);

    private static AdaptivePacer burstPacer() {
        AdaptivePacer p = new AdaptivePacer(1, 1, 8, 16, 100);
        p.configure(PlacePacing.DEFAULT);
        return p;
    }

    @Test
    void theLastItemsOfAStackRejectedAreAStackEnd() {
        t.sent("a", 10, 5, 3, false, 1); // the cache showed 1 left for new clicks
        t.observeAck(5, 14, slot -> false);
        assertEquals(Outcome.STACK_END, t.check("a", 16));
        assertEquals(1, t.rejected(), "counted among the rejected");
        assertEquals(1, t.stackEnds());
        assertEquals(1, pacer.interval(), "no rate signal");
        assertEquals(1, pacer.stackEnds());
        assertTrue(t.summary().endsWith("; 1 of the rejected at stack ends"), t.summary());
    }

    @Test
    void aSlotShownEmptyAfterTheAckIsAStackEnd() {
        t.sent("a", 10, 5, 3, false, 30); // the cache was wrong by more than the margin
        t.observeAck(5, 14, slot -> false);
        t.observeAck(5, 15, slot -> slot == 3); // the slot update came a tick after the ack
        assertEquals(Outcome.STACK_END, t.check("a", 16));
        assertEquals(1, pacer.interval());
    }

    @Test
    void anotherSlotEmptyIsNoStackEnd() {
        t.sent("a", 10, 5, 3, false, 30);
        t.observeAck(5, 14, slot -> slot == 4);
        assertEquals(Outcome.REJECTED, t.check("a", 16));
        assertEquals(0, t.stackEnds());
        assertEquals(1, pacer.isolated(), "a refusal: an isolated event");
        assertEquals(1, pacer.interval());
    }

    @Test
    void aDropIsNeverAStackEnd() {
        t.sent("x", 9, 4, 3, true, 30);
        t.answered("x", 12); // some click was answered: drops can be seen
        t.placed("x", 12);
        t.sent("a", 10, 5, 3, true, 1);
        t.observeAck(6, 14, slot -> true); // a later click's ack covers it; no answer of its own
        assertEquals(Outcome.DROPPED, t.check("a", 16));
        assertEquals(1, pacer.interval(), "drops leave the interval unchanged");
        assertEquals(1, pacer.drops());
        assertEquals(0, t.stackEnds());
    }

    @Test
    void withoutTheClassificationItIsARejectionAsBefore() {
        AdaptivePacer legacy = new AdaptivePacer(1, 1, 8, 16, 100);
        PlaceTracker<String> old = new PlaceTracker<>(legacy, 40);
        old.sent("a", 10, 5, 3, false, 1);
        old.observeAck(5, 14, slot -> true);
        assertEquals(Outcome.REJECTED, old.check("a", 16));
        assertEquals(0, old.stackEnds());
        assertEquals(2, legacy.interval());
        assertTrue(old.summary().endsWith("window 40 t"), old.summary());
    }

    @Test
    void aClickStopsCountingAgainstItsStackSettleTicksAfterItsAck() {
        AdaptivePacer p2 = new AdaptivePacer(1, 1, 8, 16, 100);
        PlaceTracker<String> two = new PlaceTracker<>(p2, 40); // the previous rule: 2 ticks
        for (PlaceTracker<String> x : java.util.List.of(t, two)) {
            x.sent("a", 10, 5, 3);
            x.sent("b", 11, 6, 3);
            x.observeAck(4, 13);
            assertEquals(2, x.inFlight(3));
            x.observeAck(5, 14); // "a" acked
            assertEquals(2, x.inFlight(3), "the slot update may come a few ms after the ack");
        }
        t.observeAck(5, 15);
        two.observeAck(5, 15);
        assertEquals(1, t.inFlight(3), "settle 1: one tick after its ack");
        assertEquals(2, two.inFlight(3), "settle 2: still counted");
        two.observeAck(5, 16);
        assertEquals(1, two.inFlight(3));
    }
}
