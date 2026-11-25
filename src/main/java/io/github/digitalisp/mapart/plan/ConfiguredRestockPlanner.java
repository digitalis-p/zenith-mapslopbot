package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.model.Lane;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * The restock planner {@code build.restockPlanner} names, read on every load (a config change applies to the next
 * restock): {@link GreedyRestockPlanner}, or {@link SplitRestockPlanner} with or without the two-lane rule
 * ({@link RestockMode}). Pure.
 */
public final class ConfiguredRestockPlanner implements RestockPlanner {
    private final Supplier<String> mode;
    private final GreedyRestockPlanner greedy = new GreedyRestockPlanner();
    private final SplitRestockPlanner lanes;
    private final SplitRestockPlanner split;

    /** @param mode the {@code build.restockPlanner} value (read each time); the carried-stacks guard on */
    public ConfiguredRestockPlanner(Supplier<String> mode) {
        this(mode, () -> true);
    }

    /**
     * @param mode         the {@code build.restockPlanner} value (read each time)
     * @param carriedGuard the {@code build.restockCarriedGuard} value (read each time, {@link SplitRestockPlanner})
     */
    public ConfiguredRestockPlanner(Supplier<String> mode, BooleanSupplier carriedGuard) {
        this.mode = mode;
        lanes = new SplitRestockPlanner(true, carriedGuard);
        split = new SplitRestockPlanner(false, carriedGuard);
    }

    /** The planner for {@code lanes} under the current mode. */
    public RestockPlanner forLanes(List<Lane> remaining) {
        return switch (RestockMode.of(mode.get()).resolve(remaining)) {
            case GREEDY -> greedy;
            case LANES -> lanes;
            case SPLIT, AUTO -> split;
        };
    }

    @Override
    public Plan plan(List<Lane> remaining, Map<String, Integer> held, int freeSlots, ToIntFunction<String> maxStack) {
        return forLanes(remaining).plan(remaining, held, freeSlots, maxStack);
    }

    @Override
    public Plan plan(Request r) {
        return forLanes(r.remaining()).plan(r);
    }

    @Override
    public boolean expensive() {
        return RestockMode.of(mode.get()) != RestockMode.GREEDY;
    }
}
