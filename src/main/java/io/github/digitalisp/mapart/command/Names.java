package io.github.digitalisp.mapart.command;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Job display names and submission file names. Display names end up in Discord embeds and in the
 * anvil name of every map tile, so they are reduced to letters, digits, spaces and a little
 * punctuation, and capped at {@link #MAX_NAME} characters.
 */
public final class Names {
    private Names() { }

    /**
     * Job names up to the anvil's limit (2b2t and vanilla: 50); a map's name is the job name plus its tile, and
     * {@code BuildEngine.fitName} shortens the job part, never the tile, when both do not fit (live 2026-10-16: the cap
     * of 32 cut "Galileo Probe over Jupiter and Io" to "... and I").
     */
    public static final int MAX_NAME = 50;
    private static final String PUNCT = "-_.,'!?&+#()";
    /** Discord mentions, channels, roles and custom emoji: <@123>, <#123>, <@&123>, <:x:123>, <a:x:123>, <t:123:R>. */
    private static final Pattern DISCORD_TOKENS = Pattern.compile("<(?:@[!&]?|#|a?:\\w*:|t:)[^>]*>");
    private static final Pattern URLS = Pattern.compile("(?i)\\b(?:https?|ftp)://\\S+");

    /** True for a file the submission pipeline accepts: {@code .nbt} or {@code .zip}, any case. */
    public static boolean isSubmissionFile(String fileName) {
        if (fileName == null) return false;
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".nbt") || n.endsWith(".zip");
    }

    /**
     * Sanitises {@code requested} (e.g. the text of a Discord upload message); falls back to the
     * sanitised {@code fallback} (the parsed base name), then to "map".
     */
    public static String displayName(String requested, String fallback) {
        String n = sanitize(requested);
        if (n.isEmpty()) n = sanitize(fallback);
        return n.isEmpty() ? "map" : n;
    }

    /**
     * Brackets and quotes of any script, besides {@link #PUNCT}: live 2026-10-15 the owner's "Ａｂａｄｄｏｎ 〘  Last Art  v2〙"
     * lost its 〘〙 and its double spaces. Never Discord markup, mentions or Minecraft's formatting sign.
     */
    private static final String NEVER = "*~`|\\<>@:;§";

    private static boolean keep(int cp) {
        if (Character.isLetterOrDigit(cp) || PUNCT.indexOf(cp) >= 0) return true;
        if (NEVER.indexOf(cp) >= 0) return false;
        int t = Character.getType(cp);
        return t == Character.LETTER_NUMBER || t == Character.OTHER_NUMBER // Ⅱ, ②, ²
            || t == Character.START_PUNCTUATION || t == Character.END_PUNCTUATION
            || t == Character.INITIAL_QUOTE_PUNCTUATION || t == Character.FINAL_QUOTE_PUNCTUATION
            || t == Character.DASH_PUNCTUATION || t == Character.OTHER_PUNCTUATION;
    }

    /**
     * First line only, mentions/URLs removed, other disallowed characters dropped (one space where they stood), the
     * writer's own spaces kept as written, ≤ 32 chars.
     */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        int nl = s.indexOf('\n');
        if (nl >= 0) s = s.substring(0, nl);
        s = DISCORD_TOKENS.matcher(s).replaceAll(" ");
        s = URLS.matcher(s).replaceAll(" ");
        StringBuilder sb = new StringBuilder(s.length());
        int spaces = 0; // the spaces the writer put since the last kept character (kept as written)
        boolean gap = false; // a dropped character stands for one space
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ' ') {
                spaces++;
            } else if (keep(cp)) {
                if (sb.length() > 0) sb.append(" ".repeat(Math.max(spaces, gap ? 1 : 0)));
                sb.appendCodePoint(cp);
                spaces = 0;
                gap = false;
            } else {
                gap = true;
            }
        }
        String out = sb.toString();
        if (out.codePointCount(0, out.length()) > MAX_NAME) {
            out = out.substring(0, out.offsetByCodePoints(0, MAX_NAME)).strip();
        }
        return out;
    }

    /** Lead-in words of an upload message that mean "submit this" rather than being part of the name. */
    static final Set<String> SUBMIT_WORDS = Set.of("queue", "q", "submit", "add", "build");
    /** A command-like token such as {@code !queue} or {@code !map2}. */
    private static final Pattern BANG_WORD = Pattern.compile("![\\p{L}\\p{N}_-]+");

    /**
     * Name text of a Discord upload message, with any command-like lead-in removed, in this order: a
     * leading command {@code prefix} (case-insensitive, followed by whitespace or the end), then any leading
     * {@code !word} tokens, then one leading word that means "submit" ({@link #SUBMIT_WORDS}, any case), but
     * only when a prefix or {@code !word} came before it, so titles like "Build the Wall" stay whole.
     * Only the start is touched: "Wow! Sunset" stays. Empty when the message carries no name; the caller
     * then falls back to the file-derived name ({@link #displayName}).
     * <p>{@code "!map queue verity"} → "verity", {@code "!queue flarpity"} → "flarpity", "q verity" stays.
     */
    public static String nameFromMessage(String content, String prefix) {
        if (content == null) return "";
        String s = content.strip();
        String p = prefix == null ? "" : prefix.strip();
        boolean command = false; // a prefix or !word came first, so a following submit word is a command too
        if (!p.isEmpty() && s.regionMatches(true, 0, p, 0, p.length())
            && (s.length() == p.length() || Character.isWhitespace(s.charAt(p.length())))) {
            s = s.substring(p.length()).strip();
            command = true;
        }
        for (String t = firstToken(s); BANG_WORD.matcher(t).matches(); t = firstToken(s)) {
            s = s.substring(t.length()).strip();
            command = true;
        }
        String first = firstToken(s);
        if (command && SUBMIT_WORDS.contains(first.toLowerCase(Locale.ROOT))) s = s.substring(first.length()).strip();
        return s;
    }

    /** {@code s} up to its first whitespace ({@code s} is already stripped). */
    private static String firstToken(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return s.substring(0, i);
        return s;
    }

    /** Safe attachment file name for uploads: [A-Za-z0-9._-], ending in {@code ext}. */
    public static String fileName(String raw, String ext) {
        String s = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
        if (s.isEmpty() || s.chars().allMatch(c -> c == '_' || c == '.')) s = "image";
        if (s.toLowerCase(Locale.ROOT).endsWith(ext)) s = s.substring(0, s.length() - ext.length());
        if (s.length() > 64) s = s.substring(0, 64);
        if (s.isEmpty()) s = "image";
        return s + ext;
    }
}
