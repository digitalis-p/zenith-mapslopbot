package io.github.digitalisp.mapart.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A prefix command such as {@code !map move j0007 2}: lower-cased sub-command name, whitespace
 * separated arguments, and the raw text after the name (for free-text arguments like a pause reason).
 */
public record CommandLine(String name, List<String> args, String rest) {
    public CommandLine {
        args = List.copyOf(args);
    }

    /**
     * Parses {@code content} if it starts with {@code prefix} (case-insensitive) followed by whitespace
     * or the end. {@code "!map"} alone parses to the name "help".
     */
    public static Optional<CommandLine> parse(String content, String prefix) {
        if (content == null || prefix == null || prefix.isBlank()) return Optional.empty();
        String s = content.strip();
        if (!s.regionMatches(true, 0, prefix, 0, prefix.length())) return Optional.empty();
        String after = s.substring(prefix.length());
        if (!after.isEmpty() && !Character.isWhitespace(after.charAt(0))) return Optional.empty();
        after = after.strip();
        if (after.isEmpty()) return Optional.of(new CommandLine("help", List.of(), ""));
        int sp = indexOfWhitespace(after);
        String name = (sp < 0 ? after : after.substring(0, sp)).toLowerCase(Locale.ROOT);
        String rest = sp < 0 ? "" : after.substring(sp).strip();
        List<String> args = new ArrayList<>();
        for (String a : rest.split("\\s+")) if (!a.isEmpty()) args.add(a);
        return Optional.of(new CommandLine(name, args, rest));
    }

    public Optional<String> arg(int i) {
        return i < args.size() ? Optional.of(args.get(i)) : Optional.empty();
    }

    /** Integer argument, empty when missing or not a number. */
    public OptionalInt intArg(int i) {
        if (i >= args.size()) return OptionalInt.empty();
        try {
            return OptionalInt.of(Integer.parseInt(args.get(i).replace("#", "")));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return i;
        return -1;
    }
}
