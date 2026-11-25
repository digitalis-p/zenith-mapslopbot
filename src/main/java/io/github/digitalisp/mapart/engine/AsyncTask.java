package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.bot.TaskStatus;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Runs blocking work (file I/O, parsing) on an executor and completes on the tick thread, so the
 * engine can treat it like any other task (retries, cancellation). The result callback runs on the
 * tick thread. A cancelled task drops its result.
 */
final class AsyncTask<T> extends BotTask {
    private final String name;
    private final Executor executor;
    private final Callable<T> work;
    private final Consumer<T> onResult;
    private boolean submitted;
    private volatile boolean done;
    private volatile T result;
    private volatile Throwable error;

    AsyncTask(String name, Executor executor, Callable<T> work, Consumer<T> onResult) {
        this.name = name;
        this.executor = executor;
        this.work = work;
        this.onResult = onResult;
    }

    @Override
    protected TaskStatus onTick() {
        if (!submitted) {
            submitted = true;
            try {
                executor.execute(() -> {
                    try {
                        result = work.call();
                    } catch (Throwable t) {
                        error = t;
                    }
                    done = true;
                });
            } catch (RuntimeException e) {
                return fail("executor rejected work: " + e);
            }
        }
        if (!done) return TaskStatus.RUNNING;
        if (error != null) return fail(String.valueOf(error.getMessage() != null ? error.getMessage() : error));
        onResult.accept(result);
        return TaskStatus.SUCCESS;
    }

    @Override
    public String name() {
        return name;
    }
}
