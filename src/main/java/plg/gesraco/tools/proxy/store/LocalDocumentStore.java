package plg.gesraco.tools.proxy.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * File-based store holding local copies of Alfresco nodes. It knows nothing about the API the nodes come from.
 *
 * <p>Layout: {@code <root>/<space>/<store>/<guid>/node.json} (record with the node document and cached API
 * responses) plus {@code content-*.bin} files holding the document content. Records are written atomically
 * (temp file + move); callers serialize access per node with {@link #withLock}.
 */
public class LocalDocumentStore {

    public static final String RECORD_FILE = "node.json";
    private static final Pattern CONTENT_FILE = Pattern.compile("^content-[A-Za-z0-9-]+\\.bin$");

    /** Body of a {@link #withLock} section. */
    @FunctionalInterface
    public interface Locked<T> {
        T run() throws IOException;
    }

    /** Writes content to a stream. */
    @FunctionalInterface
    public interface ContentWriter {
        void write(OutputStream out) throws IOException;
    }

    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<NodeRef, ReentrantLock> locks = new ConcurrentHashMap<>();

    public LocalDocumentStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    /** Runs {@code block} holding the lock of {@code ref} (re-entrant, so nested sections on one node are fine). */
    public <T> T withLock(NodeRef ref, Locked<T> block) throws IOException {
        ReentrantLock lock = locks.computeIfAbsent(ref, r -> new ReentrantLock());
        lock.lock();
        try {
            return block.run();
        } finally {
            lock.unlock();
        }
    }

    public Path nodeDir(NodeRef ref) {
        return root.resolve(ref.space()).resolve(ref.store()).resolve(ref.guid());
    }

    public ObjectNode load(NodeRef ref) throws IOException {
        Path file = nodeDir(ref).resolve(RECORD_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        JsonNode node = mapper.readTree(file.toFile());
        return node instanceof ObjectNode o ? o : null;
    }

    public void save(NodeRef ref, ObjectNode record) throws IOException {
        Path dir = Files.createDirectories(nodeDir(ref));
        Path tmp = dir.resolve(RECORD_FILE + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), record);
        moveReplacing(tmp, dir.resolve(RECORD_FILE));
    }

    /**
     * New record for {@code ref}. {@code origin} is {@code primary}, {@code fallback} or {@code local}.
     */
    public ObjectNode newRecord(NodeRef ref, String origin, String sourceUrl) {
        String now = Instant.now().toString();
        ObjectNode record = mapper.createObjectNode();
        record.put("nodeRef", ref.toString());
        record.put("origin", origin);
        record.put("sourceUrl", sourceUrl);
        record.put("copiedAt", now);
        record.put("updatedAt", now);
        record.put("locallyModified", "local".equals(origin));
        record.put("deleted", false);
        record.putObject("resources");
        return record;
    }

    /**
     * Streams {@code writer} into a new, uniquely named content file of {@code ref}.
     * A new name per write avoids replacing a file that may still be served to another client.
     */
    public StoredContent writeContent(NodeRef ref, String mimetype, ContentWriter writer) throws IOException {
        Path dir = Files.createDirectories(nodeDir(ref));
        String name = "content-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + ".bin";
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            writer.write(out);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(file);
            throw e;
        }
        return new StoredContent(name, Files.size(file), mimetype);
    }

    public StoredContent copyContent(NodeRef from, String fileName, NodeRef to, String mimetype) throws IOException {
        Path source = contentFile(from, fileName);
        if (source == null || !Files.exists(source)) {
            return null;
        }
        return writeContent(to, mimetype, out -> Files.copy(source, out));
    }

    /**
     * Resolves a content file name read from a record, refusing anything that isn't a store-generated name.
     */
    public Path contentFile(NodeRef ref, String fileName) {
        return fileName != null && CONTENT_FILE.matcher(fileName).matches() ? nodeDir(ref).resolve(fileName) : null;
    }

    public void deleteContent(NodeRef ref, String fileName) {
        Path file = contentFile(ref, fileName);
        if (file != null) {
            deleteQuietly(file);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // File may still be open by a concurrent download (Windows); it is orphaned, not referenced
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
