package io.github.digitalisp.mapart.bot.impl;

import com.viaversion.nbt.io.MNBTIO;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.StringTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SignText}: a sign block entity's lines, both sides, as the chunk cache holds them. */
class SignTextTest {
    private static CompoundTag side(String... lines) {
        CompoundTag t = new CompoundTag();
        ListTag<StringTag> messages = new ListTag<>(StringTag.class);
        for (String l : lines) messages.add(new StringTag(l));
        t.put("messages", messages);
        t.put("color", new StringTag("black"));
        return t;
    }

    @Test
    void bothSidesAreRead() {
        CompoundTag sign = new CompoundTag();
        sign.put("front_text", side("\"\"", "{\"text\":\"Rockets\"}", "\"\"", "\"\""));
        sign.put("back_text", side("\"elytra\"", "\"\"", "\"\"", "\"\""));
        String text = SignText.of(MNBTIO.write(sign, false));
        assertTrue(text.contains("Rockets"), text);
        assertTrue(text.contains("elytra"), text);
        assertEquals(text, SignText.of(sign));
    }

    @Test
    void theOldTextKeysAreReadToo() {
        CompoundTag sign = new CompoundTag();
        sign.put("Text1", new StringTag("{\"text\":\"rocket chest\"}"));
        assertTrue(SignText.of(sign).contains("rocket"));
    }

    @Test
    void nothingReadableIsEmpty() {
        assertEquals("", SignText.of((com.viaversion.nbt.mini.MNBT) null));
        assertEquals("", SignText.of(new CompoundTag()));
        assertEquals("", SignText.of(new StringTag("x")));
    }
}
