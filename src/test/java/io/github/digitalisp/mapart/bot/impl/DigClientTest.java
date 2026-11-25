package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.DigClient.Action;
import io.github.digitalisp.mapart.bot.impl.DigClient.Event;
import io.github.digitalisp.mapart.bot.impl.DigClient.Kind;
import io.github.digitalisp.mapart.bot.impl.DigClient.Outcome;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DigClient} against a scripted game: the packets of each tick, progress, and what becomes of each block. */
class DigClientTest {
    private static final Pos A = new Pos(1, 64, 1);
    private static final Pos B = new Pos(2, 64, 1);

    /** Records every packet as "ACTION pos seq shot" or "swing"; speed, air and ack are set by the test. */
    private static final class Script implements DigClient.Io<String> {
        final List<String> sent = new ArrayList<>();
        final Set<Pos> air = new HashSet<>();
        double speed;
        int ack = -1;
        int seq;

        @Override
        public int send(Action action, Pos pos, String shot) {
            int s = action == Action.ABORT ? 0 : ++seq;
            sent.add(action + " " + pos + " " + s + " " + shot);
            return s;
        }

        @Override
        public void swing() {
            sent.add("swing");
        }

        @Override
        public double speedNow(Pos pos) {
            return speed;
        }

        @Override
        public boolean isAir(Pos pos) {
            return air.contains(pos);
        }

        @Override
        public int latestAck() {
            return ack;
        }

        /** The packets since the last call. */
        List<String> take() {
            List<String> out = new ArrayList<>(sent);
            sent.clear();
            return out;
        }
    }

    private Script io;

    @BeforeEach
    void setUp() {
        io = new Script();
    }

    /** Mines the active block with the rotation in effect every tick from {@code from}; the tick of its STOP. */
    private static long mineToStop(DigClient<String> d, Script io, long from) {
        for (long t = from; t < from + 2000; t++) {
            d.tickActive(t, "up", true);
            if (io.sent.stream().anyMatch(s -> s.startsWith("STOP"))) return t;
        }
        throw new AssertionError("no STOP");
    }

    @Test
    void delayedBlockWithAbortIsStartAbortStopSwingInOneTick() {
        DigClient<String> d = new DigClient<>(io, true);
        assertTrue(d.canStart(10));
        d.startDelayed(10, A, "up", 0.2);
        assertEquals(List.of("START [1,64,1] 1 up", "ABORT [1,64,1] 0 up", "STOP [1,64,1] 2 up", "swing"), io.take());
        assertTrue(d.delayedPending());
        assertTrue(d.stopPending());
        assertFalse(d.digging());
        assertEquals(1, d.delayedSent);
    }

    @Test
    void delayedBlockWithoutAbortIsStartStopSwing() {
        DigClient<String> d = new DigClient<>(io, false);
        d.startDelayed(10, A, "up", 0.2);
        assertEquals(List.of("START [1,64,1] 1 up", "STOP [1,64,1] 2 up", "swing"), io.take());
    }

    @Test
    void oneStartPerTick() {
        DigClient<String> d = new DigClient<>(io, false);
        d.startDelayed(10, A, "up", 0.2);
        assertFalse(d.canStart(10), "a second START in the delayed block's tick");
        assertTrue(d.canStart(11));
        io.speed = 0.25;
        d.start(11, B, "up");
        assertFalse(d.canStart(11));
        assertFalse(d.canStart(12), "mining the active block");
        io.speed = 1.0;
        DigClient<String> e = new DigClient<>(io, false);
        e.start(20, A, "up");
        assertFalse(e.canStart(20));
        assertTrue(e.canStart(21), "no delay after an instant block");
    }

