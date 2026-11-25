package io.github.digitalisp.mapart.engine;

import org.slf4j.Logger;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs store writes one at a time, in submission order, on a shared executor, so an older checkpoint
 * or job state can never overwrite a newer one. Falls back to running inline if the executor rejects.
 */
final class SerialWriter {
    private final Executor executor;
    private final Logger log;
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();

    SerialWriter(Executor executor, Logger log) {
        this.executor = executor;
        this.log = log;
    }

    void submit(Runnable write) {
        queue.add(write);
        schedule();
    }

    private void schedule() {
        if (!scheduled.compareAndSet(false, true)) return;
        try {
            executor.execute(this::drain);
        } catch (RuntimeException e) {
            log.warn("store writer executor rejected work, writing inline: {}", e.toString());
            drain();
        }
    }

    private void drain() {
        try {
            Runnable r;
            while ((r = queue.poll()) != null) {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    log.error("store write failed", e);
                }
            }
        } finally {
            scheduled.set(false);
            if (!queue.isEmpty()) schedule();
        }
    }
}
