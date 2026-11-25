package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.config.MapArtConfig;

import java.util.Locale;

/**
 * How lane blocks are placed ({@code build.placeMode}). Pure.
 *
 * <ul>
 *   <li>{@link #AIRPLACE}: the 2b2t printer meta (IceTank litematica-printer, printerAirPlace true). Per block, in one
 *       tick before ZenithProxy's movement packet: [select the hotbar slot], swap it into the offhand, use-item-on the
 *       target cell itself (offhand, face UP, cursor at the cell centre, inside), swing the offhand, swap back
 *       ({@link AirPlace}). No rotation and no line of sight; eye → cell centre within {@code build.airPlaceRange}.
 *       Blocks whose placed state would follow the look still go by {@link #LOOK} ({@link TopFaceRules#placeMethod}).</li>
 *   <li>{@link #LOOK}: aim at the top face of the block below, click on the next tick, pipelined ({@link PlacePipeline}).</li>
 * </ul>
 */
public enum PlaceMode {
    AIRPLACE, LOOK;

    /** Eye height above the art layer's floor face minus half a block: eye → cell centre, vertically, on the floor. */
    public static final double EYE_ABOVE_CELL_CENTRE = Aim.EYE_HEIGHT - 0.5;

    /** {@code build.placeMode}, case-insensitive; unknown or null = LOOK (the older, proven behaviour). */
    public static PlaceMode of(@Nullable String s) {
        if (s == null) return LOOK;
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "AIRPLACE", "AIR_PLACE", "AIR" -> AIRPLACE;
            default -> LOOK;
        };
    }

    public static PlaceMode of(MapArtConfig.BuildCfg b) {
        return of(b.placeMode);
    }

    /**
     * Lane half width for the planner: LOOK = {@code laneHalfWidth}; AIRPLACE = {@code airPlaceLaneHalfWidth}, or with
     * -1 (auto) {@link LaneGeometry#maxAirPlaceHalfWidth} of {@code airPlaceRange}.
     */
    public static int laneHalfWidth(MapArtConfig.BuildCfg b) {
        return laneHalfWidth(b, true);
    }

    /**
     * {@link #laneHalfWidth(MapArtConfig.BuildCfg)}; with {@code airPlaceAvailable} false (the offhand holds a block,
     * or the offhand guard gave up: every cell goes by LOOK) the LOOK width, since 9-wide lanes placed by looks leave
     * their outer columns to stand spots.
     */
    public static int laneHalfWidth(MapArtConfig.BuildCfg b, boolean airPlaceAvailable) {
        if (of(b) == LOOK || !airPlaceAvailable) return Math.max(0, b.laneHalfWidth);
        if (b.airPlaceLaneHalfWidth >= 0) return b.airPlaceLaneHalfWidth;
        return LaneGeometry.maxAirPlaceHalfWidth(b.airPlaceRange);
    }

    /**
     * Looks are sent silently (the printer's grimRotate with rotatePlayer false): an extra PlayerInput + PosRot with
     * the target rotation, ZenithProxy's Bot re-sends the real rotation in the same tick ({@link SilentRotation}).
     */
    public static boolean silentRotation(MapArtConfig.BuildCfg b) {
        return !b.rotatePlayer && b.grimRotate;
    }
}
