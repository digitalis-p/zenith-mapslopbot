package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.pathfinder.MutableMoveResult;
import com.zenith.feature.pathfinder.movement.ActionCosts;
import com.zenith.feature.pathfinder.movement.CalculationContext;
import com.zenith.feature.pathfinder.movement.MovementHelper;
import com.zenith.feature.pathfinder.movement.Moves;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Where the pathfinder could walk from a cell at all, goal or not: a breadth-first search over ZenithProxy's own move
 * set, capped by the number of movements tried. A search that runs out of cells before the cap means the bot is boxed
 * in: ZenithProxy then reports "No path found ... 44 movements considered" for every goal (live 2026-10-12: two cells
 * on a parked flying machine, every way out a drop into flowing water). Pure apart from {@link #zenith()}.
 */
final class LocalReach {
    private LocalReach() { }

    /** Fewer movements than this, all tried, and nothing left to try: boxed in (A* tries 22 moves per cell). */
    static final int BOXED_IN_MOVEMENTS = 100;

    /** One cell's moves: calls {@code to} with each cell one movement away, returns how many movements were tried. */
    interface Expander {
        int expand(Pos from, Consumer<Pos> to);

        /** True once a move was skipped because its chunk is not loaded (the search then proves nothing). */
        default boolean skippedUnloaded() {
            return false;
        }
    }

    /**
     * @param cells     the cells reached, the start first
     * @param movements movements tried
     * @param exhausted true if every reached cell was expanded before the cap
     * @param unloaded  true if a move was skipped for an unloaded chunk
     */
    record Result(Set<Pos> cells, int movements, boolean exhausted, boolean unloaded) {
        /** All the bot can reach was searched, in loaded chunks, with fewer than {@link #BOXED_IN_MOVEMENTS} movements. */
        boolean boxedIn() {
            return exhausted && !unloaded && movements < BOXED_IN_MOVEMENTS;
        }
    }

    /** Breadth-first from {@code start} until no cell is left or {@code movementCap} movements were tried. */
    static Result explore(Pos start, Expander moves, int movementCap) {
        Set<Pos> seen = new LinkedHashSet<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        int movements = 0;
        while (!queue.isEmpty()) {
            if (movements >= movementCap) return new Result(seen, movements, false, moves.skippedUnloaded());
            movements += moves.expand(queue.poll(), p -> {
                if (seen.add(p)) queue.add(p);
            });
        }
        return new Result(seen, movements, true, moves.skippedUnloaded());
    }

    /**
     * Whether the pathfinder can start a path at {@code feet}: it can walk on the floor. On any other floor (magma, say)
     * ZenithProxy starts the path from the nearest neighbouring cell instead, which may lie back in the pocket. Client
     * tick thread.
     */
    static boolean resumable(Pos feet) {
        return MovementHelper.canWalkOn(feet.x(), feet.y() - 1, feet.z());
    }

    /**
     * ZenithProxy's moves as its A* applies them (feet cells, the pathfinder's settings of this moment): a move into
     * another chunk that is not loaded is skipped ({@link Expander#skippedUnloaded}), every other one counts as tried;
     * a cost below {@link ActionCosts#COST_INF} is a way on. Client tick thread.
     */
    static Expander zenith() {
        CalculationContext ctx = new CalculationContext();
        MutableMoveResult res = new MutableMoveResult();
        Moves[] all = Moves.values();
        return new Expander() {
            private boolean unloaded;

            @Override
            public int expand(Pos from, Consumer<Pos> to) {
                int tried = 0;
                for (Moves m : all) {
                    int nx = from.x() + m.xOffset;
                    int nz = from.z() + m.zOffset;
                    if (((nx >> 4) != (from.x() >> 4) || (nz >> 4) != (from.z() >> 4)) && !ctx.isLoaded(nx, nz)) {
                        unloaded = true;
                        continue;
                    }
                    res.reset();
                    m.apply(ctx, from.x(), from.y(), from.z(), res);
                    tried++;
                    if (res.cost < ActionCosts.COST_INF) to.accept(new Pos(res.x, res.y, res.z));
                }
                return tried;
            }

            @Override
            public boolean skippedUnloaded() {
                return unloaded;
            }
        };
    }
}
