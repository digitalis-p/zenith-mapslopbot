package io.github.digitalisp.mapart.bot.impl;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reference-counted temporary override of some global settings. The first {@link #acquire()} captures the
 * current values and applies the override; the last {@link Handle#release()} restores the captured values.
 * Handles are idempotent, so nested and overlapping holders (tasks cancelled in any order) restore correctly.
 * Pure (the settings are reached through the supplied functions); thread-safe.
 *
 * @param <S> snapshot type of the overridden settings
 */
public final class RefCountedOverride<S> {
    private final Supplier<S> capture;
    private final Runnable apply;
    private final Consumer<S> restore;
    private int holders;
    private S saved;

    public RefCountedOverride(Supplier<S> capture, Runnable apply, Consumer<S> restore) {
        this.capture = capture;
        this.apply = apply;
        this.restore = restore;
    }

    public synchronized Handle acquire() {
        if (holders++ == 0) {
            saved = capture.get();
        }
        apply.run();
        return new Handle(this);
    }

    /** Re-applies the override while held (in case something flipped a setting back mid-walk). */
    public synchronized void enforce() {
        if (holders > 0) apply.run();
    }

    public synchronized int holders() {
        return holders;
    }

    /** Values captured by the first holder, or null when nobody holds the override. */
    public synchronized S saved() {
        return saved;
    }

    private synchronized void releaseOne() {
        if (holders <= 0) return;
        if (--holders == 0) {
            S s = saved;
            saved = null;
            restore.accept(s);
        }
    }

    /** One holder's claim. {@link #release()} is idempotent. */
    public static final class Handle {
        private final RefCountedOverride<?> owner;
        private boolean released;

        private Handle(RefCountedOverride<?> owner) {
            this.owner = owner;
        }

        public void release() {
            synchronized (owner) {
                if (released) return;
                released = true;
                owner.releaseOne();
            }
        }

        public boolean isReleased() {
            synchronized (owner) {
                return released;
            }
        }
    }
}
