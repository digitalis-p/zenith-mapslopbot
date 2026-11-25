package io.github.digitalisp.mapart.command;

import java.util.List;
import java.util.Locale;

/** Small pure formatting helpers shared by Discord and ZenithProxy command output. */
public final class Fmt {
    private Fmt() { }

    /** 12345 → "12,345". */
    public static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** "minecraft:white_carpet" → "white_carpet"; other namespaces are kept. */
    public static String item(String id) {
        if (id == null) return "?";
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** placed/total as "45.2%"; "0.0%" when total is 0. */
    public static String percent(long part, long total) {
        if (total <= 0) return "0.0%";
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * part / total);
    }

    /** Seconds → "45s", "12m", "1h 05m", "2d 03h". Negative → "unknown". */
    public static String duration(long seconds) {
        if (seconds < 0) return "unknown";
        if (seconds < 60) return seconds + "s";
        long m = seconds / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return String.format(Locale.ROOT, "%dh %02dm", h, m % 60);
        return String.format(Locale.ROOT, "%dd %02dh", h / 24, h % 24);
    }

    /** Escapes Discord markdown in user-controlled text shown outside code blocks. */
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("\\*_`~|".indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * {@code s} shown exactly as typed by Discord and by ZenithProxy's terminal and in-game output, which both read
     * Discord markdown: as inline code. Text inline code cannot carry (a backtick, a backslash, a line break) is
     * escaped instead. For paths: "/maps/my_cool_art.zip" would otherwise lose "_cool_" to italics.
     */
    public static String literal(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.indexOf('`') < 0 && s.indexOf('\\') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0
            && !Character.isWhitespace(s.charAt(0))) {
            return "`" + s + "`";
        }
        return escape(s);
    }

    /** Text safe inside a ``` code block (no fence breaking). */
    public static String inCode(String s) {
        return s == null ? "" : s.replace("```", "'''").replace('`', '\'');
    }

    /** Wraps lines in a code block. */
    public static String codeBlock(List<String> lines) {
        return "```\n" + inCode(String.join("\n", lines)) + "\n```";
    }

    /** Truncates to {@code max} chars, ending with "…" when cut. */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        if (max <= 1) return s.substring(0, Math.max(0, max));
        return s.substring(0, max - 1) + "…";
    }

    /** Left-aligns {@code s} in a column of {@code width} (truncating with "~"). */
    public static String pad(String s, int width) {
        if (s == null) s = "";
        if (s.length() > width) return s.substring(0, Math.max(0, width - 1)) + "~";
        return s + " ".repeat(width - s.length());
    }

    /** Right-aligns {@code s} in a column of {@code width}. */
    public static String padLeft(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s;
        return " ".repeat(width - s.length()) + s;
    }

    /** "- a\n- b", stopping before {@code maxChars} with "… and N more". */
    public static String bullets(List<String> items, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            String line = (sb.length() > 0 ? "\n- " : "- ") + items.get(i);
            int after = items.size() - i - 1;
            int reserve = after == 0 ? 0 : ("\n… and " + after + " more").length();
            if (sb.length() + line.length() + reserve > maxChars) {
                if (sb.length() == 0) return truncate(line, maxChars);
                return sb.append("\n… and ").append(items.size() - i).append(" more").toString();
            }
            sb.append(line);
        }
        return sb.toString();
    }
}
