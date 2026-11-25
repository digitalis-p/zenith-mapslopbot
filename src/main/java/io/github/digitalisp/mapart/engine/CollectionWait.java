package io.github.digitalisp.mapart.engine;

import java.util.Locale;
import java.util.OptionalInt;

/**
 * WAIT_CLEAR after the art layer read clear: how long the item collection (moss: hopper minecarts) keeps running.
 *
 * <p>Each poll brings the number of dropped-item entities left in the platform volume, or none when that count is
 * unavailable or cannot be trusted. The wait is over once the count has read 0 for {@code quietMs} without a break,
 * or when {@code capMs} since the clear has run out, whichever comes first. A poll without a count breaks the quiet
 * period, so with no count at all the cap is the fixed run-on of before. Engine thread only.
 */
final class CollectionWait {
    /** One poll's outcome: keep waiting (the status note after "carts collecting, "), or done (the line to log). */
    record Step(boolean done, String text) { }

    private final long start;
    private final long capMs;
    private final long quietMs;
    /** Clock time the count read 0 in the current quiet stretch; -1 = not quiet. */
    private long quietSince = -1;
    /** The last poll's count; -1 = that poll had none. */
    private int last = -1;
    /** Some poll had a count. */
    private boolean counted;

    /** {@code start}: when the art layer read clear; {@code capMs} > 0; {@code quietMs} >= 0 (negative counts as 0). */
    CollectionWait(long start, long capMs, long quietMs) {
        this.start = start;
        this.capMs = capMs;
        this.quietMs = Math.max(0, quietMs);
    }

    Step poll(long now, OptionalInt items) {
        if (items.isPresent()) {
            last = Math.max(0, items.getAsInt());
            counted = true;
            if (last > 0) quietSince = -1;
            else if (quietSince < 0) quietSince = now;
        } else {
            last = -1;
            quietSince = -1;
        }
        long elapsed = now - start;
        if (quietSince >= 0 && now - quietSince >= quietMs) {
            return new Step(true, "collection: 0 items left after " + secs(quietSince - start) + " s; carts off after "
                + secs(elapsed) + " s");
        }
        if (elapsed >= capMs) {
            String what = last >= 0 ? "cap reached with " + items(last) + " left"
                : counted ? "cap reached, items left unknown (no item count now)"
                : "fixed run-on of " + capMs / 1000 + " s over (no item count)";
            return new Step(true, "collection: " + what + "; carts off");
        }
        long capLeft = Math.ceilDiv(capMs - elapsed, 1000L);
        if (last < 0) return new Step(false, capLeft + " s left");
        if (last > 0) return new Step(false, items(last) + " left, at most " + capLeft + " s");
        long quietLeft = Math.ceilDiv(quietMs - (now - quietSince), 1000L);
        return new Step(false, "no items left, off in " + Math.min(quietLeft, capLeft) + " s");
    }

    private static String items(int n) {
        return n + (n == 1 ? " item" : " items");
    }

    private static String secs(long ms) {
        return String.format(Locale.ROOT, "%.1f", ms / 1000.0);
    }
}
