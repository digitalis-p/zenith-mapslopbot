package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The silent look's packets against a fake ZenithProxy client and Bot: our PlayerInput and PosRot, then Bot's
 * movement packet of the same tick, which carries the real rotation again.
 */
class SilentRotationTest {
    /**
     * Fake client + Bot. {@link #botTick} is Bot's movement packet: like ZenithProxy's Bot it sends a rotation only
     * when {@code yaw - lastYaw} or {@code pitch - lastPitch} is non-zero (NaN counts), else a status-only packet.
     */
    private static final class FakeBot implements SilentRotation.Port {
        final List<String> wire = new ArrayList<>();
        boolean connected = true;
        boolean @Nullable [] lastKeys = new boolean[7];
        double x = 10.5;
        double y = 64.0;
        double z = 20.5;
        boolean onGround = true;
        boolean horizontalCollision;
        /** Real (continuous) rotation, and what Bot sent last. */
        float yaw = 725f;
        float pitch = 10f;
        float lastYaw = 725f;
        float lastPitch = 10f;
        float sentYaw = Float.NaN;
        float sentPitch = Float.NaN;
        int resends;

        void botTick() {
            if (yaw - lastYaw != 0 || pitch - lastPitch != 0) {
                wire.add("rot " + yaw + "/" + pitch);
                lastYaw = yaw;
                lastPitch = pitch;
            } else {
                wire.add("status");
            }
        }

        @Override public boolean connected() { return connected; }
        @Override public boolean @Nullable [] lastSentKeys() { return lastKeys; }
        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public boolean onGround() { return onGround; }
        @Override public boolean horizontalCollision() { return horizontalCollision; }
        @Override public float realYaw() { return yaw; }

        @Override
        public void sendInput(boolean[] keys) {
            wire.add("input " + Arrays.toString(keys));
        }

        @Override
        public void sendPosRot(double x, double y, double z, float yaw, float pitch, boolean onGround, boolean hc) {
            sentYaw = yaw;
            sentPitch = pitch;
            wire.add("posrot " + x + "," + y + "," + z + " " + yaw + "/" + pitch + (onGround ? " ground" : " air")
                + (hc ? " hc" : ""));
        }

        @Override
        public boolean forceRotationResend() {
            resends++;
            lastYaw = Float.NaN;
            return true;
        }
    }

    @Test
    void oneTickSendsTheKeysThenTheLookThenTheRealRotation() {
        FakeBot b = new FakeBot();
        b.lastKeys = new boolean[]{true, false, false, false, false, false, true}; // walking forward, sprinting
        assertTrue(SilentRotation.send(b, -5f, 60f));
        b.botTick();
        assertEquals(List.of(
            "input [true, false, false, false, false, false, true]",
            "posrot 10.5,64.0,20.5 715.0/60.0 ground",
            "rot 725.0/10.0"), b.wire);
        assertEquals(1, b.resends);
        b.botTick();
        assertEquals("status", b.wire.getLast(), "the next tick: the real rotation is known to the server again");
    }

    @Test
    void withoutASilentLookBotSendsNoRotation() {
        FakeBot b = new FakeBot();
        b.botTick();
        assertEquals(List.of("status"), b.wire, "the resend is what forceRotationResend adds");
    }

    @Test
    void targetYawIsTheEquivalentNearestTheRealYaw() {
        float[][] cases = { // real, target, sent
            {725f, -5f, 715f},
            {725f, 30f, 750f},
            {-170f, 180f, -180f},
            {0f, 359f, -1f},
            {3600f, 90f, 3690f},
            {-725f, 5f, -715f},
        };
        for (float[] k : cases) {
            FakeBot b = new FakeBot();
            b.yaw = k[0];
            assertTrue(SilentRotation.send(b, k[1], 0f));
            assertEquals(k[2], b.sentYaw, 1e-3, "real " + k[0] + ", target " + k[1]);
        }
        Random r = new Random(42);
        for (int i = 0; i < 2000; i++) {
            FakeBot b = new FakeBot();
            b.yaw = (float) (r.nextDouble() * 4000 - 2000);
            float target = (float) (r.nextDouble() * 360 - 180);
            SilentRotation.send(b, target, 0f);
            assertTrue(Math.abs(b.sentYaw - b.yaw) <= 180.01, "never a whole turn: real " + b.yaw + " sent " + b.sentYaw);
            double rem = Math.abs(Math.IEEEremainder(b.sentYaw - target, 360));
            assertTrue(rem < 1e-2, "same direction: target " + target + " sent " + b.sentYaw);
        }
    }

    @Test
    void pitchIsClampedToTheVerticalRange() {
        float[][] cases = {{95f, 90f}, {-120f, -90f}, {45.5f, 45.5f}, {90f, 90f}, {-90f, -90f}};
        for (float[] k : cases) {
            FakeBot b = new FakeBot();
            SilentRotation.send(b, 0f, k[0]);
            assertEquals(k[1], b.sentPitch, "pitch " + k[0]);
        }
    }

    @Test
    void noKeysSentYetMeansAllKeysUp() {
        FakeBot b = new FakeBot();
        b.lastKeys = null;
        assertTrue(SilentRotation.send(b, 0f, 0f));
        assertEquals("input [false, false, false, false, false, false, false]", b.wire.getFirst());
    }

    @Test
    void notConnectedSendsNothing() {
        FakeBot b = new FakeBot();
        b.connected = false;
        assertFalse(SilentRotation.send(b, 30f, 20f));
        assertEquals(List.of(), b.wire);
        assertEquals(0, b.resends);
        b.botTick();
        assertEquals(List.of("status"), b.wire, "no rotation resend forced");
    }

    @Test
    void aLookThatWentOutIsReportedToTheSetbackGuard() {
        SilentLookGuard g = new SilentLookGuard();
        FakeBot b = new FakeBot();
        assertTrue(SilentRotation.send(b, 30f, 20f, g, 1_000));
        assertFalse(g.setback(1_200));
        assertTrue(SilentRotation.send(b, 35f, 20f, g, 5_000));
        assertTrue(g.setback(5_300), "two looks, each followed by a setback: off");

        SilentLookGuard quiet = new SilentLookGuard();
        FakeBot off = new FakeBot();
        off.connected = false;
        assertFalse(SilentRotation.send(off, 30f, 20f, quiet, 1_000));
        assertFalse(quiet.setback(1_100));
        assertFalse(SilentRotation.send(off, 30f, 20f, quiet, 2_000));
        assertFalse(quiet.setback(2_100), "no look went out: the setbacks are not the looks'");
        assertFalse(quiet.disabled());
    }

    @Test
    void usableWhileAvailableAndNotTurnedOff() {
        SilentLookGuard g = new SilentLookGuard();
        assertTrue(SilentRotation.usable(true, g));
        assertFalse(SilentRotation.usable(false, g), "no reflection");
        g.sent(0);
        g.setback(100);
        g.sent(200);
        g.setback(300);
        assertFalse(SilentRotation.usable(true, g), "turned off for the run");
    }

    @Test
    void posRotKeepsBotsPositionAndFlags() {
        FakeBot b = new FakeBot();
        b.x = 10.25;
        b.y = 70.0;
        b.z = -3.75;
        b.onGround = false;
        b.horizontalCollision = true;
        b.yaw = 0f;
        b.lastYaw = 0f;
        assertTrue(SilentRotation.send(b, 45f, -30f));
        assertEquals("posrot 10.25,70.0,-3.75 45.0/-30.0 air hc", b.wire.get(1));
    }
}
