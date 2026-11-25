package io.github.digitalisp.mapart.bot.impl;

import com.viaversion.nbt.io.MNBTIO;
import com.viaversion.nbt.mini.MNBT;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The text of a sign's block entity data ({@code build.elytraLegs}: the rocket chest's sign). 1.21.4 sends {@code
 * front_text} and {@code back_text}, each with four {@code messages} (JSON text
 * components as strings; later versions send them as compound tags); before 1.20 {@code Text1} to {@code Text4}.
 * Every string under those keys counts, so a JSON component's {@code "text"} value is in the result with its
 * quoting. Unreadable data gives "".
 */
final class SignText {
    private SignText() { }

    private static final String[] KEYS = {"front_text", "back_text", "Text1", "Text2", "Text3", "Text4"};

    /** The sign's lines, front then back, joined by newlines; "" when there are none or the data does not read. */
    static String of(@Nullable MNBT nbt) {
        if (nbt == null || nbt.isEmpty() || nbt.getData() == null) return "";
        try {
            return of(MNBTIO.read(nbt));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** {@link #of(MNBT)} for the read tag. */
    static String of(@Nullable Tag tag) {
        if (!(tag instanceof CompoundTag c)) return "";
        List<String> out = new ArrayList<>();
        for (String k : KEYS) strings(c.get(k), out, 0);
        return String.join("\n", out);
    }

    /** Every string value in {@code t} (lists and compounds, at most 8 levels deep) into {@code out}. */
    private static void strings(@Nullable Tag t, List<String> out, int depth) {
        if (t == null || depth > 8) return;
        if (t instanceof StringTag s) {
            if (s.getValue() != null && !s.getValue().isEmpty()) out.add(s.getValue());
        } else if (t instanceof ListTag<?> l) {
            for (Tag x : l) strings(x, out, depth + 1);
        } else if (t instanceof CompoundTag c) {
            for (Tag x : c.values()) strings(x, out, depth + 1);
        }
    }
}
