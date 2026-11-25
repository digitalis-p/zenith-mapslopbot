package io.github.digitalisp.mapart.engine;

import org.jspecify.annotations.Nullable;

import static io.github.digitalisp.mapart.engine.EngineTuning.KEEP_ALIVE_RETRY_MS;
import static io.github.digitalisp.mapart.engine.EngineTuning.KEEP_ALIVE_TURN_DEGREES;

/**
 * When the plugin's keep-alive is due, which way it turns, and which one is logged at INFO. Pure: {@link BuildEngine}
 * feeds it its clock and what the bot did. Tick thread only.
 */
final class KeepAlive {
    /** Clock millis the bot last acted (a task ran, bot ticks started, a keep-alive went out); -1 = not yet. */
    private long lastAction = -1;
    /** Clock millis a failed keep-alive is tried again; -1 = none failed since the bot last acted. */
    private long retryAt = -1;
    private boolean right = true;
    /** The wait state of the last keep-alive ({@link #firstIn}). */
    private @Nullable String state;

    /** The bot acted (a task ran, bot ticks started): the idle time starts again now. */
    void acted(long now) {
        lastAction = now;
        retryAt = -1;
    }

    /** Milliseconds since the bot last acted; 0 before it ever did. */
    long idleMillis(long now) {
        return lastAction < 0 ? 0 : Math.max(0, now - lastAction);
    }

    /** Idle for {@code seconds} (0 or less: off), or the retry time of a failed keep-alive has come. */
    boolean due(long now, int seconds) {
        if (seconds <= 0 || lastAction < 0) return false;
        if (retryAt >= 0) return now >= retryAt;
        return now - lastAction >= seconds * 1000L;
    }

    /** The head turn of the next keep-alive in degrees: right and left in turn, so every second one turns back. */
    float turn() {
        return right ? KEEP_ALIVE_TURN_DEGREES : -KEEP_ALIVE_TURN_DEGREES;
    }

    /** A keep-alive went out: the next one turns the other way, a keep-alive interval from now. */
    void sent(long now) {
        right = !right;
        acted(now);
    }

    /**
     * A keep-alive did not complete (another module owned the input, a menu opened): tried again
     * {@code KEEP_ALIVE_RETRY_MS} from now, turning the same way.
     */
    void failed(long now) {
        retryAt = now + KEEP_ALIVE_RETRY_MS;
    }

    /** True for the first keep-alive in the wait state {@code key} (the one before was in another state, or none). */
    boolean firstIn(String key) {
        if (key.equals(state)) return false;
        state = key;
        return true;
    }
}
