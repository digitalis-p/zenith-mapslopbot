package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Carry-over between tiles, the pure part: what the end of a stored
 * map breaks when the next tile on the same platform keeps the cells the two share, and the time estimates the engine
 * decides by.
 *
 * <p>Normal end of tile: the NUKE breaks every block the piston clear cannot remove, the clear (when any clearable block
 * is left) sweeps the rest, and the next tile places all its cells. Carry-over: the NUKE breaks every block on the art
 * layer that is not the next tile's block in its cell (clearable blocks too, by hand), no clear, and the next tile
 * places only its cells that are then air.
 *
 * <p>Rates (client ticks, 20/s), measured live on 2b2t 2026-11-02 (Snowy Angel on soul sand, build.nukeLambda on): a
 * block the normal nuke breaks {@link #NUKE_TICKS}, a block a carry-over breaks {@link #CARRY_BREAK_TICKS} (any kind: its
 * targets are scattered among the kept blocks, which the sweep walks around), a placed cell {@link #PLACE_TICKS}, the
 * clear with its wait {@link #CLEAR_SECONDS}. The first rates (8.1 per nuked block, 4.1 per carpet by hand, the same
 * walk for both paths) chose the first live carry-over, [11,5] → [12,5], at an estimated 52 min against 93; it took
 * 56 min to break alone, against about 37 min for the normal nuke of [10,5].
 */
final class CarryOver {
    private CarryOver() { }

    /** Ticks per non-clearable block the normal nuke breaks: Lambda's scheme, 11,067 blocks of [10,5] in about 37 min. */
    static final double NUKE_TICKS = 4.0;
    /**
     * Ticks per block a carry-over breaks, carpets by hand included: 7,854 blocks of [11,5] in 56.4 min (49 detours around
     * kept blocks; the normal nuke of [10,5] had none).
     */
    static final double CARRY_BREAK_TICKS = 8.6;
    /** Ticks per placed cell, restock trips included: 16,384 cells in about 23 min. */
    static final double PLACE_TICKS = 1.7;
    /** The piston clear and the wait for the art layer to read all air, with the carts' run-on (70-80 s live). */
    static final double CLEAR_SECONDS = 80;

    /**
     * Both ends of tile, counted on the world as it is now.
     *
     * @param kept        cells already the next tile's block (kept by a carry-over)
     * @param breakFull   carry-over: full blocks that differ from the next tile (broken by the nuke)
     * @param breakHand   carry-over: clearable blocks that differ (broken by hand)
     * @param placeCarry  carry-over: the next tile's cells still to place afterwards
     * @param nukeNormal  normal: blocks the NUKE breaks (every non-clearable block)
     * @param clearNormal normal: some clearable block is left for the piston clear
     * @param placeNormal normal: every cell of the next tile
     * @param fluid       fluid on the art layer: no carry-over (the normal path blocks on it as before)
     */
    record Estimate(int kept, int breakFull, int breakHand, int placeCarry, int nukeNormal, boolean clearNormal,
                    int placeNormal, boolean fluid) {
        double carrySeconds() {
            return ((breakFull + breakHand) * CARRY_BREAK_TICKS + placeCarry * PLACE_TICKS) / 20.0;
        }

        double normalSeconds() {
            return (nukeNormal * NUKE_TICKS + placeNormal * PLACE_TICKS) / 20.0 + (clearNormal ? CLEAR_SECONDS : 0);
        }

        /** Carry-over at most {@code 1 - margin} of the normal estimate (and nothing on the layer it cannot handle). */
        boolean carry(double margin) {
            double m = Math.max(0, Math.min(0.9, margin));
            return !fluid && kept > 0 && carrySeconds() <= normalSeconds() * (1 - m);
        }

        /** Seconds the carry-over is estimated to save (negative: slower). */
        double savedSeconds() {
            return normalSeconds() - carrySeconds();
        }

        /** "keep 9,120 cells, break 1,200 (300 by hand), place 7,264: about 12.1 min; nuke 16,000 + clear + place 16,384: about 135.4 min". */
        String describe() {
            return String.format(Locale.ROOT, "keep %,d cells, break %,d (%,d by hand), place %,d: about %.1f min;"
                    + " nuke %,d%s + place %,d: about %.1f min", kept, breakFull + breakHand, breakHand, placeCarry,
                carrySeconds() / 60, nukeNormal, clearNormal ? " + clear" : "", placeNormal, normalSeconds() / 60);
        }
    }

    /**
     * The estimate for ending tile {@code now} with {@code next} built next on the same platform (both plans of that
     * platform: the same geometry and fixtures). Reads every cell of the art layer once; the chunks must be loaded.
     */
    static Estimate estimate(TilePlan now, TilePlan next, WorldView w, Collection<String> clearable) {
        TilePlan.Survey s = next.survey(w);
        int breakFull = 0, breakHand = 0, place = s.missing().size();
        for (Pos p : s.wrong()) {
            BlockSpec b = w.block(p);
            if (TilePlan.isFluidBlock(b)) continue;
            if (clearable.contains(b.id())) breakHand++;
            else breakFull++;
            if (next.wants(p)) place++; // broken, then placed again as the next tile's block
        }
        int nuke = now.nukeTargets(w, clearable).size();
        TilePlan.Survey all = now.survey(w);
        // what the nuke leaves for the clear: any non-air block that is not a nuke target (clearable)
        boolean clear = all.nonAir() > nuke;
        return new Estimate(s.correct(), breakFull, breakHand, place, nuke, clear, next.total, s.fluid() || all.fluid());
    }

    /**
     * The carry-over NUKE's targets: every block on the art layer that is not {@code next}'s block in its cell
     * (clearable ones included), fluids left out (they cannot be broken; {@link #estimate} refuses a carry-over with
     * one). Fixtures are never targets. Re-read every round.
     */
    static List<Pos> targets(TilePlan next, WorldView w) {
        List<Pos> out = new ArrayList<>();
        for (Pos p : next.survey(w).wrong()) if (!TilePlan.isFluidBlock(w.block(p))) out.add(p);
        return out;
    }
}
