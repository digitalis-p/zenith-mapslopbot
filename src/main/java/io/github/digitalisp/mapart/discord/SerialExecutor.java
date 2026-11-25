package io.github.digitalisp.mapart.discord;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/**
 * Runs tasks one at a time, in submission order, on a shared executor (the pattern from the
 * {@link Executor} javadoc). Intake uses it so uploads are parsed one after another (bounded memory,
 * stable queue positions) without owning a thread.
 */
final class SerialExecutor implements Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private final Executor executor;
    private Runnable active;

    SerialExecutor(Executor executor) {
        this.executor = executor;
    }

    @Override
    public synchronized void execute(Runnable r) {
        tasks.add(() -> {
            try {
                r.run();
            } finally {
                scheduleNext();
            }
        });
        if (active == null) scheduleNext();
    }

    private synchronized void scheduleNext() {
        if ((active = tasks.poll()) == null) return;
        try {
            executor.execute(active);
        } catch (RuntimeException e) { // executor shut down: drop the backlog instead of wedging
            tasks.clear();
            active = null;
            throw e;
        }
    }
}