    @Test
    void heldKeyCadenceByDefault_noProgressInTheStartTick_stopAfterCeilTicks_thenTheDelay() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.25;
        d.start(0, A, "up");
        assertEquals(List.of("START [1,64,1] 1 up", "swing"), io.take());
        assertEquals(0.0, d.active().progress);
        d.tickActive(1, "up", true);
        assertEquals(0.25, d.active().progress, 1e-9);
        assertEquals(List.of("swing"), io.take(), "every mining tick swings");
        long stop = mineToStop(d, io, 2);
        assertEquals(4, stop, "STOP ceil(1/0.25) = 4 ticks after START");
        List<String> sent = io.take();
        assertEquals(List.of("swing", "swing", "STOP [1,64,1] 2 up", "swing"), sent, "ticks 2, 3 and the STOP tick 4");
        assertFalse(d.digging());
        for (long t = stop + 1; t <= stop + DigClient.DESTROY_DELAY; t++) assertFalse(d.canStart(t), "START at " + t);
        assertTrue(d.canStart(stop + DigClient.DESTROY_DELAY + 1));
        assertEquals(1, d.stopped);
    }

    @Test
    void progressInTheStartTickWhenAsked() {
        DigClient<String> d = new DigClient<>(io, false);
        d.progressInStartTick(true);
        io.speed = 0.25;
        d.start(0, A, "up");
        assertEquals(0.25, d.active().progress, 1e-9, "the first progress is added in the START tick");
        assertEquals(3, mineToStop(d, io, 1), "STOP ceil(1/0.25) - 1 ticks after START");
    }

    @Test
    void stopPacketAndSwingInTheStopTick() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.5;
        d.start(0, A, "up");
        io.take();
        d.tickActive(1, "up", true);
        assertEquals(List.of("swing"), io.take());
        d.tickActive(2, "up", true);
        assertEquals(List.of("STOP [1,64,1] 2 up", "swing"), io.take());
    }

    @Test
    void stopWaitsForTheRotationInEffectAndTheInput() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.5;
        d.start(0, A, "up");
        d.tickActive(1, "up", true);
        d.tickActive(2, null, true);
        d.tickActive(3, "up", false);
        io.take();
        assertTrue(d.digging(), "no STOP without the rotation in effect or the input");
        d.tickActive(4, "side", true);
        assertEquals(List.of("STOP [1,64,1] 2 side", "swing"), io.take(), "the STOP carries the face in effect");
    }

    @Test
    void instantBlockSendsOnlyTheStart() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 1.2;
        d.start(5, A, "up");
        assertEquals(List.of("START [1,64,1] 1 up", "swing"), io.take());
        assertFalse(d.digging());
        assertEquals(1, d.instants);
        assertTrue(d.waiting());
        assertFalse(d.stopPending(), "an instant START never becomes a delayed destroy");
        assertTrue(d.canStart(6));
        io.air.add(A);
        List<Event> ev = d.poll(8);
        assertEquals(1, ev.size());
        assertEquals(Outcome.BROKEN, ev.get(0).outcome());
        assertEquals(Kind.INSTANT, ev.get(0).kind());
        assertFalse(d.waiting());
    }

    @Test
    void ackWithoutAirIsRefusedAfterTheGrace() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.5;
        d.start(0, A, "up");
        long stop = mineToStop(d, io, 1);
        io.ack = 1; // the START only
        assertTrue(d.poll(stop + 1).isEmpty());
        io.ack = 2; // the STOP
        assertTrue(d.poll(stop + 2).isEmpty());
        assertTrue(d.poll(stop + 2 + DigClient.REJECT_GRACE_TICKS - 1).isEmpty());
        List<Event> ev = d.poll(stop + 2 + DigClient.REJECT_GRACE_TICKS);
        assertEquals(Outcome.REFUSED, ev.get(0).outcome());
        assertEquals(1, d.refused);
        assertFalse(d.waiting());
    }

    /**
     * {@code build.nukeInstantAckTicks}: an instant block whose air comes 3 ticks after its ack (2b2t, live j0024) is
     * broken with a 4-tick wait, never refused, so nothing tells the run to START it again; with the 2-tick default it
     * was refused before its air showed.
     */
    @Test
    void lateAirWithinTheInstantAckWaitIsBrokenNotRefused() {
        DigClient<String> late = new DigClient<>(io, false);
        late.instantAckTicks(4);
        assertEquals(4, late.instantAckTicks());
        io.speed = 1.3;
        assertTrue(late.startInstant(0, A, "up", true));
        io.ack = 1; // the ack at tick 6, the air 3 ticks later
        for (long t = 6; t <= 8; t++) assertTrue(late.poll(t).isEmpty(), "no answer yet at " + t);
        io.air.add(A);
        List<Event> ev = late.poll(9);
        assertEquals(1, ev.size());
        assertEquals(Outcome.BROKEN, ev.get(0).outcome());
        assertEquals(0, late.refused);
        assertFalse(late.waiting());

        Script io2 = new Script();
        io2.speed = 1.3;
        DigClient<String> d = new DigClient<>(io2, false);
        assertEquals(DigClient.REJECT_GRACE_TICKS, d.instantAckTicks(), "2 by default, as before");
        d.startInstant(0, A, "up", true);
        io2.ack = 1;
        assertTrue(d.poll(6).isEmpty());
        assertTrue(d.poll(7).isEmpty());
        assertEquals(Outcome.REFUSED, d.poll(8).get(0).outcome(), "refused one tick before its air");
    }

    /**
     * A block the server keeps (acked, still showing at the end of the wait) is refused after the instant wait and
     * reported for its retry; a mined block's STOP keeps the 2-tick grace; the wait is clamped to 2..20.
     */
    @Test
    void aBlockStillShowingAfterTheInstantAckWaitIsRefusedAndMinedKeepsTwo() {
        DigClient<String> d = new DigClient<>(io, false);
        d.instantAckTicks(4);
        io.speed = 1.3;
        d.startInstant(0, A, "up", true);
        io.ack = 1;
        for (long t = 6; t <= 9; t++) assertTrue(d.poll(t).isEmpty(), "waiting at " + t);
        List<Event> ev = d.poll(10);
        assertEquals(1, ev.size());
        assertEquals(Outcome.REFUSED, ev.get(0).outcome());
        assertEquals(Kind.INSTANT, ev.get(0).kind());
        assertEquals(1, d.refused);
        assertFalse(d.inFlight(A), "answered: free for its retry");

        io.speed = 0.5;
        d.start(20, B, "up");
        long stop = mineToStop(d, io, 21);
        io.ack = 3; // the START and the STOP of B
        assertTrue(d.poll(stop + 1).isEmpty());
        assertTrue(d.poll(stop + 1 + DigClient.REJECT_GRACE_TICKS - 1).isEmpty());
        assertEquals(Outcome.REFUSED, d.poll(stop + 1 + DigClient.REJECT_GRACE_TICKS).get(0).outcome(),
            "a mined block keeps the 2-tick grace");

        d.instantAckTicks(1);
        assertEquals(DigClient.REJECT_GRACE_TICKS, d.instantAckTicks());
        d.instantAckTicks(99);
        assertEquals(DigClient.INSTANT_ACK_MAX_TICKS, d.instantAckTicks());
    }

    @Test
    void noAnswerIsUnknownAfterTheConfirmWindow() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 1.5;
        d.start(0, A, "up");
        assertTrue(d.poll(DigClient.CONFIRM_MAX_TICKS).isEmpty());
        assertEquals(Outcome.UNKNOWN, d.poll(DigClient.CONFIRM_MAX_TICKS + 1).get(0).outcome());
        assertEquals(1, d.unknown);
    }

    @Test
    void delayedBlockTimesOutAfterItsDeadlineAndIgnoresTheAck() {
        DigClient<String> d = new DigClient<>(io, false);
        d.startDelayed(0, A, "up", 0.2); // 5 ticks: expected at 4, deadline 4 + 40
        io.ack = 2;
        long deadline = 4 + DigClient.DELAYED_GRACE_TICKS;
        for (long t = 1; t <= deadline; t++) assertTrue(d.poll(t).isEmpty(), "event at " + t);
        assertTrue(d.delayedPending());
        List<Event> ev = d.poll(deadline + 1);
        assertEquals(Outcome.TIMED_OUT, ev.get(0).outcome());
        assertEquals(Kind.DELAYED, ev.get(0).kind());
        assertEquals(1, d.delayedTimedOut);
        assertFalse(d.delayedPending());
    }

    @Test
    void delayedBlockIsBrokenWhenAirShows() {
        DigClient<String> d = new DigClient<>(io, false);
        d.startDelayed(0, A, "up", 0.2);
        io.air.add(A);
        List<Event> ev = d.poll(7);
        assertEquals(Outcome.BROKEN, ev.get(0).outcome());
        assertEquals(Kind.DELAYED, ev.get(0).kind());
        assertEquals(1, d.delayedBroken);
        assertEquals(3, d.delayedLateTicks, "air at 7, expected at 4");
        assertFalse(d.delayedPending());
    }

    @Test
    void activeBlockShowingAirEndsItAsBroken() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.1;
        d.start(0, A, "up");
        d.tickActive(1, "up", true);
        io.air.add(A);
        d.tickActive(2, "up", true);
        assertFalse(d.digging());
        List<Event> ev = d.poll(2);
        assertEquals(Outcome.BROKEN, ev.get(0).outcome());
        assertEquals(Kind.STOPPED, ev.get(0).kind());
    }

    @Test
    void activeBlockPastItsBudgetIsAborted() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.25; // 4 ticks: budget 4 * 2 + 60
        d.start(0, A, "up");
        for (long t = 1; t <= 68; t++) d.tickActive(t, null, true);
        assertTrue(d.digging());
        io.take();
        d.tickActive(69, null, true);
        assertEquals(List.of("ABORT [1,64,1] 0 up"), io.take());
        assertFalse(d.digging());
        assertEquals(Outcome.ABORTED, d.poll(69).get(0).outcome());
        assertEquals(1, d.aborted);
    }

    @Test
    void abortActiveSendsAbortWithSequenceZero() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.25;
        d.start(0, A, "up");
        io.take();
        d.abortActive();
        assertEquals(List.of("ABORT [1,64,1] 0 up"), io.take());
        assertFalse(d.digging());
        assertNull(d.active());
        d.abortActive();
        assertTrue(io.take().isEmpty(), "nothing to abort");
    }

    @Test
    void progressUsesTheSpeedOfEachTick() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.25;
        d.start(0, A, "up");
        d.tickActive(1, "up", true);
        d.tickActive(2, "up", true);
        io.speed = 0.125; // a slower item from now on
        io.take();
        long stop = mineToStop(d, io, 3);
        assertEquals(6, stop, "0.25 + 0.25 then 0.125 per tick: 1.0 at tick 6 (4 at full speed)");
    }

    @Test
    void serverClockScalesProgressDeadlinesAndBudget() {
        DigClient<String> d = new DigClient<>(io, false);
        d.serverClock(0.125);
        assertEquals(0.125, d.serverClock());
        io.speed = 0.5;
        d.start(0, A, "up");
        assertEquals(16, mineToStop(d, io, 1), "0.5 x 0.125 per client tick");
        assertEquals(16 + DigClient.DESTROY_DELAY + 1, firstStart(d, 17), "the delay after it stays in client ticks");

        DigClient<String> e = new DigClient<>(io, false);
        e.serverClock(0.125);
        e.startDelayed(0, B, "up", 0.2); // 5 server ticks: expected after 4 / 0.125, deadline 40 / 0.125 later
        for (long t = 1; t <= 32 + 320; t++) assertTrue(e.poll(t).isEmpty(), "event at " + t);
        assertEquals(Outcome.TIMED_OUT, e.poll(32 + 320 + 1).get(0).outcome());

        DigClient<String> f = new DigClient<>(io, false);
        f.serverClock(0.125);
        f.start(0, A, "up"); // 2 ticks at full clock: budget (2 * 2 + 60) / 0.125
        for (long t = 1; t <= 512; t++) f.tickActive(t, null, true);
        assertTrue(f.digging());
        f.tickActive(513, null, true);
        assertFalse(f.digging());

        DigClient<String> g = new DigClient<>(io, false);
        g.serverClock(0.125);
        io.speed = 1.0;
        g.start(0, A, "up");
        assertEquals(1, g.instants, "the instant rule does not scale");
    }

    private static long firstStart(DigClient<String> d, long from) {
        for (long t = from; t < from + 100; t++) if (d.canStart(t)) return t;
        throw new AssertionError("never");
    }

    @Test
    void serverClockIsClampedToOneAndRejectsNonsense() {
        DigClient<String> d = new DigClient<>(io, false);
        d.serverClock(2.0);
        assertEquals(1.0, d.serverClock());
        d.serverClock(0);
        assertEquals(1.0, d.serverClock());
        d.serverClock(Double.NaN);
        assertEquals(1.0, d.serverClock());
        d.serverClock(0.5);
        assertEquals(0.5, d.serverClock());
    }

    @Test
    void stopPendingAndFlightsCountWhatIsUnanswered() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 1.5;
        d.start(0, A, "up");
        assertEquals(1, d.flights());
        assertFalse(d.stopPending());
        io.speed = 0.5;
        d.start(1, B, "up");
        mineToStop(d, io, 2);
        assertEquals(2, d.flights());
        assertTrue(d.stopPending());
        assertTrue(d.inFlight(B));
    }

    // ------------------------------------------------------------------------- Lambda's OldGrim scheme (nukeLambda)

    private static List<String> fakes(int n, int firstSeq) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add("START [1,1019,1] " + (firstSeq + i) + " up");
        return out;
    }

    @Test
    void lambdaSlowBlockIsStartFakeThenStopAfterSevenTicksWithTheBurst() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.39; // lapis, netherite pickaxe Efficiency V, no Haste
        assertTrue(d.canStartLambda(10));
        assertTrue(d.startLambda(10, A, "up", 0, false));
        assertEquals(List.of("START [1,64,1] 1 up", "START [1,1019,1] 2 up", "swing"), io.take());
        assertTrue(d.digging());
        for (long t = 11; t <= 15; t++) {
            assertFalse(d.tickLambda(t, true, true), "no STOP before 7 ticks of mining (tick " + t + ")");
            assertEquals(List.of("swing"), io.take());
        }
        assertTrue(d.tickLambda(16, true, true));
        List<String> expected = new ArrayList<>(fakes(22, 3));
        expected.add("STOP [1,64,1] 25 up");
        expected.add("swing");
        assertEquals(expected, io.take());
        assertEquals(1, d.bursts);
        assertEquals(23, d.fakeStarts);
        assertTrue(d.lambdaBypass());
        // the burst lets the next non-instant block start at once; instant ones wait the 6 ticks
        assertTrue(d.canStartLambda(16));
        assertFalse(d.canStartInstant(16, 8));
        assertFalse(d.canStartInstant(21, 8));
        assertTrue(d.canStartInstant(22, 8));
    }

    @Test
    void lambdaWithoutRoomForTheBurstWaitsTheSixTicks() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.39;
        d.startLambda(10, A, "up", 0, false);
        io.take();
        for (long t = 11; t <= 15; t++) d.tickLambda(t, true, false);
        assertTrue(d.tickLambda(16, true, false));
        assertEquals(List.of("swing", "swing", "swing", "swing", "swing", "STOP [1,64,1] 3 up", "swing"), io.take());
        assertFalse(d.lambdaBypass());
        assertFalse(d.canStartLambda(16));
        assertFalse(d.canStartLambda(21));
        assertTrue(d.canStartLambda(22));
    }

    @Test
    void lambdaStopWaitsForTheInputAndTheThreshold() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.1; // 0.7 after 7 ticks of progress (Lambda's fudge: ticks since START)
        d.startLambda(10, A, "up", 0, false);
        io.take();
        for (long t = 11; t <= 16; t++) assertFalse(d.tickLambda(t, true, true));
        assertFalse(d.tickLambda(17, false, true), "input not ours");
        assertTrue(d.tickLambda(18, true, true));
        assertTrue(io.take().contains("STOP [1,64,1] 25 up"));
    }

    @Test
    void lambdaBlockAtSevenTenthsIsStartFakeStopInOneTick() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.93; // gray terracotta without Haste
        assertTrue(d.startLambda(10, A, "up", 0, false));
        assertEquals(List.of("START [1,64,1] 1 up", "START [1,1019,1] 2 up", "STOP [1,64,1] 3 up", "swing"), io.take());
        assertFalse(d.digging());
        assertTrue(d.inFlight(A));
        assertEquals(1, d.lambdaQuick);
        assertFalse(d.canStartLambda(10));
        assertFalse(d.canStartLambda(15));
        assertTrue(d.canStartLambda(16));
        assertFalse(d.lambdaBypass());
    }

    @Test
    void lambdaDoubleBreakStopsTheFirstAndStartsTheSecondInOneTick() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.39;
        d.startLambda(10, A, "up", 0, false);
        assertTrue(d.lambdaSecondary(10, B, "east"));
        assertEquals(List.of("START [1,64,1] 1 up", "START [1,1019,1] 2 up", "swing", "STOP [1,64,1] 3 up",
            "START [2,64,1] 4 east", "START [2,1019,1] 5 east"), io.take());
        assertTrue(d.delayedPending());
        assertTrue(d.digging());
        assertEquals(B, d.active().pos);
        assertEquals(1, d.lambdaSecondaries);
        // the server breaks A at ticks 10..12 (0.39 × 3 ≥ 1); the slot counts as free 2 ticks after
        assertFalse(d.delayedSlotFree(13, 2));
        assertTrue(d.delayedSlotFree(14, 2));
        io.air.add(A);
        List<Event> ev = d.poll(13);
        assertEquals(1, ev.size());
        assertEquals(Kind.DELAYED, ev.getFirst().kind());
        assertEquals(Outcome.BROKEN, ev.getFirst().outcome());
    }

    @Test
    void lambdaDoubleBreakOnlyBelowSevenTenths() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.39;
        d.startLambda(10, A, "up", 0, false);
        io.take();
        assertFalse(d.lambdaSecondary(11, B, "up"), "0.39 × 2 ≥ 0.7: the early STOP would break it");
        assertEquals(List.of(), io.take());
        DigClient<String> e = new DigClient<>(io, false);
        e.startLambda(20, A, "up", 8, true);
        io.take();
        assertFalse(e.lambdaSecondary(20, B, "up"), "a retried block is mined alone");
    }

    @Test
    void lambdaFakeStartsNeverGoBelowTheWorldTop() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.39;
        Pos deep = new Pos(1, -700, 1); // 955 above is 255: inside the build height of the nether and the end
        d.startLambda(10, deep, "up", 0, false);
        assertEquals(List.of("START [1,-700,1] 1 up", "swing"), io.take());
        assertEquals(0, d.fakeStarts);
        assertTrue(DigClient.outOfWorld(DigClient.fakeOf(A)));
        assertFalse(DigClient.outOfWorld(DigClient.fakeOf(deep)));
    }

    @Test
    void lambdaWithoutFakeStartsSendsNone() {
        DigClient<String> d = new DigClient<>(io, false);
        d.lambdaOptions(false, true);
        io.speed = 0.39;
        d.startLambda(10, A, "up", 0, false);
        for (long t = 11; t <= 16; t++) d.tickLambda(t, true, true);
        assertTrue(io.take().stream().noneMatch(s -> s.contains("1019")));
        assertEquals(0, d.fakeStarts);
        assertFalse(d.lambdaBypass(), "no burst, no bypass");
    }

    @Test
    void lambdaInstantBlockIsAPlainStart() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 1.3;
        assertTrue(d.startLambda(10, A, "up", 0, false));
        assertEquals(List.of("START [1,64,1] 1 up", "swing"), io.take());
        assertFalse(d.digging());
        assertEquals(1, d.instants);
    }

    @Test
    void lambdaActiveBlockIsAbortedOnRelease() {
        DigClient<String> d = new DigClient<>(io, false);
        io.speed = 0.39;
        d.startLambda(10, A, "up", 0, false);
        io.take();
        d.abortActive();
        assertEquals(List.of("ABORT [1,64,1] 0 up"), io.take());
        assertFalse(d.digging());
    }
}
