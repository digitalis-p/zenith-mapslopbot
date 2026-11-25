package io.github.digitalisp.mapart.engine;

import org.slf4j.Logger;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.config.MapArtConfig.PlatformCfg;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How coordinates appear in Discord ({@code discord.coordinates}). REAL, the default (a private channel),
 * leaves every text alone and the safety net below is off. RELATIVE, for a channel that is shared, shows X and Z
 * relative to the origin: the map centre of the first enabled MOSS or SOUL_SAND platform ({@code minX + 64,
 * minZ + 64}). Y is unchanged, so relative = real − origin.
 *
 * <p>Text is composed with real coordinates everywhere, so logs, the terminal, job files and the config keep them.
 * It is converted only on its way to Discord, by {@link #discord}. "Near" means within {@value #NEAR} blocks of the
 * map centre of any configured platform; every position the engine works with is near. Conversion, in order:
 * <ol>
 *   <li>Points written by {@link #point} ("x.x, y.y, z.z"). A point that is not near any platform becomes
 *       {@value #FAR}: relative to a known landmark such as spawn it would give the origin away.</li>
 *   <li>Integer triples and pairs ("x,y,z" from {@code Pos.key()} and {@code Pos.toString()}, "x,z") whose X and
 *       Z are both near the same platform.</li>
 *   <li>Safety net: any other number with {@value #MIN_DIGITS} to {@value #MAX_DIGITS} digits whose value is near a
 *       platform centre's X or Z, in any format (ranges "a..b", Baritone goals, "z-72-…"). Counts as a hit.</li>
 *   <li>Digits cut off by truncation ("…" or "~") that could be the start of such a number are removed. Counts as
 *       a hit.</li>
 * </ol>
 * A hit means a format this class does not know reached Discord; {@link #discord(String, String, Logger)} logs a
 * WARN for it. Immutable and thread-safe.
 */
public final class CoordFormat {
    public static final String RELATIVE = "RELATIVE";
    public static final String REAL = "REAL";
    /** Blocks around a platform's map centre that count as the base. */
    public static final int NEAR = 1_000;
    /** Shown in place of a {@link #point} that is not near any platform. */
    public static final String FAR = "[far from base]";
    static final int MIN_DIGITS = 5;
    static final int MAX_DIGITS = 9;

    private static final Set<String> PLATFORM_KINDS = Set.of("MOSS", "SOUL_SAND");
    private static final CoordFormat UNCHANGED = new CoordFormat(false, 0, 0, new int[0], new int[0], null);

    private static final Pattern POINT =
        Pattern.compile("(?<![\\d.])(-?\\d+\\.\\d), (-?\\d+\\.\\d), (-?\\d+\\.\\d)(?!\\d|\\.\\d)");
    private static final Pattern TUPLE =
        Pattern.compile("(?<![\\d.])(-?\\d+), ?(-?\\d+)(?:, ?(-?\\d+))?(?!\\d|\\.\\d)");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    /** A whole digit run (not a fraction), optionally signed, directly followed by a truncation mark. */
    private static final Pattern CUT = Pattern.compile("-?(?<![\\d.])(\\d+)(?=[…~])");

    private final boolean relative;
    private final int originX;
    private final int originZ;
    /** Map centres of every configured platform (X and Z, same index). */
    private final int[] centreX;
    private final int[] centreZ;
    private final String problem;

    private CoordFormat(boolean relative, int originX, int originZ, int[] centreX, int[] centreZ, String problem) {
        this.relative = relative;
        this.originX = originX;
        this.originZ = originZ;
        this.centreX = centreX;
        this.centreZ = centreZ;
        this.problem = problem;
    }

    /** Text after conversion and the number of safety-net {@code hits}. */
    public record Converted(String text, int hits) { }

    // ---------------------------------------------------------------- construction

    /** Leaves every text unchanged. */
    public static CoordFormat real() {
        return UNCHANGED;
    }

    /** RELATIVE to {@code (originX, originZ)}, with that origin as the only platform centre. */
    public static CoordFormat relativeTo(int originX, int originZ) {
        return new CoordFormat(true, originX, originZ, new int[]{originX}, new int[]{originZ}, null);
    }

    /**
     * The format the config asks for: REAL when unset. An unknown value is treated as RELATIVE (the cautious reading
     * of a typo). RELATIVE without an enabled MOSS or SOUL_SAND platform falls back to REAL. {@link #problem()} says
     * when either happened. Pure: reads a snapshot of the platform list.
     */
    public static CoordFormat of(MapArtConfig config) {
        String raw = config.discord.coordinates;
        String mode = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (mode.isEmpty() || mode.equals(REAL)) return UNCHANGED;
        String problem = mode.equals(RELATIVE) ? null
            : "discord.coordinates is \"" + raw + "\", expected REAL or RELATIVE; using RELATIVE";
        List<PlatformCfg> list = config.site.platforms;
        PlatformCfg[] platforms = list == null ? new PlatformCfg[0] : list.toArray(new PlatformCfg[0]);
        int[] cx = new int[platforms.length];
        int[] cz = new int[platforms.length];
        int n = 0;
        PlatformCfg origin = null;
        for (PlatformCfg p : platforms) {
            if (p == null) continue;
            cx[n] = p.minX + 64;
            cz[n] = p.minZ + 64;
            n++;
            if (origin == null && p.enabled && p.kind != null
                && PLATFORM_KINDS.contains(p.kind.strip().toUpperCase(Locale.ROOT))) origin = p;
        }
        if (origin == null) {
            String none = "discord.coordinates is RELATIVE but no enabled MOSS or SOUL_SAND platform is configured; "
                + "Discord shows real coordinates";
            return new CoordFormat(false, 0, 0, new int[0], new int[0], problem == null ? none : problem + "; " + none);
        }
        return new CoordFormat(true, origin.minX + 64, origin.minZ + 64, Arrays.copyOf(cx, n), Arrays.copyOf(cz, n),
            problem);
    }

    /**
     * {@link #of} evaluated on every call, so config changes apply to the next message. Logs a WARN once per
     * distinct {@link #problem()}.
     */
    public static Supplier<CoordFormat> live(MapArtConfig config, Logger log) {
        AtomicReference<String> warned = new AtomicReference<>();
        return () -> {
            CoordFormat f = of(config);
            String p = f.problem;
            if (p == null) warned.set(null);
            else if (!p.equals(warned.getAndSet(p))) log.warn("Map art Discord coordinates: {}", p);
            return f;
        };
    }

    public boolean relative() {
        return relative;
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    /** Why the configured mode could not be applied as asked, or null. */
    public String problem() {
        return problem;
    }

    // ---------------------------------------------------------------- composing

    /** A precise position as the plugin writes it: "x.x, y.y, z.z". Discord text converts it at any distance. */
    public static String point(double x, double y, double z) {
        return String.format(Locale.ROOT, "%.1f, %.1f, %.1f", x, y, z);
    }

    /**
     * A distance for a text that also carries points: "12.5 blocks", or "over 1,000 blocks" beyond {@value #NEAR}
     * (a long distance to a landmark such as spawn would give the base away).
     */
    public static String distance(double blocks) {
        if (blocks <= NEAR) return String.format(Locale.ROOT, "%.1f blocks", blocks);
        return String.format(Locale.ROOT, "over %,d blocks", NEAR);
    }

    // ---------------------------------------------------------------- converting

    /** {@code text} as Discord shows it; logs a WARN naming {@code title} when the safety net had to act. */
    public String discord(String text, String title, Logger log) {
        if (!relative || text == null || text.isEmpty()) return text;
        Converted c = convert(text);
        if (c.hits() > 0 && log != null)
            log.warn("Map art Discord text \"{}\": {} real coordinate(s) in an unknown format were made relative",
                title, c.hits());
        return c.text();
    }

    /** {@code text} as Discord shows it, with the number of safety-net hits. REAL: unchanged, no hits. */
    public Converted convert(String text) {
        if (!relative || text == null || text.isEmpty()) return new Converted(text, 0);
        String s = points(text);
        s = tuples(s);
        int[] hits = new int[1];
        s = loose(s, hits);
        s = cut(s, hits);
        return new Converted(s, hits[0]);
    }

    private String points(String s) {
        Matcher m = POINT.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            BigDecimal x = new BigDecimal(m.group(1));
            BigDecimal z = new BigDecimal(m.group(3));
            String out = nearSamePlatform(x.doubleValue(), z.doubleValue())
                ? minus(x, originX) + ", " + m.group(2) + ", " + minus(z, originZ)
                : FAR;
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String tuples(String s) {
        Matcher m = TUPLE.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int zGroup = m.group(3) != null ? 3 : 2;
            String xs = m.group(1);
            String zs = m.group(zGroup);
            boolean big = digits(xs) >= MIN_DIGITS || digits(zs) >= MIN_DIGITS;
            if (!big || digits(xs) > MAX_DIGITS || digits(zs) > MAX_DIGITS
                || !nearSamePlatform(Long.parseLong(xs), Long.parseLong(zs))) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String whole = m.group();
            int base = m.start();
            String out = whole.substring(0, m.start(1) - base) + minus(new BigDecimal(xs), originX)
                + whole.substring(m.end(1) - base, m.start(zGroup) - base) + minus(new BigDecimal(zs), originZ)
                + whole.substring(m.end(zGroup) - base);
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String loose(String s, int[] hits) {
        Matcher m = NUMBER.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String tok = m.group();
            String out = tok;
            int d = digits(tok);
            if (d >= MIN_DIGITS && d <= MAX_DIGITS) {
                BigDecimal v = new BigDecimal(tok);
                char axis = axis(v.doubleValue());
                if (axis != 0) {
                    out = minus(v, axis == 'X' ? originX : originZ);
                } else {
                    // the sign may be lost ("a-b" ranges) or the minus a separator ("z-72-…"): try the other sign
                    BigDecimal w = v.negate();
                    axis = axis(w.doubleValue());
                    if (axis != 0) out = (tok.startsWith("-") ? "-" : "") + minus(w, axis == 'X' ? originX : originZ);
                }
                if (axis != 0) hits[0]++;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String cut(String s, int[] hits) {
        Matcher m = CUT.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String out = m.group();
            if (couldStartNear(m.group(1))) {
                out = "";
                hits[0]++;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * True if some number of 5..9 digits, of either sign, that starts with {@code digits} is near a platform
     * centre's X or Z.
     */
    private boolean couldStartNear(String digits) {
        int len = digits.length();
        if (len > MAX_DIGITS || digits.startsWith("0")) return false;
        long prefix = Long.parseLong(digits);
        long scale = 1;
        for (int i = 0; i < Math.max(0, MIN_DIGITS - len); i++) scale *= 10;
        for (int total = Math.max(len, MIN_DIGITS); total <= MAX_DIGITS; total++, scale *= 10) {
            long lo = prefix * scale;
            long hi = lo + scale - 1;
            for (int i = 0; i < centreX.length; i++)
                for (int c : new int[]{centreX[i], centreZ[i]})
                    if (overlapsNear(lo, hi, c) || overlapsNear(-hi, -lo, c)) return true;
        }
        return false;
    }

    private static boolean overlapsNear(long lo, long hi, int centre) {
        return lo <= (long) centre + NEAR && hi >= (long) centre - NEAR;
    }

    private boolean nearSamePlatform(double x, double z) {
        for (int i = 0; i < centreX.length; i++)
            if (Math.abs(x - centreX[i]) <= NEAR && Math.abs(z - centreZ[i]) <= NEAR) return true;
        return false;
    }

    /** 'X' or 'Z' when {@code v} is near a platform centre's X or Z (whichever is closer), else 0. */
    private char axis(double v) {
        double bx = Double.MAX_VALUE;
        double bz = Double.MAX_VALUE;
        for (int i = 0; i < centreX.length; i++) {
            bx = Math.min(bx, Math.abs(v - centreX[i]));
            bz = Math.min(bz, Math.abs(v - centreZ[i]));
        }
        if (Math.min(bx, bz) > NEAR) return 0;
        return bx <= bz ? 'X' : 'Z';
    }

    private static String minus(BigDecimal v, int origin) {
        return v.subtract(BigDecimal.valueOf(origin)).toPlainString();
    }

    /** Digits before the decimal point, without the sign. */
    private static int digits(String number) {
        int start = number.startsWith("-") ? 1 : 0;
        int dot = number.indexOf('.');
        return (dot < 0 ? number.length() : dot) - start;
    }
}
