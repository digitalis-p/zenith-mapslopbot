package io.github.digitalisp.mapart.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.model.ValidationResult;
import io.github.digitalisp.mapart.stock.StockIndex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@link JobStore} on plain JSON files (layout in the interface doc). Every method is synchronized; state is
 * loaded lazily on first use. Writes go to a temp file in the same directory, are fsynced, then moved over
 * the target atomically (plain replace where the file system cannot). A file that fails to parse is moved
 * aside as {@code <name>.corrupt} and reported; the store continues without it. Job ids ({@code j0001}, ...)
 * never repeat: the next sequence number is persisted in queue.json and is also kept above every job
 * directory found on disk.
 */
public final class FileJobStore implements JobStore {
    private static final Logger LOG = Logger.getLogger(FileJobStore.class.getName());
    private static final Pattern ID = Pattern.compile("j\\d{1,9}");
    private static final int MAX_NAME = io.github.digitalisp.mapart.command.Names.MAX_NAME;

    /** queue.json body. */
    static final class QueueFile {
        int nextSeq = 1;
        List<String> jobs = new ArrayList<>();
    }

    private final Path dir;
    private final Gson gson;
    private final Clock clock;
    private final Consumer<String> log;

    private boolean loaded;
    private int nextSeq = 1;
    private final List<String> order = new ArrayList<>();
    private final Map<String, Job> jobs = new HashMap<>();
    private EngineCheckpoint checkpoint;
    private StockIndex stock;

    public FileJobStore(Path dataDir, Gson gson, Clock clock) {
        this(dataDir, gson, clock, null);
    }

    /** @param log receives problems (corrupt files, failed writes); default java.util.logging */
    public FileJobStore(Path dataDir, Gson gson, Clock clock, Consumer<String> log) {
        this.dir = dataDir;
        this.gson = gson != null ? gson : new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.log = log != null ? log : LOG::warning;
    }

    // ---------------------------------------------------------------- jobs

    @Override
    public synchronized Job create(ParsedSubmission submission, ValidationResult validation, String displayName,
                                   String submitterId, String submitterName) throws IOException {
        ensureLoaded();
        String id;
        do {
            id = formatId(nextSeq++);
        } while (Files.exists(jobDir(id)));

        Job j = new Job();
        j.id = id;
        String name = sanitizeName(displayName);
        j.name = name.isEmpty() ? sanitizeName(submission.baseName()) : name;
        if (j.name.isEmpty()) j.name = id;
        j.submitterId = submitterId;
        j.submitterName = submitterName;
        j.createdAt = clock.millis();
        j.tilesX = submission.tilesX();
        j.tilesZ = submission.tilesZ();
        j.status = JobStatus.QUEUED;
        if (validation != null) {
            j.materials = new LinkedHashMap<>(validation.materials());
            j.platformIds = new ArrayList<>(validation.platformIds());
        }
        for (int z = 0; z < submission.tilesZ(); z++)
            for (int x = 0; x < submission.tilesX(); x++) {
                Job.TileState t = new Job.TileState();
                t.x = x;
                t.z = z;
                TileArt art = submission.tiles().get(new TileCoord(x, z));
                t.total = art == null ? 0 : art.nonEmptyCount();
                j.tiles.add(t);
            }

        Path jd = jobDir(id);
        try {
            Files.createDirectories(jd);
            for (var e : submission.tiles().entrySet())
                writeJson(tileFile(id, e.getKey()), TileCodec.encode(e.getValue()));
            writeJson(jd.resolve("job.json"), j);
            order.add(id);
            jobs.put(id, j);
            writeQueue();
        } catch (IOException | RuntimeException e) {
            order.remove(id);
            jobs.remove(id);
            deleteTree(jd);
            try {
                writeQueue();
            } catch (IOException ignored) {
                // the id counter is also recovered from job directories
            }
            throw e;
        }
        return j;
    }

