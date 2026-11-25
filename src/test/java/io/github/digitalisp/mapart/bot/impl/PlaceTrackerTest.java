package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.PlaceTracker.Outcome;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceTrackerTest {
    private final AdaptivePacer pacer = new AdaptivePacer(1, 1, 8, 16, 100);
    private final PlaceTracker<String> t = new PlaceTracker<>(pacer, 40);

    @Test
    void blockWithinTheWindowIsConfirmed() {
        t.observeAck(4, 9);
        t.sent("a", 10, 5, 1);
        t.observeAck(4, 12);
        assertNull(t.check("a", 12));
        assertEquals(Outcome.CONFIRMED, t.placed("a", 14));
        assertEquals(1, t.confirmed());
        assertEquals(List.of(4), t.confirmLatencies());
        assertEquals(1, pacer.confirmations());
        assertFalse(t.pending("a"));
        assertNull(t.check("a", 15), "resolved");
    }

    @Test
    void ackWithoutTheBlockIsARejectionAfterTheGrace() {
        t.sent("a", 10, 5, 1);
        t.observeAck(5, 14); // the server handled seq 5; the block updates came first on the wire
        assertEquals(List.of(4), t.ackLatencies());
        assertNull(t.check("a", 14));
        assertNull(t.check("a", 15));
        assertEquals(Outcome.REJECTED, t.check("a", 14 + PlaceTracker.REJECT_GRACE_TICKS));
        assertEquals(1, t.rejected());
        assertEquals(2, pacer.interval(), "a rejection doubles the interval");
    }

    @Test
    void blockShownInTheGraceTicksIsConfirmed() {
        t.sent("a", 10, 5, 1);
        t.observeAck(5, 14);
        assertNull(t.check("a", 14));
        assertEquals(Outcome.CONFIRMED, t.placed("a", 15));
        assertEquals(0, t.rejected());
    }

    @Test
    void laterAckCoversEarlierSequences() {
        t.sent("a", 10, 5, 1);
        t.sent("b", 11, 6, 1);
        t.observeAck(6, 15); // one ack for two clicks handled in one server tick
        assertEquals(Outcome.CONFIRMED, t.placed("b", 15));
        assertEquals(Outcome.REJECTED, t.check("a", 17));
    }

    @Test
    void withoutSequenceNothingBackByTheWindowIsUnknown() {
        t.sent("a", 10, -1, 1);
        t.observeAck(-1, 49);
        assertNull(t.check("a", 49));
        assertEquals(Outcome.UNKNOWN, t.check("a", 50));
        assertEquals(1, t.unknown());
        assertEquals(2, pacer.interval(), "unknown: one tick slower");
        assertEquals(1, pacer.unknowns());
    }

    @Test
    void withSequenceAnOverdueClickWaitsForItsAck() {
        t.sent("a", 10, 5, 1);
        assertNull(t.check("a", 50), "past the window: counted unknown, still waiting");
        assertEquals(1, t.unknown());
        assertEquals(2, pacer.interval());
        assertTrue(t.pending("a"));
        t.observeAck(5, 70); // the answer came in the end, without the block
        assertEquals(Outcome.REJECTED, t.check("a", 72));
        assertEquals(0, t.unknown(), "reclassified");
        assertEquals(1, t.rejected());
        assertEquals(2, pacer.interval(), "not slowed twice for one click");
    }

    @Test
    void overdueBlockIsLateAndWidensTheWindow() {
        t.sent("a", 10, 5, 1);
        assertNull(t.check("a", 50));
        assertEquals(Outcome.LATE, t.placed("a", 70));
        assertEquals(1, t.late());
        assertEquals(0, t.unknown());
        assertEquals(1, pacer.late());
        assertEquals(70, t.window(), "60-tick latency + margin");
    }

    @Test
    void givenUpClickWhoseBlockShowsLaterIsLate() {
        t.sent("a", 10, -1, 1);
        assertEquals(Outcome.UNKNOWN, t.check("a", 50));
        assertEquals(Outcome.LATE, t.placed("a", 55));
        assertEquals(1, t.late());
        assertEquals(0, t.unknown());
        assertNull(t.placed("a", 56), "counted once");
    }

    @Test
    void retryReplacesTheGivenUpClick() {
        t.sent("a", 10, -1, 1);
        assertEquals(Outcome.UNKNOWN, t.check("a", 50));
        t.sent("a", 52, -1, 1);
        assertEquals(Outcome.CONFIRMED, t.placed("a", 56));
        assertEquals(1, t.confirmed());
        assertEquals(1, t.unknown());
        assertEquals(2, t.clicks());
    }

    @Test
    void unansweredClickIsGivenUpAtTheWindowCap() {
        t.sent("a", 10, 5, 1);
        assertNull(t.check("a", 109));
        assertEquals(Outcome.UNKNOWN, t.check("a", 110));
        assertEquals(1, t.unknown());
    }

    @Test
    void clicksStayInFlightPerSlotUntilAckedAndSettled() {
        t.sent("a", 10, 1, 2);
        t.sent("b", 11, 2, 2);
        t.sent("c", 12, 3, 2);
        t.sent("d", 13, 4, 3);
        assertEquals(3, t.inFlight(2));
        assertEquals(1, t.inFlight(3));
        t.observeAck(2, 15);
        assertEquals(3, t.inFlight(2), "the slot update may still be on its way");
        t.observeAck(2, 16);
        assertEquals(3, t.inFlight(2));
        t.observeAck(2, 17);
        assertEquals(1, t.inFlight(2), "acked two ticks ago: the cache counts them");
        assertEquals(Outcome.CONFIRMED, t.placed("c", 17));
        assertEquals(1, t.inFlight(2), "c is placed but not acked: its item may still show in the cache");
        t.observeAck(4, 18);
        t.observeAck(4, 20);
        assertEquals(0, t.inFlight(2));
        assertEquals(0, t.inFlight(3));
    }

    @Test
    void clickWithoutSequenceStaysInFlightUntilResolved() {
        t.sent("a", 10, -1, 1);
        t.observeAck(-1, 11);
        assertEquals(1, t.inFlight(1));
        t.placed("a", 14);
        t.observeAck(-1, 14);
        assertEquals(1, t.inFlight(1));
        t.observeAck(-1, 15);
        assertEquals(0, t.inFlight(1));
    }

    @Test
    void staleAckFromARestartedCounterIsNotTakenForAnAnswer() {
        t.observeAck(500, 0); // left over from the last connection
        t.sent("a", 1, 3, 1); // the new connection's counter
        t.observeAck(500, 5);
        assertNull(t.check("a", 10), "no rejection on a stale ack");
        assertEquals(Outcome.UNKNOWN, t.check("a", 41), "tracked by the window alone");
    }

    @Test
    void counterRestartDropsSequencesOfTheOldConnection() {
        t.observeAck(10, 0);
        t.sent("a", 1, 11, 1);
        t.observeAck(-1, 2); // disconnect: acks reset
        t.observeAck(11, 3); // the new connection's seq 11 is not our click
        assertNull(t.check("a", 6));
        assertEquals(Outcome.UNKNOWN, t.check("a", 41));
    }

    @Test
    void summaryHasTheCountsAndLatencies() {
        t.sent("a", 0, 1, 1);
        t.sent("b", 1, 2, 1);
        t.observeAck(1, 4);
        t.placed("a", 4);
        t.observeAck(2, 6);
        assertEquals(Outcome.REJECTED, t.check("b", 8));
        assertEquals("clicks 2: 1 confirmed, 0 late, 1 rejected, 0 dropped, 0 unknown; confirm p50 4 p95 4 t; ack p50 4"
            + " p95 5 t; window 40 t", t.summary());
    }

    // ------------------------------------------------------------------ answers: rejected (handled) vs dropped

    @Test
    void answeredAndAckedWithoutTheBlockIsRejected() {
        t.sent("a", 10, 5, 1, true);
        t.observeAck(5, 14);
        t.answered("a", 14); // the block updates for its cell came with the ack: handled, old state kept
        assertNull(t.check("a", 15));
        assertEquals(Outcome.REJECTED, t.check("a", 16));
        assertEquals(1, t.rejected());
        assertEquals(0, t.dropped());
        assertEquals(2, pacer.interval(), "a refusal doubles the interval");
    }

    @Test
    void answerBeforeTheAckIsKept() {
        t.sent("a", 10, 5, 1, true);
        t.answered("a", 13); // the block updates precede the ack on the wire
        assertNull(t.check("a", 13));
        t.observeAck(5, 14);
        assertEquals(Outcome.REJECTED, t.check("a", 16));
        assertEquals(0, t.dropped());
    }

    @Test
    void ackedOnlyByALaterClickWithoutAnAnswerIsDropped() {
        t.sent("a", 10, 5, 1, true); // dropped by the click limiter: never handled, never acked itself
        t.sent("b", 11, 6, 1, true);
        t.answered("b", 15);
        t.observeAck(6, 15); // b's ack covers a's sequence
        assertEquals(Outcome.CONFIRMED, t.placed("b", 15));
        assertNull(t.check("a", 16));
        assertEquals(Outcome.DROPPED, t.check("a", 17));
        assertEquals(1, t.dropped());
        assertEquals(0, t.rejected());
        assertEquals(0, t.unknown());
        assertFalse(t.pending("a"), "free for a retry");
        assertEquals(1, pacer.interval(), "a drop changes the click window, not the interval");
        assertEquals(0, pacer.rejections());
        assertEquals(1, pacer.drops());
        assertNull(t.placed("a", 30), "a dropped click is not waited for");
    }

    @Test
    void answerAfterTheGraceIsTooLate() {
        t.sent("b", 9, 4, 1, true);
        t.answered("b", 12);
        t.sent("a", 10, 5, 1, true);
        t.observeAck(6, 14);
        assertEquals(Outcome.DROPPED, t.check("a", 16));
        t.answered("a", 17); // resolved already: ignored
        assertEquals(1, t.dropped());
        assertEquals(0, t.rejected());
    }

    @Test
    void withoutAnyAnswerSeenAnAckedClickIsRejected() {
        // no answer reached this run (block updates not delivered, or a server that does not echo): as before
        t.sent("a", 10, 5, 1, true);
        t.sent("b", 11, 6, 1, true);
        t.observeAck(6, 15);
        assertEquals(Outcome.REJECTED, t.check("a", 17));
        assertEquals(Outcome.REJECTED, t.check("b", 17));
        assertEquals(0, t.dropped());
    }

    @Test
    void unwatchedClickIsNeverDropped() {
        t.sent("b", 9, 4, 1, true);
        t.answered("b", 12);
        t.sent("a", 10, 5, 1); // its answers are not watched
        t.observeAck(6, 14);
        assertEquals(Outcome.REJECTED, t.check("a", 16));
        assertEquals(0, t.dropped());
    }

    @Test
    void overdueDroppedClickIsReclassifiedWhenALaterAckCoversIt() {
        t.sent("b", 9, 4, 1, true);
        t.answered("b", 12);
        t.placed("b", 12);
        t.sent("a", 10, 5, 1, true);
        assertNull(t.check("a", 50), "past the window: counted unknown, still waiting for an ack");
        assertEquals(1, t.unknown());
        assertEquals(2, pacer.interval());
        t.observeAck(9, 70); // a later click's ack covers it, without an answer
        assertEquals(Outcome.DROPPED, t.check("a", 72));
        assertEquals(0, t.unknown(), "reclassified");
        assertEquals(1, t.dropped());
        assertEquals(2, pacer.interval(), "not slowed twice for one click");
    }

    @Test
    void droppedClickWithoutALaterAckIsUnknownAtTheCap() {
        t.sent("b", 9, 4, 1, true);
        t.answered("b", 12);
        t.sent("a", 10, 5, 1, true); // the last click: no later ack ever covers it
        assertNull(t.check("a", 109));
        assertEquals(Outcome.UNKNOWN, t.check("a", 110));
        assertEquals(0, t.dropped());
    }

    @Test
    void counterRestartStillTracksWatchedClicksByTheWindowAlone() {
        t.sent("b", 0, 10, 1, true);
        t.answered("b", 1);
        t.observeAck(10, 1);
        t.placed("b", 1);
        t.sent("a", 1, 11, 1, true);
        t.observeAck(-1, 2); // disconnect: acks reset
        t.observeAck(11, 3); // the new connection's seq 11 is not our click
        assertNull(t.check("a", 6), "neither rejected nor dropped on the new connection's ack");
        assertEquals(Outcome.UNKNOWN, t.check("a", 41));
        assertEquals(0, t.dropped());
        assertEquals(0, t.rejected());
    }

    @Test
    void droppedClicksStayInFlightUntilCoveredLikeAnyOther() {
        t.sent("b", 9, 1, 2, true);
        t.answered("b", 10);
        t.sent("a", 10, 2, 2, true); // dropped: the stack guard still counts it until an ack covers it
        t.sent("c", 11, 3, 2, true);
        assertEquals(3, t.inFlight(2));
        t.observeAck(1, 13);
        t.observeAck(1, 15);
        assertEquals(2, t.inFlight(2), "b acked two ticks ago");
        t.observeAck(3, 16); // c's ack covers a
        assertEquals(2, t.inFlight(2), "the slot update may still be on its way");
        t.observeAck(3, 18);
        assertEquals(0, t.inFlight(2));
        assertEquals(Outcome.DROPPED, t.check("a", 18));
    }

    @Test
    void quantileIsNearestRank() {
        assertEquals(-1, PlaceTracker.quantile(List.of(), 0.5));
        assertEquals(3, PlaceTracker.quantile(List.of(5, 1, 3, 2, 4), 0.5));
        assertEquals(5, PlaceTracker.quantile(List.of(5, 1, 3, 2, 4), 0.95));
        assertEquals(1, PlaceTracker.quantile(List.of(5, 1, 3, 2, 4), 0.0));
    }
}
