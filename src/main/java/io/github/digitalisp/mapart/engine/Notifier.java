package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.model.Job;

/**
 * Outbound reports (Discord channel + log). Implementations must be non-blocking and thread-safe:
 * the engine calls them from the client tick thread. CONTRACT (lead-owned).
 */
public interface Notifier {
    /** Lifecycle event, always posted: job started, map done, job done, paused/resumed, waits... */
    void info(String title, String body);

    /**
     * Routine step (back-off, frequent setbacks, tile built, map locked, clearing, next map...): always logged, posted
     * only with {@code discord.verbose}. An implementation without that setting posts it like {@link #info}.
     */
    default void detail(String title, String body) {
        info(title, body);
    }

    /** Needs a human (shortage, blocked, clear failed). Prepends the configured alert mention. */
    void alert(String title, String body);

    /** Event with an image (preview, final map render, diff). */
    void image(String title, String body, String fileName, byte[] png);

    /**
     * {@link #image} as a routine step: always logged, posted only with {@code discord.verbose} (like {@link #detail}).
     * An implementation without that setting posts it like {@link #image}.
     */
    default void detailImage(String title, String body, String fileName, byte[] png) {
        image(title, body, fileName, png);
    }

    /** Periodic progress line for the current tile (posted only with {@code discord.verbose}). */
    void progress(Job job, int tileX, int tileZ, int placed, int total, long etaSeconds);

    /** Log-only fallback used when Discord is disabled. */
    Notifier LOG_ONLY = new Notifier() {
        @Override public void info(String t, String b) { }
        @Override public void detail(String t, String b) { }
        @Override public void alert(String t, String b) { }
        @Override public void image(String t, String b, String f, byte[] p) { }
        @Override public void progress(Job j, int x, int z, int p, int t, long e) { }
    };
}
