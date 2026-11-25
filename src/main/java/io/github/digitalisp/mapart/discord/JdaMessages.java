package io.github.digitalisp.mapart.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;

/** {@link Card} → JDA message data. No network access. */
public final class JdaMessages {
    private JdaMessages() { }

    public static MessageEmbed embed(Card c) {
        EmbedBuilder b = new EmbedBuilder()
            .setTitle(c.title())
            .setDescription(c.description())
            .setColor(c.color())
            .setTimestamp(Instant.now());
        for (Card.Field f : c.fields()) b.addField(f.name(), f.value(), f.inline());
        if (c.footer() != null) b.setFooter(c.footer());
        if (c.attachment() != null && c.attachment().fileName().toLowerCase(Locale.ROOT).endsWith(".png"))
            b.setImage("attachment://" + c.attachment().fileName());
        if (b.isEmpty()) b.setDescription("(empty)");
        return b.build();
    }

    public static MessageCreateData create(Card c) {
        MessageCreateBuilder b = new MessageCreateBuilder()
            .setAllowedMentions(mentions(c.content()))
            .addEmbeds(embed(c));
        if (c.content() != null) b.setContent(c.content());
        if (c.attachment() != null) b.addFiles(FileUpload.fromData(c.attachment().data(), c.attachment().fileName()));
        return b.build();
    }

    /** Edits keep the original attachments; only content and embed are replaced. */
    public static MessageEditData edit(Card c) {
        return new MessageEditBuilder()
            .setAllowedMentions(mentions(c.content()))
            .setContent(c.content() == null ? "" : c.content())
            .setEmbeds(embed(c))
            .build();
    }

    /**
     * Users and roles may be pinged (alert mention). @everyone/@here only if the configured mention
     * itself contains them. Set per message: JDA's defaults are global and shared with ZenithProxy's bot.
     */
    static EnumSet<Message.MentionType> mentions(String content) {
        EnumSet<Message.MentionType> m = EnumSet.of(Message.MentionType.USER, Message.MentionType.ROLE);
        if (content != null && (content.contains("@everyone") || content.contains("@here"))) {
            m.add(Message.MentionType.EVERYONE);
            m.add(Message.MentionType.HERE);
        }
        return m;
    }
}
