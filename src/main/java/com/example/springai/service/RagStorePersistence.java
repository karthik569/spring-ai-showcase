package com.example.springai.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Writes {@link SimpleVectorStore} to disk so uploaded documents outlive the process.
 *
 * <p>Two files, one owner each: {@code vector-store.json} is Spring AI's own serialization and is opaque
 * to this class, {@code rag-store.json} holds only the checksum per indexed filename. A restore needs both;
 * either one missing or unreadable means a cold start, which is today's behaviour rather than a failure.
 */
@Component
public class RagStorePersistence {

    private static final Logger log = LoggerFactory.getLogger(RagStorePersistence.class);

    private static final String VECTOR_FILE = "vector-store.json";
    private static final String CHECKSUM_FILE = "rag-store.json";
    private static final int SCHEMA_VERSION = 1;

    private final SimpleVectorStore vectorStore;
    private final Path directory;
    private final boolean enabled;
    private final boolean saveOnWrite;
    private final ObjectMapper objectMapper;
    private final Map<String, String> checksums = new LinkedHashMap<>();

    private boolean dirty;
    private int batchDepth;

    public RagStorePersistence(SimpleVectorStore vectorStore,
                               @Value("${app.rag.store.enabled:true}") boolean enabled,
                               @Value("${app.rag.store.directory:./data/rag}") String directory,
                               @Value("${app.rag.store.save-on-write:true}") boolean saveOnWrite) {
        this.vectorStore = vectorStore;
        this.enabled = enabled;
        this.directory = Path.of(directory);
        this.saveOnWrite = saveOnWrite;
        // Reading a snapshot written by a future version must degrade to a cold start, not an exception.
        this.objectMapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Loads both snapshot files into the store.
     *
     * @return true when the store now holds what a previous run indexed; false means cold start and the
     *         shipped documents are re-indexed
     */
    public synchronized boolean restore() {
        if (!enabled) {
            return false;
        }
        Path vectorFile = directory.resolve(VECTOR_FILE);
        Path checksumFile = directory.resolve(CHECKSUM_FILE);
        if (!Files.isReadable(vectorFile) || !Files.isReadable(checksumFile)) {
            log.info("[RAG-STORE] No snapshot pair in {}, indexing from scratch", directory);
            return false;
        }
        try {
            ChecksumSnapshot snapshot = objectMapper.readValue(checksumFile.toFile(), ChecksumSnapshot.class);
            checksums.clear();
            if (snapshot.checksums() != null) {
                checksums.putAll(snapshot.checksums());
            }
            vectorStore.load(vectorFile.toFile());
            if (isPartialSnapshot()) {
                log.warn("[RAG-STORE] Discarding stale or partial vector snapshot in {}: {} document(s) "
                        + "listed as indexed but the store answers no query", directory, checksums.size());
                checksums.clear();
                return false;
            }
            log.info("[RAG-STORE] Restored snapshot from {} ({} document checksum(s))",
                    directory, checksums.size());
            return true;
        } catch (Exception ex) {
            log.warn("[RAG-STORE] Snapshot in {} is unreadable ({}), cold start", directory, ex.getMessage());
            checksums.clear();
            return false;
        }
    }

    /**
     * True when the checksum file claims indexed documents yet a threshold-free search finds nothing — a
     * truncated {@code vector-store.json} can still parse into an empty store, and without this probe the
     * boot log would say the chunks were kept while the knowledge base is empty.
     *
     * <p>The probe itself needs the embedder, because a search embeds the query. When it cannot answer the
     * snapshot is kept: re-indexing would fail for the same reason, so the store would only end up emptier.
     */
    private boolean isPartialSnapshot() {
        if (checksums.isEmpty()) {
            return false;
        }
        try {
            return vectorStore.similaritySearch(SearchRequest.builder()
                    .query("policy")
                    .topK(1)
                    .similarityThresholdAll()
                    .build()).isEmpty();
        } catch (Exception ex) {
            log.debug("[RAG-STORE] Integrity probe skipped, the embedder did not answer: {}", ex.getMessage());
            return false;
        }
    }

    /** Writes both files. Never throws: losing the snapshot must not fail the request that caused it. */
    public synchronized void persist() {
        if (!enabled || !dirty) {
            return;
        }
        try {
            // SimpleVectorStore.save uses Files.createFile, so the parent has to exist already.
            Files.createDirectories(directory);
            vectorStore.save(directory.resolve(VECTOR_FILE).toFile());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(CHECKSUM_FILE).toFile(),
                    new ChecksumSnapshot(SCHEMA_VERSION, Map.copyOf(checksums)));
            dirty = false;
        } catch (Exception ex) {
            log.warn("[RAG-STORE] Snapshot could not be written to {}, the next boot starts cold: {}",
                    directory, ex.getMessage());
        }
    }

    public synchronized Optional<String> checksumOf(String filename) {
        return Optional.ofNullable(checksums.get(filename));
    }

    /** Records the bytes a filename was indexed from, so an unchanged document is not embedded again. */
    public synchronized void remember(String filename, String sha256) {
        if (filename == null || sha256 == null) {
            return;
        }
        checksums.put(filename, sha256);
        markDirty();
    }

    public synchronized void forget(String filename) {
        if (filename == null) {
            return;
        }
        checksums.remove(filename);
        markDirty();
    }

    /** Suppresses per-write saves while the boot refresh loops. */
    public synchronized void beginBatch() {
        batchDepth++;
    }

    public synchronized void endBatch() {
        batchDepth = Math.max(0, batchDepth - 1);
        if (batchDepth == 0) {
            persist();
        }
    }

    private void markDirty() {
        dirty = true;
        if (saveOnWrite && batchDepth == 0) {
            persist();
        }
    }

    @PreDestroy
    public void persistOnShutdown() {
        persist();
    }

    /**
     * Digests a resource from either the exploded classpath or a packaged jar, so a shipped document and an
     * uploaded one are comparable by content rather than by path.
     */
    public static String sha256(Resource resource) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK specification", ex);
        }
        try (InputStream in = resource.getInputStream()) {
            byte[] buffer = new byte[8192];
            for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChecksumSnapshot(int version, Map<String, String> checksums) {
    }
}
