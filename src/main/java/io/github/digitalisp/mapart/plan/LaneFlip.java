package io.github.digitalisp.mapart.plan;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;

/**
 * Lanes walked the other way ({@code build.restockLaneFlip}): the restock planner
 * may run a load's lanes in the opposite serpentine parity, its first lane S→N instead of N→S. A flipped lane swaps
 * {@code from} and {@code to} and walks its rows in the reverse order; the cells of a row keep their order (the
 * serpentine planner orders a row by the distance to the walking line, west before east, whichever way the lane is
 * walked). Pure.
 */
public final class LaneFlip {
    private LaneFlip() {
    }

    /** {@code lane} walked the other way: the rows (runs of cells with the same z) in reverse order. */
    public static Lane flip(Lane lane) {
        List<Placement> ps = lane.placements();
        List<Placement> out = new ArrayList<>(ps.size());
        int end = ps.size();
        while (end > 0) {
            int z = ps.get(end - 1).pos().z();
            int start = end - 1;
            while (start > 0 && ps.get(start - 1).pos().z() == z) start--;
            out.addAll(ps.subList(start, end));
            end = start;
        }
        return new Lane(lane.index(), lane.to(), lane.from(), List.copyOf(out));
    }

    /** Every lane of {@code lanes} flipped, in the same order. */
    public static List<Lane> flip(List<Lane> lanes) {
        List<Lane> out = new ArrayList<>(lanes.size());
        for (Lane l : lanes) out.add(flip(l));
        return List.copyOf(out);
    }

    /** The lanes a load of {@code plan} walks: {@code lanes}, flipped when the plan says so. */
    public static List<Lane> walked(List<Lane> lanes, RestockPlanner.@Nullable Plan plan) {
        return plan != null && plan.flipped() ? flip(lanes) : lanes;
    }

    /** Where a load of {@code plan} starts its first lane (the walk's and the restock sweep's end); no copy. */
    public static Pos start(List<Lane> lanes, RestockPlanner.@Nullable Plan plan) {
        Lane first = lanes.getFirst();
        return plan != null && plan.flipped() ? first.to() : first.from();
    }
}
