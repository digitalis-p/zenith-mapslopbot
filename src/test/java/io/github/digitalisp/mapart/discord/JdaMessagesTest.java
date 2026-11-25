package io.github.digitalisp.mapart.discord;

import net.dv8tion.jda.api.entities.Message.MentionType;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/** Builds JDA message objects offline (no client, no network). */
class JdaMessagesTest {
    @Test
    void createIncludesEmbedFileAndContent() {
        Card card = Card.builder().content("<@&1>").title("Queued j0001: Pine").description("desc")
            .field("Tiles", "1x1", true).color(Card.SUCCESS).attachment("preview.png", new byte[]{1, 2, 3}).build();
        MessageCreateData data = JdaMessages.create(card);
        assertEquals("<@&1>", data.getContent());
        assertEquals(1, data.getEmbeds().size());
        MessageEmbed e = data.getEmbeds().get(0);
        assertEquals("Queued j0001: Pine", e.getTitle());
        assertEquals("desc", e.getDescription());
        assertEquals(Card.SUCCESS, e.getColorRaw());
        assertEquals("attachment://preview.png", e.getImage().getUrl());
        assertEquals("Tiles", e.getFields().get(0).getName());
        assertEquals(1, data.getFiles().size());
        assertEquals("preview.png", data.getFiles().get(0).getName());
    }

    @Test
    void editHasNoFiles() {
        Card card = Card.builder().title("Building").description("50%").build();
        assertEquals("Building", JdaMessages.edit(card).getEmbeds().get(0).getTitle());
        assertTrue(JdaMessages.edit(card).getFiles().isEmpty());
    }

    @Test
    void everyonePingOnlyWhenConfiguredMentionHasIt() {
        assertEquals(EnumSet.of(MentionType.USER, MentionType.ROLE), JdaMessages.mentions("<@&1>"));
        assertEquals(EnumSet.of(MentionType.USER, MentionType.ROLE), JdaMessages.mentions(null));
        assertTrue(JdaMessages.mentions("@here").contains(MentionType.HERE));
    }
}
