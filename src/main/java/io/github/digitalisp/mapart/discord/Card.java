package io.github.digitalisp.mapart.discord;

import io.github.digitalisp.mapart.command.Fmt;
import io.github.digitalisp.mapart.command.Reply;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * A Discord message as plain data: optional text content (used for alert mentions), one embed, and
 * an optional PNG shown as the embed image. Built without JDA so formatting is unit-testable;
 * {@link JdaMessages} converts it. The builder enforces Discord's embed limits.
 */
public record Card(String content, String title, String description, List<Field> fields, int color,
                   String footer, Attachment attachment) {
    public static final int INFO = 0x3498DB;
    public static final int SUCCESS = 0x2ECC71;
    public static final int WARN = 0xF1C40F;
    public static final int ERROR = 0xE74C3C;
    public static final int PROGRESS = 0x95A5A6;

    static final int TITLE_MAX = 256;
    static final int DESCRIPTION_MAX = 4096;
    static final int FIELD_NAME_MAX = 256;
    static final int FIELD_VALUE_MAX = 1024;
    static final int FIELDS_MAX = 25;
    static final int FOOTER_MAX = 2048;
    static final int CONTENT_MAX = 2000;
    static final int TOTAL_MAX = 6000;

    public record Field(String name, String value, boolean inline) { }

    public record Attachment(String fileName, byte[] data) { }

    public Card {
        fields = List.copyOf(fields);
    }

    /** Embed character count as Discord counts it (title, description, field names/values, footer). */
    public int length() {
        int n = len(title) + len(description) + len(footer);
        for (Field f : fields) n += len(f.name()) + len(f.value());
        return n;
    }

    public static Card of(Reply r) {
        return builder().title(r.title()).description(r.body()).color(colorOf(r.tone())).build();
    }

    /**
     * Same card with {@code f} applied to title, description, field names and values, and footer, re-checked
     * against the limits. Content (the alert mention) and the attachment are kept as they are.
     */
    public Card mapText(UnaryOperator<String> f) {
        Builder b = builder().content(content).title(map(f, title)).description(map(f, description)).color(color)
            .footer(map(f, footer));
        for (Field x : fields) b.field(map(f, x.name()), map(f, x.value()), x.inline());
        if (attachment != null) b.attachment(attachment.fileName(), attachment.data());
        return b.build();
    }

    private static String map(UnaryOperator<String> f, String s) {
        return s == null ? null : f.apply(s);
    }

    public static int colorOf(Reply.Tone tone) {
        return switch (tone) {
            case INFO -> INFO;
            case SUCCESS -> SUCCESS;
            case WARN -> WARN;
            case ERROR -> ERROR;
        };
    }

    public static Builder builder() {
        return new Builder();
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    public static final class Builder {
        private String content;
        private String title;
        private String description;
        private final List<Field> fields = new ArrayList<>();
        private int color = INFO;
        private String footer;
        private Attachment attachment;

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder field(String name, String value, boolean inline) {
            if (name != null && value != null && !value.isBlank()) fields.add(new Field(name, value, inline));
            return this;
        }

        public Builder color(int color) {
            this.color = color;
            return this;
        }

        public Builder footer(String footer) {
            this.footer = footer;
            return this;
        }

        public Builder attachment(String fileName, byte[] data) {
            this.attachment = data == null ? null : new Attachment(fileName, data);
            return this;
        }

        /** Truncates every part to Discord's limits, then trims the description to keep the total ≤ 6000. */
        public Card build() {
            String t = blankToNull(Fmt.truncate(title, TITLE_MAX));
            String d = blankToNull(Fmt.truncate(description, DESCRIPTION_MAX));
            String f = blankToNull(Fmt.truncate(footer, FOOTER_MAX));
            String c = blankToNull(Fmt.truncate(content, CONTENT_MAX));
            List<Field> fs = new ArrayList<>();
            for (Field x : fields) {
                if (fs.size() == FIELDS_MAX) break;
                fs.add(new Field(Fmt.truncate(x.name(), FIELD_NAME_MAX), Fmt.truncate(x.value(), FIELD_VALUE_MAX), x.inline()));
            }
            Card card = new Card(c, t, d, fs, color, f, attachment);
            int over = card.length() - TOTAL_MAX;
            if (over > 0 && d != null) {
                d = Fmt.truncate(d, Math.max(1, d.length() - over));
                card = new Card(c, t, d, fs, color, f, attachment);
            }
            while (card.length() > TOTAL_MAX && !fs.isEmpty()) {
                fs.remove(fs.size() - 1);
                card = new Card(c, t, d, fs, color, f, attachment);
            }
            return card;
        }

        private static String blankToNull(String s) {
            return s == null || s.isEmpty() ? null : s;
        }
    }
}
