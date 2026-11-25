package io.github.digitalisp.mapart.coord;

import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * The answer to an asynchronous {@link Coordination} call (a kv read or write), polled once per tick: the engine never
 * waits for it. An implementation may complete it from its own thread ({@link Settable}); {@link LocalCoordination}
 * hands back completed ones.
 *
 * <p>Error codes: {@code cas_failed} (the expected version did not match),
 * {@code unavailable} (no link), {@code limit}, {@code forbidden}, ... .
 */
public interface Pending<T> {
    /** True once {@link #value()} or {@link #error()} is set. */
    boolean done();

    /** The result once done without error; null before. */
    @Nullable T value();

    /** The error code once done with an error; null while pending or on success. */
    @Nullable String error();

    static <T> Pending<T> of(T value) {
        Settable<T> p = new Settable<>();
        p.complete(value);
        return p;
    }

    static <T> Pending<T> failed(String code) {
        Settable<T> p = new Settable<>();
        p.fail(code);
        return p;
    }

    /** A view of {@code p} whose value is {@code f} of p's value (errors pass through). */
    static <A, B> Pending<B> map(Pending<A> p, Function<A, B> f) {
        return new Pending<>() {
            @Override
            public boolean done() {
                return p.done();
            }

            @Override
            public @Nullable B value() {
                A a = p.value();
                return a == null ? null : f.apply(a);
            }

            @Override
            public @Nullable String error() {
                return p.error();
            }
        };
    }

    /** Completed once, from any thread; read on the tick thread. */
    final class Settable<T> implements Pending<T> {
        private volatile boolean done;
        private volatile @Nullable T value;
        private volatile @Nullable String error;

        /** First completion wins; later ones are ignored. */
        public synchronized void complete(T v) {
            if (done) return;
            value = v;
            done = true;
        }

        public synchronized void fail(String code) {
            if (done) return;
            error = code == null || code.isBlank() ? "internal" : code;
            done = true;
        }

        @Override
        public boolean done() {
            return done;
        }

        @Override
        public @Nullable T value() {
            return done ? value : null;
        }

        @Override
        public @Nullable String error() {
            return done ? error : null;
        }
    }
}
