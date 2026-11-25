package io.github.digitalisp.mapart.model;

/** Immutable integer block position in world coordinates. CONTRACT (lead-owned). */
public record Pos(int x, int y, int z) {
    public Pos offset(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    public Pos below() {
        return offset(0, -1, 0);
    }

    public Pos above() {
        return offset(0, 1, 0);
    }

    public double distSq(double px, double py, double pz) {
        double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    public int manhattan(Pos o) {
        return Math.abs(x - o.x) + Math.abs(y - o.y) + Math.abs(z - o.z);
    }

    /** "x,y,z" — also used as a stable map key in persisted JSON. */
    public String key() {
        return x + "," + y + "," + z;
    }

    public static Pos parseKey(String key) {
        String[] p = key.split(",");
        return new Pos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
    }

    @Override
    public String toString() {
        return "[" + key() + "]";
    }
}
