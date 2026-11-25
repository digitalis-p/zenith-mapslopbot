package io.github.digitalisp.mapart.bot.impl;

import com.zenith.mc.block.Block;
import com.zenith.mc.block.Direction;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.zenith.Globals.BLOCK_DATA;

/**
 * Places one block (a replacement anvil at {@code site.anvil}). The decisions are {@link PlaceRun}'s: a full-cube
 * neighbour's face touching the cell, its centre in reach and in sight from an exact-cell stand spot ({@link Approach}),
 * look on one tick and click on the next, a sneak-click when the only support does something on a right-click (the anvil
 * tower's crafting table). Stand spots stay off every platform's art layer ({@link PlaceRun#standCells}) and leave the
 * cell free. Confirmed from the world; up to 3 clicks. Short and rare: it does not park.
 */
final class PlaceBlockTask extends ZenithTask {
    private final Pos pos;
    private final String itemId;
    private @Nullable PlaceRun<Aiming.Shot> run;
    private @Nullable PlaceIo io;

    PlaceBlockTask(ZenithBotActions ctx, Pos pos, String itemId) {
        super(ctx, WalkTask.walkTimeout(pos) + 1800 + 400L * (Approach.MAX_SPOTS - 1));
        this.pos = pos;
        this.itemId = ItemIds.ns(itemId);
    }

    @Override
    public String name() {
        return "placeBlock " + itemId + " at " + pos;
    }

    @Override
    protected String progress() {
        return run == null ? "" : run.progress();
    }

    /** The walk to the placement; the click waits for the quiet window. */
    @Override
    protected SprintLegs.Walk walkKind() {
        return SprintLegs.Walk.PLACE;
    }

    @Override
    protected TaskStatus step() {
        String refusal = protectedRegionRefusal(pos, "place a block at");
        if (refusal != null) return fail(refusal);
        if (!ctx.world().isLoaded(pos)) {
            TaskStatus s = walkNear(pos, 3, 3);
            if (s == TaskStatus.FAILED) return fail("walking to " + pos + ": " + walker.error());
            if (s == TaskStatus.SUCCESS && !ctx.world().isLoaded(pos)) return fail("chunk of " + pos + " not loaded");
            return TaskStatus.RUNNING;
        }
        if (BlockStates.block(itemId) == null) return fail(itemId + " does not place a block");
        if (run == null) {
            walker.stop(); // the walk that loaded the chunk ends here
            io = new PlaceIo(new ArrayList<>());
            run = new PlaceRun<>(pos, itemId, reach(), cfg.build.placeConfirmTicks, io).brake(cfg.build.standSpotBrake);
        }
        TaskStatus s = run.tick(now);
        if (s == TaskStatus.FAILED) return fail(run.failure());
        return s;
    }

    /** {@link PlaceRun.Io} on ZenithProxy: the approach's world and clicks plus the support and hand checks. */
    private final class PlaceIo extends ApproachIo implements PlaceRun.Io<Aiming.Shot> {
        /** The approach's targets: the supports {@link #aimAt} names. */
        private final List<Pos> supports;

        PlaceIo(List<Pos> supports) {
            super(() -> supports, PlaceRun.standCells(UseBlockTask.platforms(ctx.config())));
            this.supports = supports;
        }

        @Override
        public boolean solidSupport(Pos p) {
            int state = ctx.world().stateId(p);
            Block b = BLOCK_DATA.getBlockDataFromBlockStateId(state);
            return b != null && !b.isAir() && !b.replaceable() && BLOCK_DATA.isShapeFullBlock(state);
        }

        @Override
        public boolean interactive(Pos p) {
            Block b = BLOCK_DATA.getBlockDataFromBlockStateId(ctx.world().stateId(p));
            return b != null && (b.blockEntityType() != null || PlaceRun.interactive(ctx.world().block(p)));
        }

        @Override
        public boolean occupied(Pos p) {
            return BlockStates.blocksPlacement(ctx.world().stateId(p));
        }

        @Override
        public int count(String item) {
            return Inv.count(Inv.is(item));
        }

        @Override
        public boolean hold(String item) {
            return hotbar.ensureHeld(Inv.is(item), Hotbar.SPECIAL_SLOT, now);
        }

        @Override
        public void aimAt(Map<Pos, Integer> faces, boolean sneak, Pos keepClear) {
            Map<Pos, Direction> dirs = new LinkedHashMap<>();
            faces.forEach((p, f) -> dirs.put(p, Direction.values()[f]));
            supports.clear();
            supports.addAll(dirs.keySet());
            configure(dirs, sneak, keepClear);
        }

        @Override
        public boolean click(Aiming.Shot shot) {
            var hand = Inv.mainHand();
            if (Inv.empty(hand) || !Inv.is(itemId).test(hand)) return false; // the item left the hand: no click
            return super.click(shot);
        }
    }
}
