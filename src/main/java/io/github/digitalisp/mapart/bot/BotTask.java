package io.github.digitalisp.mapart.bot;

/**
 * A unit of in-game work driven by the engine, one {@link #tick()} per ZenithProxy ClientBotTick
 * (client tick thread — never block, never do I/O). A task owns any Baritone process, input request
 * or open container it starts and must release them in {@link #cancel()}.
 *
 * <p>Tasks must be restartable: after a disconnect the engine discards the task and builds a new one
 * from world/inventory state. CONTRACT (lead-owned); {@link #requestPark}, {@link #parked} and {@link #resume} were
 * added for eating mid-lane (all optional).
 */
public abstract class BotTask {
    private String failure;
    private boolean finished;

    /** Advance one tick. Return RUNNING until done. After SUCCESS/FAILED the task is not ticked again. */
    public final TaskStatus tick() {
        if (finished) return failure == null ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        TaskStatus s;
        try {
            s = onTick();
        } catch (RuntimeException e) {
            failure = name() + ": " + e;
            s = TaskStatus.FAILED;
        }
        if (s != TaskStatus.RUNNING) {
            finished = true;
            if (s == TaskStatus.FAILED && failure == null) failure = name() + " failed";
            release();
        }
        return s;
    }

    /** Stop now (pause, player took control, disconnect, engine shutdown). Idempotent. */
    public final void cancel() {
        if (finished) return;
        finished = true;
        if (failure == null) failure = name() + " cancelled";
        release();
    }

    protected abstract TaskStatus onTick();

    /** Release Baritone/input/container resources. Called exactly once when the task ends or is cancelled. */
    protected void release() { }

    /**
     * Asks the task to stop at its next safe point and wait there, parked, so the engine can eat or stand still
     * (hunger, low health) and then {@link #resume()} it. A safe point: no click or inventory request in flight, no
     * placement waiting for the server, no container open, Baritone stopped, on the ground. The engine keeps ticking
     * the task until {@link #parked()} and then not at all until it resumes it.
     *
     * @return true if the task can park; false (the default) means it runs on to its end, which is short for such tasks
     */
    public boolean requestPark() {
        return false;
    }

    /** True once the task stopped at a safe point after {@link #requestPark()}. */
    public boolean parked() {
        return false;
    }

    /**
     * Continues a parked task. The bot may have moved meanwhile (it fetched food from a chest): the task walks back
     * or re-plans from where it stands. Its timeout does not count the parked time.
     */
    public void resume() { }

    /** Mark failure with a human-readable reason and return FAILED: {@code return fail("chest empty");} */
    protected final TaskStatus fail(String reason) {
        failure = name() + ": " + reason;
        return TaskStatus.FAILED;
    }

    public final String failure() {
        return failure;
    }

    public final boolean isFinished() {
        return finished;
    }

    public abstract String name();
}
