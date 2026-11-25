package io.github.digitalisp.mapart.discord;

import io.github.digitalisp.mapart.command.Fmt;
import io.github.digitalisp.mapart.command.QueueOps;
import io.github.digitalisp.mapart.command.QueueOps.MaterialRow;
import io.github.digitalisp.mapart.command.SubmissionPipeline;
import io.github.digitalisp.mapart.command.SubmissionPipeline.Accepted;
import io.github.digitalisp.mapart.command.SubmissionPipeline.Rejected;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.plan.SameColourSwap;
import io.github.digitalisp.mapart.stock.Unstocked;

import java.util.List;

/**
 * Discord replies for submissions (accepted with preview, facts and the material list, or rejected with reasons). Pure.
 * Laid out for a phone: facts and materials one per line in the description, no inline fields, no tables.
 */
public final class SubmissionCards {
    private SubmissionCards() { }

    static final int MATERIALS_MAX = 3000;
    /** Cap for the "No chest at this base for: …" line. */
    static final int NO_CHEST_MAX = 600;
    /** Cap for the "Swapped for identical colours: …" line (with the list, still within the 4096-char description). */
    static final int SWAPS_MAX = 400;

    /** The reply to a submission's outcome. */
    public static Card of(SubmissionPipeline.Outcome outcome, List<String> extraWarnings) {
        return switch (outcome) {
            case Accepted a -> accepted(a, extraWarnings);
            case Rejected r -> rejected(r, extraWarnings);
        };
    }

    public static Card accepted(Accepted a, List<String> extraWarnings) {
        Job j = a.job();
        List<String> noChest = a.materials().stream().filter(MaterialRow::unstocked).map(MaterialRow::item).sorted().toList();
        long shorts = a.materials().stream().filter(r -> r.isShort() && !r.unstocked()).count();
        StringBuilder d = new StringBuilder();
        d.append("**").append(Fmt.escape(j.name)).append("** is #").append(a.queuePosition()).append(" in the queue.\n");
        String swapped = SameColourSwap.line(j.substitutions, SWAPS_MAX);
        if (swapped != null) d.append(Fmt.escape(swapped)).append('\n');
        if (!a.stockScanned()) {
            d.append("Stock has not been scanned yet, so every material shows as short.\n");
        } else {
            if (!noChest.isEmpty())
                d.append(Unstocked.MARK).append(' ').append(Fmt.escape(Unstocked.line(noChest, NO_CHEST_MAX))).append('\n');
            if (shorts > 0) d.append(shorts).append(noChest.isEmpty() ? "" : " other")
                .append(" material(s) are short (marked !). The job waits for a restock when its turn comes.\n");
            else if (noChest.isEmpty()) d.append("All materials are in stock.\n");
        }
        facts(d, List.of(
            "**Job:** `" + Fmt.inCode(j.id) + "`",
            "**Tiles:** " + (j.isSuppression()
                ? "color suppression: 1 map on two layers (preview: bottom layer, top layer, map)"
                : j.tilesX + "x" + j.tilesZ + " (" + (j.tilesX * j.tilesZ) + " maps)"),
            "**Blocks:** " + Fmt.count(a.totalBlocks()),
            "**Queue position:** #" + a.queuePosition(),
            "**Platforms:** " + platforms(j.platformIds),
            "**Submitted by:** " + Fmt.escape(j.submitterName == null ? "?" : j.submitterName)));
        // ≤ 3000 chars, and the description under Discord's 4096, keeps the whole embed under its 6000-char total
        d.append(QueueOps.formatMaterials(a.materials(), materialsMax(d)));

        Card.Builder b = Card.builder()
            .title("Queued " + j.id + ": " + Fmt.escape(j.name))
            .description(d.toString())
            .color(shorts > 0 || !noChest.isEmpty() || !a.stockScanned() ? Card.WARN : Card.SUCCESS);
        List<String> warnings = merge(a.warnings(), extraWarnings);
        if (!warnings.isEmpty()) b.field("Warnings", Fmt.bullets(warnings, Card.FIELD_VALUE_MAX), false);
        if (a.previewPng() != null) b.attachment(SubmissionPipeline.PREVIEW_FILE, a.previewPng());
        return b.build();
    }

    public static Card rejected(Rejected r, List<String> extraWarnings) {
        Card.Builder b = Card.builder()
            .title("Submission rejected")
            .description(Fmt.bullets(r.reasons().isEmpty() ? List.of("Unknown error.") : r.reasons(), 3900))
            .color(Card.ERROR);
        List<String> warnings = merge(r.warnings(), extraWarnings);
        if (!warnings.isEmpty()) b.field("Warnings", Fmt.bullets(warnings, Card.FIELD_VALUE_MAX), false);
        return b.build();
    }

    /** Rejection before parsing (size limit, download failure). */
    public static Card rejected(List<String> reasons) {
        return rejected(new Rejected(reasons, List.of()), List.of());
    }

    /** Cap for the platforms line. */
    static final int PLATFORMS_MAX = 300;

    /**
     * The card's facts (job, tiles, blocks, ...) one per line in the description, a blank line around them: inline
     * fields stack in narrow columns on Discord's phone app (the owner, 2026-10-28: every reply readable on mobile).
     */
    private static void facts(StringBuilder d, List<String> lines) {
        d.append('\n').append(String.join("\n", lines)).append("\n\n");
    }

    private static String platforms(List<String> ids) {
        return ids == null || ids.isEmpty() ? "none" : Fmt.escape(Fmt.truncate(String.join(", ", ids), PLATFORMS_MAX));
    }

    /** What the material list may take: at most {@link #MATERIALS_MAX}, and what is left of the description. */
    static int materialsMax(StringBuilder d) {
        return Math.max(200, Math.min(MATERIALS_MAX, Card.DESCRIPTION_MAX - d.length()));
    }

    private static List<String> merge(List<String> a, List<String> b) {
        if (b == null || b.isEmpty()) return a == null ? List.of() : a;
        if (a == null || a.isEmpty()) return b;
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
