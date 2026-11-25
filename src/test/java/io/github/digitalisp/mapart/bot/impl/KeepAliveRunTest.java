package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keep-alive's packets against a fake ZenithProxy client, InputManager and Bot: the turn rides on Bot's own
 * movement packet, the swing follows on the next tick once the turn was executed, and nothing else goes out.
 */
class KeepAliveRunTest {
    /** Fake client + Bot. {@link #botTick} is InputManager + Bot after the plugin's handler: one movement packet at most. */
    private static final class FakeBot implements KeepAliveRun.Port {
        final List<String> wire = new ArrayList<>();
        float yaw = 725f;
        float pitch = 10f;
        float sentYaw = 725f;
        boolean menuOpen;
        /** A module of higher priority submits before us (rejected) or after us (ours dropped). */
        boolean rejectAll;
        boolean dropOurs;
        @Nullable Float requested;
        boolean executed;
        int flyingThisTick;

        void botTick() {
            executed = requested != null && !dropOurs;
            if (executed) yaw = requested;
            requested = null;
            flyingThisTick = 0;
            if (yaw != sentYaw) {
                wire.add("rot " + yaw + "/" + pitch);
                sentYaw = yaw;
                flyingThisTick++;
            }
        }

        @Override public boolean containerOpen() { return menuOpen; }
        @Override public float yaw() { return yaw; }

        @Override
        public boolean requestYaw(float y) {
            if (rejectAll) return false;
            requested = y;
            return true;
        }

        @Override public boolean executedLastTick() { return executed; }

        @Override
        public void swing() {
            wire.add("swing");
        }
    }

    @Test
    void turnsWithBotsOwnMovementPacketThenSwingsOnTheNextTick() {
        FakeBot b = new FakeBot();
        KeepAliveRun run = new KeepAliveRun(15f);
        assertEquals(TaskStatus.RUNNING, run.tick(b));
        b.botTick();
        assertEquals(List.of("rot 740.0/10.0"), b.wire, "the turn is Bot's packet, the continuous yaw kept");
        assertEquals(1, b.flyingThisTick);
        assertEquals(TaskStatus.SUCCESS, run.tick(b));
        b.botTick();
        assertEquals(List.of("rot 740.0/10.0", "swing"), b.wire);
        assertEquals(0, b.flyingThisTick, "the swing tick sends no movement packet");
        assertEquals(10f, b.pitch, "pitch unchanged");

        KeepAliveRun back = new KeepAliveRun(-15f);
        back.tick(b);
        b.botTick();
        back.tick(b);
        assertEquals(725f, b.yaw, "the next one turns back");
    }

    @Test
    void anotherModuleOnTheInputMeansNoSwing() {
        FakeBot b = new FakeBot();
        b.rejectAll = true;
        KeepAliveRun run = new KeepAliveRun(15f);
        assertEquals(TaskStatus.FAILED, run.tick(b));
        assertEquals("another module owns the input", run.failure());
        b.botTick();
        assertTrue(b.wire.isEmpty(), b.wire.toString());

        FakeBot d = new FakeBot();
        d.dropOurs = true; // AutoEat submitted after us in the same tick
        KeepAliveRun dropped = new KeepAliveRun(15f);
        assertEquals(TaskStatus.RUNNING, dropped.tick(d));
        d.botTick();
        assertEquals(TaskStatus.FAILED, dropped.tick(d));
        d.botTick();
        assertTrue(d.wire.isEmpty(), "neither turned nor swung: " + d.wire);
    }

    @Test
    void anOpenMenuStopsItAtEitherTick() {
        FakeBot b = new FakeBot();
        b.menuOpen = true;
        KeepAliveRun run = new KeepAliveRun(15f);
        assertEquals(TaskStatus.FAILED, run.tick(b));
        assertEquals("a container menu is open", run.failure());
        b.botTick();
        assertTrue(b.wire.isEmpty());

        FakeBot m = new FakeBot();
        KeepAliveRun late = new KeepAliveRun(15f);
        late.tick(m);
        m.botTick();
        m.menuOpen = true;
        assertEquals(TaskStatus.FAILED, late.tick(m));
        assertEquals(List.of("rot 740.0/10.0"), m.wire, "no swing with the menu open");
    }
}
