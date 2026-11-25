package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;

import java.util.List;
import java.util.Locale;

/** {@code build.restockPlanner}: which planner chooses the restock loads. Pure. */
public enum RestockMode {
    /** {@link GreedyRestockPlanner}. */
    GREEDY,
    /**
     * {@link SplitRestockPlanner} with the two-lane rule: while a load of the current lane and the next one fits, loads
     * end at lane ends; when no lane pair fits, lanes are cut (the full split, the roadmap's fallback).
     */
    LANES,
    /** {@link SplitRestockPlanner} cutting lanes anywhere. */
    SPLIT,
    /** LANES for lanes up to {@link #AUTO_LANES_MAX_WIDTH} columns wide, SPLIT for wider ones. */
    AUTO;

    /** Widest lanes (columns) AUTO plans with the two-lane rule: LOOK's 5-wide lanes. */
    public static final int AUTO_LANES_MAX_WIDTH = 5;

    /** {@code value} read leniently (case and surrounding blanks ignored); unknown or null = AUTO. */
    public static RestockMode of(String value) {
        if (value == null) return AUTO;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AUTO;
        }
    }

    /** AUTO resolved for {@code lanes}: LANES or SPLIT by the widest lane's columns; other modes unchanged. */
    public RestockMode resolve(List<Lane> lanes) {
        if (this != AUTO) return this;
        return widest(lanes) <= AUTO_LANES_MAX_WIDTH ? LANES : SPLIT;
    }

    /** Columns of the widest lane (0 without placements). */
    static int widest(List<Lane> lanes) {
        int w = 0;
        for (Lane l : lanes) {
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (Placement p : l.placements()) {
                lo = Math.min(lo, p.pos().x());
                hi = Math.max(hi, p.pos().x());
            }
            if (hi >= lo) w = Math.max(w, hi - lo + 1);
        }
        return w;
    }
}