    @Override
    public synchronized List<Job> list() {
        ensureLoaded();
        List<Job> l = new ArrayList<>(order.size());
        for (String id : order) l.add(jobs.get(id));
        return l;
    }

    @Override
    public synchronized Optional<Job> get(String id) {
        ensureLoaded();
        return Optional.ofNullable(id == null ? null : jobs.get(id));
    }

    /** Persists {@code job}; an unknown id is registered at the end of the queue. */
    @Override
    public synchronized void save(Job job) {
        ensureLoaded();
        if (job.id == null || !ID.matcher(job.id).matches()) throw new IllegalArgumentException("bad job id " + job.id);
        try {
            writeJson(jobDir(job.id).resolve("job.json"), job);
            if (jobs.put(job.id, job) == null) {
                order.add(job.id);
                nextSeq = Math.max(nextSeq, seq(job.id) + 1);
                writeQueue();
            }
        } catch (IOException e) {
            log.accept("cannot save job " + job.id + ": " + e);
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized TileArt loadTile(String jobId, TileCoord coord) throws IOException {
        if (jobId == null || !ID.matcher(jobId).matches()) throw new NoSuchFileException("job " + jobId);
        Path f = tileFile(jobId, coord);
        if (!Files.exists(f)) throw new NoSuchFileException(f.toString());
        TileCodec.Stored s;
        try {
            s = gson.fromJson(Files.readString(f, StandardCharsets.UTF_8), TileCodec.Stored.class);
        } catch (RuntimeException e) {
            throw new IOException("corrupt tile file " + f + ": " + e.getMessage(), e);
        }
        return TileCodec.decode(s);
    }

    /** Atomic like every write; the job and its tile file must exist. */
    @Override
    public synchronized void saveTile(String jobId, TileCoord coord, TileArt art) throws IOException {
        ensureLoaded();
        if (jobId == null || !jobs.containsKey(jobId)) throw new NoSuchFileException("job " + jobId);
        if (art == null) throw new IllegalArgumentException("no art for tile " + coord);
        Path f = tileFile(jobId, coord);
        if (!Files.exists(f)) throw new NoSuchFileException(f.toString());
        writeJson(f, TileCodec.encode(art));
    }

    @Override
    public synchronized boolean cancel(String id) {
        ensureLoaded();
        Job j = id == null ? null : jobs.get(id);
        if (j == null || j.status.terminal()) return false;
        j.status = JobStatus.CANCELLED;
        save(j);
        return true;
    }

    /** Index is clamped to the valid range; terminal jobs keep their places in the order. */
    @Override
    public synchronized boolean move(String id, int index) {
        ensureLoaded();
        Job j = id == null ? null : jobs.get(id);
        if (j == null || j.status.terminal()) return false;
        List<String> open = new ArrayList<>();
        for (String o : order) if (!jobs.get(o).status.terminal()) open.add(o);
        open.remove(id);
        open.add(Math.max(0, Math.min(open.size(), index)), id);
        Iterator<String> it = open.iterator();
        for (int k = 0; k < order.size(); k++)
            if (!jobs.get(order.get(k)).status.terminal()) order.set(k, it.next());
        try {
            writeQueue();
        } catch (IOException e) {
            log.accept("cannot save queue order: " + e);
            throw new UncheckedIOException(e);
        }
        return true;
    }

    @Override
    public synchronized Optional<Job> next() {
        ensureLoaded();
        for (JobStatus s : new JobStatus[] {JobStatus.ACTIVE, JobStatus.QUEUED, JobStatus.WAITING_MATERIALS})
            for (String id : order) {
                Job j = jobs.get(id);
                if (j.status == s) return Optional.of(j);
            }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- checkpoint, stock

    /** Returns a fresh copy of the last saved checkpoint (an idle one if none). */
    @Override
    public synchronized EngineCheckpoint loadCheckpoint() {
        if (checkpoint == null) {
            checkpoint = readJson(dir.resolve("checkpoint.json"), EngineCheckpoint.class);
            if (checkpoint == null) checkpoint = new EngineCheckpoint();
        }
        return gson.fromJson(gson.toJson(checkpoint), EngineCheckpoint.class);
    }

    @Override
    public synchronized void saveCheckpoint(EngineCheckpoint cp) {
        EngineCheckpoint copy = gson.fromJson(gson.toJson(cp), EngineCheckpoint.class);
        try {
            writeJson(dir.resolve("checkpoint.json"), copy);
        } catch (IOException e) {
            log.accept("cannot save checkpoint: " + e);
            throw new UncheckedIOException(e);
        }
        checkpoint = copy;
    }

    @Override
    public synchronized byte[] loadBlob(String name) throws IOException {
        Path f = blob(name);
        return java.nio.file.Files.exists(f) ? java.nio.file.Files.readAllBytes(f) : null;
    }

    @Override
    public synchronized void saveBlob(String name, byte[] data) throws IOException {
        Path f = blob(name);
        if (data == null) {
            java.nio.file.Files.deleteIfExists(f);
            return;
        }
        java.nio.file.Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        java.nio.file.Files.write(tmp, data);
        java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private Path blob(String name) {
        if (name == null || !name.matches("[a-z0-9._-]+")) throw new IllegalArgumentException("bad blob name " + name);
        return dir.resolve("blobs").resolve(name);
    }

    @Override
    public synchronized StockIndex stock() {
        if (stock == null) {
            stock = readJson(dir.resolve("stock.json"), StockIndex.class);
            if (stock == null) stock = new StockIndex();
            if (stock.containers == null) stock.containers = new TreeMap<>();
        }
        return stock;
    }

    @Override
    public synchronized void saveStock() {
        StockIndex s = stock();
        String json;
        synchronized (s) {
            json = gson.toJson(s);
        }
        try {
            writeString(dir.resolve("stock.json"), json);
        } catch (IOException e) {
            log.accept("cannot save stock index: " + e);
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- loading

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        deleteTemps(dir);
        boolean dirty = false;
        QueueFile q = readJson(dir.resolve("queue.json"), QueueFile.class);
        if (q != null) nextSeq = Math.max(1, q.nextSeq);
        Set<String> seen = new HashSet<>();
        if (q != null && q.jobs != null) {
            for (String id : q.jobs) {
                if (id == null || !ID.matcher(id).matches() || !seen.add(id)) {
                    dirty = true;
                    continue;
                }
                nextSeq = Math.max(nextSeq, seq(id) + 1);
                Job j = loadJob(id);
                if (j == null) {
                    dirty = true;
                    continue;
                }
                order.add(id);
                jobs.put(id, j);
            }
        }
        Path jobsDir = dir.resolve("jobs");
        if (Files.isDirectory(jobsDir)) {
            List<String> orphans = new ArrayList<>();
            try (Stream<Path> s = Files.list(jobsDir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String id = p.getFileName().toString();
                    if (!Files.isDirectory(p) || !ID.matcher(id).matches()) continue;
                    nextSeq = Math.max(nextSeq, seq(id) + 1);
                    deleteTemps(p);
                    if (!seen.contains(id) && Files.exists(p.resolve("job.json"))) orphans.add(id);
                }
            } catch (IOException e) {
                log.accept("cannot list " + jobsDir + ": " + e);
            }
            orphans.sort(Comparator.comparingInt(FileJobStore::seq));
            for (String id : orphans) {
                Job j = loadJob(id);
                if (j == null) continue;
                log.accept("job " + id + " was missing from queue.json; appended");
                order.add(id);
                jobs.put(id, j);
                dirty = true;
            }
        }
        if (dirty) {
            try {
                writeQueue();
            } catch (IOException e) {
                log.accept("cannot rewrite queue.json: " + e);
            }
        }
    }

    private Job loadJob(String id) {
        Path f = jobDir(id).resolve("job.json");
        if (!Files.exists(f)) {
            log.accept("job " + id + " is listed in queue.json but " + f + " is missing; dropped");
            return null;
        }
        Job j = readJson(f, Job.class);
        if (j == null) return null;
        j.id = id;
        if (j.status == null) j.status = JobStatus.QUEUED;
        if (j.statusDetail == null) j.statusDetail = "";
        if (j.materials == null) j.materials = new LinkedHashMap<>();
        if (j.platformIds == null) j.platformIds = new ArrayList<>();
        if (j.tiles == null) j.tiles = new ArrayList<>();
        j.tiles.removeIf(t -> t == null);
        for (Job.TileState t : j.tiles) if (t.status == null) t.status = TileStatus.PENDING;
        return j;
    }

    /** Parses {@code f}; null if absent, unreadable or corrupt (corrupt files are moved aside). */
    private <T> T readJson(Path f, Class<T> type) {
        if (!Files.exists(f)) return null;
        String text;
        try {
            text = Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.accept("cannot read " + f + ": " + e);
            return null;
        }
        try {
            T v = gson.fromJson(text, type);
            if (v == null) throw new IllegalStateException("empty file");
            return v;
        } catch (RuntimeException e) {
            quarantine(f, e);
            return null;
        }
    }

    private void quarantine(Path f, Exception cause) {
        Path target = f.resolveSibling(f.getFileName() + ".corrupt");
        if (Files.exists(target)) target = f.resolveSibling(f.getFileName() + "." + clock.millis() + ".corrupt");
        try {
            Files.move(f, target);
            log.accept("corrupt " + f + " moved to " + target.getFileName() + ": " + cause.getMessage());
        } catch (IOException e) {
            log.accept("corrupt " + f + " (" + cause.getMessage() + ") could not be moved aside: " + e);
        }
    }

    // ---------------------------------------------------------------- writing

    private void writeQueue() throws IOException {
        QueueFile q = new QueueFile();
        q.nextSeq = nextSeq;
        q.jobs = new ArrayList<>(order);
        writeJson(dir.resolve("queue.json"), q);
    }

    private void writeJson(Path target, Object value) throws IOException {
        writeString(target, gson.toJson(value));
    }

    /** Temp file in the same directory, fsync, then atomic move over {@code target}. */
    private void writeString(Path target, String text) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (buf.hasRemaining()) ch.write(buf);
                ch.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void deleteTemps(Path d) {
        if (!Files.isDirectory(d)) return;
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : (Iterable<Path>) s::iterator)
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".tmp")) Files.deleteIfExists(p);
        } catch (IOException e) {
            log.accept("cannot clean temp files in " + d + ": " + e);
        }
    }

    private void deleteTree(Path d) {
        if (!Files.exists(d)) return;
        try (Stream<Path> s = Files.walk(d)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            log.accept("cannot remove " + d + ": " + e);
        }
    }

    // ---------------------------------------------------------------- naming

    private Path jobDir(String id) {
        return dir.resolve("jobs").resolve(id);
    }

    private Path tileFile(String id, TileCoord c) {
        return jobDir(id).resolve("tile_" + c.x() + "_" + c.z() + ".json");
    }

    static String formatId(int seq) {
        return String.format("j%04d", seq);
    }

    private static int seq(String id) {
        return Integer.parseInt(id.substring(1));
    }

    /**
     * Strips control and formatting characters (anvil-safe), keeps the writer's spaces (only the ends are trimmed), at
     * most {@link #MAX_NAME} characters.
     */
    static String sanitizeName(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (cp == '§') return; // a formatting sign: dropped, its code letter stays plain text
            if (cp == 127 || Character.isISOControl(cp)) b.append(' ');
            else b.appendCodePoint(cp);
        });
        String r = b.toString().strip();
        if (r.codePointCount(0, r.length()) > MAX_NAME) r = r.substring(0, r.offsetByCodePoints(0, MAX_NAME)).trim();
        return r;
    }
}
