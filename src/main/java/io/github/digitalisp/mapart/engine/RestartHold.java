package io.github.digitalisp.mapart.engine;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2b2t's scheduled restart: the server warns in system chat, once a
 * minute, "Server restarting in 5 minutes..." down to "Server restarting in 1 minute...", each followed in the same
 * message by "You will keep your position on the server if you stay connected, ...". A flying machine or a cart run cut
 * off by the restart breaks, so inside {@code site.restartHoldMinutes} of the restart the engine switches the moss carts
 * off and starts no NUKE and no clear press. This class only tracks the expected restart time; the engine acts on it.
 *
 * <p>The hold ends when the restart has happened (the connection was lost, or 2b2t moved the bot to its queue, and bot
 * ticks then started again), or {@link #GRACE_MS} after the announced time with no restart. Engine thread only, except
 * {@link #parse}, which is pure.
 */
final class RestartHold {
    /** After the announced time, how long the hold waits for the restart before it gives up on it. */
    static final long GRACE_MS = 2 * 60_000L;

    /**
     * The warning, from the start of the line: never a player's line ("&lt;name&gt; Server restarting in 4 minutes...",
     * which the anchor rejects) and never a whisper. Without a time ("Server restarting", "Server Restarting...") the
     * restart is taken as due now.
     */
    private static final Pattern WARNING = Pattern.compile(
        "server\\s+restarting(?:\\s+in\\s+(\\d{1,4})\\s*(seconds?|secs?|minutes?|mins?|hours?))?(?![\\p{L}\\d]).*",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Clock time the restart is due; -1 = none announced. */
    private long dueAt = -1;
    /** The last warning's text (first line), for the log. */
    private String text = "";
    /** The connection was lost (or the bot went to the queue) while a restart was announced. */
    private boolean restartSeen;

    /** Milliseconds until the restart a system chat line announces (0 = now), or empty when it is no warning. */
    static OptionalLong parse(String line) {
        if (line == null) return OptionalLong.empty();
        Matcher m = WARNING.matcher(line.strip());
        if (!m.matches()) return OptionalLong.empty();
        if (m.group(1) == null) return OptionalLong.of(0);
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2).toLowerCase(Locale.ROOT);
        long ms = unit.startsWith("h") ? n * 3_600_000L : unit.startsWith("m") ? n * 60_000L : n * 1000L;
        return OptionalLong.of(ms);
    }

    /** A warning seen at {@code now}: the restart is due {@code inMs} from then (a later warning replaces it). */
    void warned(long now, long inMs, String line) {
        dueAt = now + Math.max(0, inMs);
        String first = line == null ? "" : line.strip();
        int nl = first.indexOf('\n');
        text = nl >= 0 ? first.substring(0, nl).strip() : first;
    }

    /** A restart is announced and not over yet. */
    boolean announced() {
        return dueAt >= 0;
    }

    /** The connection was lost, or the bot was moved to the queue: the restart happened if one was announced. */
    void connectionLost() {
        if (dueAt >= 0) restartSeen = true;
    }

    /** Bot ticks started again: true (and the state cleared) when a restart was seen before. */
    boolean backAfterRestart() {
        if (!restartSeen) return false;
        clear();
        return true;
    }

    /** True from {@code windowMinutes} before the announced time to {@link #GRACE_MS} after it. 0 or less = never. */
    boolean holds(long now, int windowMinutes) {
        return windowMinutes > 0 && dueAt >= 0 && now >= dueAt - windowMinutes * 60_000L && now < dueAt + GRACE_MS;
    }

    /**
     * True (and the state cleared) once the grace after the announced time ran out. A restart seen is normally cleared
     * first, by {@link #backAfterRestart} when bot ticks start again.
     */
    boolean expired(long now) {
        if (dueAt < 0 || now < dueAt + GRACE_MS) return false;
        clear();
        return true;
    }

    /** Milliseconds until the announced time (negative once past); {@link Long#MAX_VALUE} with none announced. */
    long msLeft(long now) {
        return dueAt < 0 ? Long.MAX_VALUE : dueAt - now;
    }

    /** Whole minutes (rounded up) until the announced time; 0 when it is due or past. */
    long minutesLeft(long now) {
        return dueAt < 0 ? 0 : Math.max(0, (dueAt - now + 59_999) / 60_000);
    }

    String text() {
        return text;
    }

    void clear() {
        dueAt = -1;
        text = "";
        restartSeen = false;
    }
}
