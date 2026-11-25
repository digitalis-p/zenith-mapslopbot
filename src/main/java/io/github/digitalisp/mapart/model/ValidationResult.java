package io.github.digitalisp.mapart.model;

import java.util.List;
import java.util.Map;

/**
 * Outcome of validating a {@link ParsedSubmission} against the block registry and the site.
 *
 * @param errors        fatal problems; a job with errors is rejected
 * @param warnings      non-fatal notes shown to the submitter
 * @param materials     item id → total count needed across all tiles (what must come out of storage)
 * @param platformIds   ids of configured platforms that can host every block of this job; a result with
 *                      no errors always has at least one (a job needing two platforms is rejected)
 * CONTRACT (lead-owned).
 */
public record ValidationResult(List<String> errors, List<String> warnings,
                               Map<String, Integer> materials, List<String> platformIds) {
    public boolean ok() {
        return errors.isEmpty();
    }
}
