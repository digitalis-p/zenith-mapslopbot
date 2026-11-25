package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.ContainerVisit;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.zenith.Globals.BOT;

/**
 * {@code BotActions.planVisits} on ZenithProxy: a {@link SweepPlanner} over the cached world. A stand cell counts for a
 * container exactly when {@link Approach} would accept it: the {@link StandSpots} floor rules and ZenithProxy's
 * raycast to a face centre ({@link ZenithTask.ApproachIo}), both halves of a double chest. The raycasts are spread over
 * ticks, {@link SweepPlanner#CONTAINERS_PER_TICK} containers per tick (the whole moss sorter, 310 containers, takes
 * about 20 ticks), so no tick stalls. Nothing moves and nothing is clicked meanwhile. With an end point ({@code then},
 * a restock's next lane start) the sweep runs in the direction that makes bot → containers → {@code then} shortest.
 */
final class PlanVisitsTask extends ZenithTask {
    /** Far more than a whole sorter needs; the engine falls back to the nearest-first order when it runs out. */
    static final long TIMEOUT_TICKS = 600;

    private final List<Pos> containers;
    private final @Nullable Pos then;
    private final Consumer<List<ContainerVisit>> result;
    private @Nullable SweepPlanner planner;

    PlanVisitsTask(ZenithBotActions ctx, List<Pos> containers, Consumer<List<ContainerVisit>> result) {
        this(ctx, containers, null, result);
    }

    /** @param then where the walk goes after the last container, or null */
    PlanVisitsTask(ZenithBotActions ctx, List<Pos> containers, @Nullable Pos then,
                   Consumer<List<ContainerVisit>> result) {
        super(ctx, TIMEOUT_TICKS);
        this.containers = List.copyOf(containers);
        this.then = then;
        this.result = result;
    }

    @Override
    public String name() {
        return "planVisits " + containers.size() + (then != null ? " then " + then : "");
    }

    @Override
    protected TaskStatus step() {
        holdStill(); // the input stays ours and keyless while it works (like a wait)
        if (planner == null) {
            StandSpots.CellRule anywhere = StandSpots.CellRule.ANYWHERE; // as a ContainerTask's approach
            List<SweepPlanner.Target> targets = new ArrayList<>(containers.size());
            for (Pos p : containers) {
                List<Pos> blocks = List.copyOf(ctx.containerHalves(p));
                targets.add(new SweepPlanner.Target(p, blocks, new ApproachIo(() -> blocks, anywhere),
                    ctx.failedStandSpots(p)));
            }
            planner = new SweepPlanner(new ApproachIo(List::of, anywhere), targets, reach(), BOT.getX(), BOT.getY(),
                BOT.getZ(), then);
        }
        if (!planner.step(SweepPlanner.CONTAINERS_PER_TICK)) return TaskStatus.RUNNING;
        result.accept(planner.plan().visits());
        return TaskStatus.SUCCESS;
    }
}
