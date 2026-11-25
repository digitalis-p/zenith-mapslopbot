package io.github.digitalisp.mapart.command;

import java.util.function.UnaryOperator;

/**
 * Transport-neutral command result: rendered as a Discord embed by the intake bot and as a ZenithProxy
 * {@code Embed} by {@link MapArtCommand}. {@code body} may contain Discord markdown (code blocks).
 */
public record Reply(Tone tone, String title, String body) {
    public enum Tone { INFO, SUCCESS, WARN, ERROR }

    public Reply {
        if (title == null) title = "";
        if (body == null) body = "";
    }

    public static Reply info(String title, String body) {
        return new Reply(Tone.INFO, title, body);
    }

    public static Reply ok(String title, String body) {
        return new Reply(Tone.SUCCESS, title, body);
    }

    public static Reply warn(String title, String body) {
        return new Reply(Tone.WARN, title, body);
    }

    public static Reply error(String title, String body) {
        return new Reply(Tone.ERROR, title, body);
    }

    public boolean isError() {
        return tone == Tone.ERROR;
    }

    /** Same reply with {@code f} applied to title and body. */
    public Reply mapText(UnaryOperator<String> f) {
        return new Reply(tone, f.apply(title), f.apply(body));
    }

    /** Same reply with {@code extra} appended to the body on a new line. */
    public Reply append(String extra) {
        if (extra == null || extra.isEmpty()) return this;
        return new Reply(tone, title, body.isEmpty() ? extra : body + "\n" + extra);
    }
}
