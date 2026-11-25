package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.NamedFile;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.ValidationResult;

import java.util.List;

/**
 * Intake pipeline used by Discord and terminal submission: parse files → flat tiles → validate.
 * Pure (no Zenith globals); safe to call from any thread. CONTRACT (lead-owned).
 */
public interface SubmissionService {
    /**
     * Parses .nbt files and/or .zip archives of .nbt files. Accepts MapArtCraft "1x1 split" names
     * ({@code <base>_<x>_<y>.nbt}), a single joined NxM export, or a single 1x1 export.
     *
     * @throws SubmissionException with a user-readable message on malformed input or non-flat art
     */
    ParsedSubmission parse(List<NamedFile> files) throws SubmissionException;

    /**
     * As {@link #parse(List)}; when {@code site} has an enabled COLOR_SUPPRESSION platform, a single PNG-to-NBT
     * "Suppress (2-Layer)" CrubTech export is read as a colour-suppression map ({@link ParsedSubmission#suppression()},
     * docs/COLOR_SUPPRESSION.md) instead of being refused.
     */
    default ParsedSubmission parse(List<NamedFile> files, MapArtConfig.SiteCfg site) throws SubmissionException {
        return parse(files);
    }

    /**
     * An upload that is several jobs: two or more PNG-to-NBT colour-suppression files (one picture cut into maps,
     * "&lt;name&gt;-suppress_crubtech-&lt;a&gt;_&lt;b&gt;.nbt", loose or in a zip) are one colour-suppression map each, since such a
     * map is built, ridden and stored on its own (docs/COLOR_SUPPRESSION.md). Null when the
     * upload is one job: {@link #parse(List, MapArtConfig.SiteCfg)} reads or refuses it as before.
     *
     * @throws SubmissionException when only some of the files are colour-suppression maps
     */
    default Split split(List<NamedFile> files, MapArtConfig.SiteCfg site) throws SubmissionException {
        return null;
    }

    /**
     * @param base  the upload's own name (the zip's, else what the file names share); "" when the parts are named by
     *              their files
     * @param parts one map each, in queue order
     */
    record Split(String base, List<Part> parts) { }

    /**
     * @param label what tells this map from the others of the upload in its job name: "1,1" for "...-1_1.nbt", else
     *              the file's own name
     * @param file  the map's file, for {@link #parse(List, MapArtConfig.SiteCfg)}
     */
    record Part(String label, NamedFile file) { }

    /** Registry, placement and per-platform clearability checks plus the material list. */
    ValidationResult validate(ParsedSubmission submission, BlockCatalog catalog, MapArtConfig.SiteCfg site);

    class SubmissionException extends Exception {
        public SubmissionException(String message) {
            super(message);
        }

        public SubmissionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
