package io.github.digitalisp.mapart.discord;

import io.github.digitalisp.mapart.command.Fmt;
import io.github.digitalisp.mapart.command.Names;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Chooses which attachments of a Discord message are a map-art submission and enforces the size
 * limit before anything is downloaded. Pure; JDA attachments are mapped to {@link Candidate}s.
 */
public final class AttachmentFilter {
    private AttachmentFilter() { }

    /** Discord allows 10 attachments per message; more is never a valid submission. */
    public static final int MAX_FILES = 10;

    public record Candidate(int index, String fileName, long size) { }

    /**
     * @param accepted .nbt/.zip attachments to download (original indices kept)
     * @param ignored  names of other attachments (images etc.), reported as a warning
     * @param errors   reasons to reject without downloading (size limit, too many files)
     */
    public record Selection(List<Candidate> accepted, List<String> ignored, List<String> errors) {
        /** True if the message is a submission attempt (has .nbt/.zip files, even rejected ones). */
        public boolean isSubmission() {
            return !accepted.isEmpty() || !errors.isEmpty();
        }
    }

    /** Each accepted file and their total must be ≤ {@code maxBytes}. */
    public static Selection select(List<Candidate> all, long maxBytes) {
        List<Candidate> accepted = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        long total = 0;
        for (Candidate c : all) {
            if (!Names.isSubmissionFile(c.fileName())) {
                ignored.add(c.fileName());
                continue;
            }
            if (c.size() > maxBytes) {
                errors.add("`" + Fmt.inCode(c.fileName()) + "` is " + size(c.size()) + "; the limit is " + size(maxBytes) + ".");
                continue;
            }
            total += c.size();
            accepted.add(c);
        }
        if (accepted.size() > MAX_FILES) errors.add("Too many files (" + accepted.size() + "); put them in one .zip.");
        if (total > maxBytes && errors.isEmpty())
            errors.add("The files total " + size(total) + "; the limit is " + size(maxBytes) + ". Upload fewer at a time.");
        if (!errors.isEmpty()) accepted = List.of();
        return new Selection(accepted, ignored, errors);
    }

    /** Reads at most {@code maxBytes}; throws if the stream is longer (size reported by Discord was wrong). */
    public static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) throw new IOException("download exceeds the limit of " + size(maxBytes));
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 1536 → "1.5 KiB". */
    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }
}
