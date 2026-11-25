package io.github.digitalisp.mapart.engine;

import com.google.gson.Gson;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.SuppressionArt;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.store.JobStore;
import io.github.digitalisp.mapart.store.TileCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The order of a colour-suppression job's passes ({@code platform.topLayerFirst}, docs/COLOR_SUPPRESSION.md). A job is stored
 * with the order of its upload: pass i is tile (i, 0) and {@code job.suppression.get(i)}. A queued job that has not
 * started any pass takes the platform's current order when it is first selected: its stored passes are put in that
 * order (the layer that goes first, each layer's passes in the order they had). A job with a pass started keeps its own.
 *
 * <p>The tiles are files of their own, so the change is journalled: the passes in their new order go into one blob
 * ({@link #blobName}, written atomically) before the first tile is replaced, and the blob goes once the job is saved
 * with the new order. A restart in between finds the blob at the job's next selection and finishes the change from
 * it, so a job is never left with its tiles in one order and its pass list in the other.
 */
final class PassOrder {
    private PassOrder() { }

    private static final Gson GSON = new Gson();

    /** The journal: the passes in their new order. */
    static final class Journal {
        List<Job.SuppressionPass> suppression = new ArrayList<>();
        List<TileCodec.Stored> tiles = new ArrayList<>();
    }

    /** What the job takes over once the tiles are written ({@link #apply}). */
    record Change(List<Job.SuppressionPass> suppression, int[] totals) { }

    static String blobName(String jobId) {
        return "cs-order-" + jobId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_") + ".json";
    }

    /** No pass of the job was begun: every tile is PENDING with nothing placed. */
    static boolean unstarted(Job j) {
        if (j == null || !j.isSuppression() || j.tiles.size() != j.suppression.size()) return false;
        for (Job.TileState t : j.tiles)
            if (t == null || t.status != TileStatus.PENDING || t.placed > 0) return false;
        return true;
    }

    /**
     * Where each pass comes from when the layer {@code topFirst} names goes first: {@code perm[i]} is the old index of
     * the new pass i, the passes of a layer in the order they had. Null when the order is that already (also a job of
     * one layer).
     */
    static int @Nullable [] permutation(List<Job.SuppressionPass> passes, boolean topFirst) {
        int first = topFirst ? SuppressionArt.TOP : SuppressionArt.BOTTOM;
        int[] perm = new int[passes.size()];
        int n = 0;
        for (int i = 0; i < passes.size(); i++) if (passes.get(i).layer == first) perm[n++] = i;
        for (int i = 0; i < passes.size(); i++) if (passes.get(i).layer != first) perm[n++] = i;
        for (int i = 0; i < perm.length; i++) if (perm[i] != i) return perm;
        return null;
    }

    /**
     * Any thread (disk): puts the stored passes of the unstarted job {@code j} in the order {@code topFirst} asks for, or
     * finishes such a change a restart cut short. Null when there is nothing to change, or the store cannot replace
     * tiles (the job keeps its order). The caller then calls {@link #apply} on the thread that owns the job.
     */
    static @Nullable Change write(JobStore store, Job j, boolean topFirst) throws IOException {
        String name = blobName(j.id);
        int n = j.suppression.size();
        Journal jn = null;
        byte[] blob = store.loadBlob(name);
        if (blob != null) {
            try {
                jn = GSON.fromJson(new String(blob, StandardCharsets.UTF_8), Journal.class);
            } catch (RuntimeException e) {
                jn = null;
            }
            if (jn == null || jn.suppression == null || jn.tiles == null || jn.suppression.size() != n || jn.tiles.size() != n)
                throw new IOException("the pass order journal " + name + " does not fit job " + j.id + " (" + n
                    + " passes): its tiles may be in either order. Cancel the job and upload it again");
        }
        if (jn == null) {
            int[] perm = permutation(j.suppression, topFirst);
            if (perm == null) return null;
            jn = new Journal();
            for (int i = 0; i < n; i++) {
                Job.SuppressionPass p = j.suppression.get(perm[i]);
                jn.suppression.add(new Job.SuppressionPass(p.layer, p.level, p.noobline));
                jn.tiles.add(TileCodec.encode(store.loadTile(j.id, new TileCoord(perm[i], 0))));
            }
            store.saveBlob(name, GSON.toJson(jn).getBytes(StandardCharsets.UTF_8));
        }
        int[] totals = new int[n];
        for (int i = 0; i < n; i++) {
            TileArt art = TileCodec.decode(jn.tiles.get(i));
            totals[i] = art.nonEmptyCount();
            try {
                store.saveTile(j.id, new TileCoord(i, 0), art);
            } catch (UnsupportedOperationException e) {
                if (i > 0) throw new IOException("the job store stopped replacing tiles at pass " + i, e);
                store.saveBlob(name, null); // nothing was replaced: the job keeps the order it has
                return null;
            }
        }
        return new Change(jn.suppression, totals);
    }

    /** The thread that owns the job: the pass list and the tiles' block counts in the new order. The caller saves it. */
    static void apply(Job j, Change c) {
        j.suppression = new ArrayList<>(c.suppression());
        for (int i = 0; i < j.tiles.size() && i < c.totals().length; i++) j.tiles.get(i).total = c.totals()[i];
    }
}
