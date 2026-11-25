package io.github.digitalisp.mapart.bot.impl;

/**
 * Coordinates along an axis-aligned lane. {@code s} is the distance along the walking direction measured from the
 * centre of the {@code from} cell; {@code lateral} is the signed offset from the lane line (the line through the
 * cell centres of from/to). The corridor is the column of cells the walking player's 0.6-wide box covers. Pure.
 */
public final class LaneGeometry {
    private final boolean alongZ;
    private final int dir;
    private final double originAlong;
    private final double lineCross;
    private final double length;

    public LaneGeometry(int fromX, int fromZ, int toX, int toZ) {
        if (fromX != toX && fromZ != toZ) {
            throw new IllegalArgumentException("lane must be axis-aligned: " + fromX + "," + fromZ + " -> " + toX + "," + toZ);
        }
        this.alongZ = fromX == toX;
        int a0 = alongZ ? fromZ : fromX;
        int a1 = alongZ ? toZ : toX;
        this.dir = a1 >= a0 ? 1 : -1;
        this.originAlong = a0 + 0.5;
        this.lineCross = (alongZ ? fromX : fromZ) + 0.5;
        this.length = Math.abs(a1 - a0);
    }

    public boolean alongZ() {
        return alongZ;
    }

    /** +1 if walking toward increasing coordinate, else -1. */
    public int dir() {
        return dir;
    }

    /** s of the {@code to} cell centre. */
    public double length() {
        return length;
    }

    public double s(double x, double z) {
        return ((alongZ ? z : x) - originAlong) * dir;
    }

    public double lateral(double x, double z) {
        return (alongZ ? x : z) - lineCross;
    }

    public double cellS(int x, int z) {
        return s(x + 0.5, z + 0.5);
    }

    public double cellLateral(int x, int z) {
        return lateral(x + 0.5, z + 0.5);
    }

    /** True for cells in the column the player walks along. */
    public boolean inCorridor(int x, int z) {
        return Math.abs(cellLateral(x, z)) < 0.5;
    }

    /** World {dx, dz} unit vector of the walking direction. */
    public double[] forward() {
        return alongZ ? new double[]{0, dir} : new double[]{dir, 0};
    }

    /** World {dx, dz} that steers toward the lane line (unit lateral axis times -lateral, clamped to 1). */
    public double[] steer(double x, double z, double gain) {
        double lat = lateral(x, z);
        double c = Math.max(-1, Math.min(1, -lat * gain));
        return alongZ ? new double[]{c, 0} : new double[]{0, c};
    }

    /** Range kept off the airplace range for the lane width: the 0.05 click margin plus one walking step (0.25). */
    public static final double AIR_WIDTH_MARGIN = 0.3;
    /** Along-lane distance either side of a cell over which an outer column must stay in range (2 blocks of walk). */
    public static final double AIR_MIN_WINDOW = 1.0;

    /**
     * Widest lane half width for airplace: the largest column offset L whose cell centres stay within
     * {@code range − AIR_WIDTH_MARGIN} of the eye of a bot on the lane line (feet on the art layer, eye
     * {@link PlaceMode#EYE_ABOVE_CELL_CENTRE} above the centres) over ±{@link #AIR_MIN_WINDOW} along the lane:
     * L² + 1.12² + 1² ≤ (range − 0.3)². 4 (9-wide lanes) for range 5.0 (the outer column is in range for about ±2.2 blocks
     * along the lane while walking); 3 for 4.5. Never negative.
     */
    public static int maxAirPlaceHalfWidth(double range) {
        double r = range - AIR_WIDTH_MARGIN;
        if (!(r > 0)) return 0; // also NaN
        double h = PlaceMode.EYE_ABOVE_CELL_CENTRE;
        double l2 = r * r - h * h - AIR_MIN_WINDOW * AIR_MIN_WINDOW;
        if (l2 < 0) return 0;
        return (int) Math.floor(Math.sqrt(l2) + 1e-9);
    }

    /**
     * Along-lane half window over which a cell {@code lateral} columns off the line is within {@code range} of the eye
     * of a bot on the line (cell centre {@link PlaceMode#EYE_ABOVE_CELL_CENTRE} below the eye); NaN if never.
     */
    public static double airWindow(double lateral, double range) {
        double h = PlaceMode.EYE_ABOVE_CELL_CENTRE;
        double a2 = range * range - h * h - lateral * lateral;
        return a2 < 0 ? Double.NaN : Math.sqrt(a2);
    }
}
