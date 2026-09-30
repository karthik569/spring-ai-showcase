package com.example.springai.service;

import com.example.springai.rag.StubEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence, tested against the real {@link SimpleVectorStore} file format with a stub embedder so the
 * whole restart path runs with Ollama switched off. A "restart" is exactly what the container builds: a new
 * store and a new {@link RagStorePersistence} aimed at the same directory.
 */
class RagStorePersistenceTest {

    private static final String POLICY = "company-policy.md";
    private static final String RUNBOOK = "ops-runbook.md";
    private static final int POLICY_CHUNKS = 3;

    @TempDir
    Path directory;

    @Test
    void uploadedChunksSurviveARestart() {
        Boot first = boot();
        assertEquals(1, first.ingestion().ingest(runbook(), RUNBOOK, "upload"));
        assertTrue(Files.isRegularFile(directory.resolve("vector-store.json")));
        assertTrue(Files.isRegularFile(directory.resolve("rag-store.json")));

        Boot restarted = boot();
        assertTrue(restarted.persistence().restore(), "the snapshot pair should restore");
        assertEquals(1, search(restarted.store(), "when is failover allowed?", RUNBOOK).size(),
                "the uploaded document must still be retrievable after a restart");
    }

    @Test
    void aCorruptSnapshotStartsColdInsteadOfBrickingBoot() throws IOException {
        Files.writeString(directory.resolve("vector-store.json"), "this is not json");
        Files.writeString(directory.resolve("rag-store.json"),
                "{\"version\":1,\"checksums\":{\"" + POLICY + "\":\"aaa\"}}");

        Boot restarted = boot();
        assertFalse(restarted.persistence().restore());

        restarted.ingestion().run();
        assertFalse(search(restarted.store(), "stipend", POLICY).isEmpty(),
                "a rejected snapshot must fall through to a full re-index");
    }

    /**
     * The case the probe exists for: a truncated vector snapshot that still parses into an empty store would
     * otherwise let the boot log claim the chunks were kept while the knowledge base is empty.
     */
    @Test
    void anEmptyVectorSnapshotAgainstANonEmptyManifestIsDiscarded() throws IOException {
        Boot first = boot();
        first.ingestion().run();
        Files.writeString(directory.resolve("vector-store.json"), "{}");

        assertFalse(boot().persistence().restore(),
                "a snapshot whose documents answer no query must be rejected");
    }

    @Test
    void anUnchangedShippedDocumentIsNotIndexedTwice() {
        boot().ingestion().run();
        Boot restarted = boot();
        restarted.ingestion().run();

        assertEquals(POLICY_CHUNKS, count(restarted.store(), POLICY),
                "restore plus a checksum match must not re-embed the shipped document");
    }

    @Test
    void changedBytesReplaceOnlyThatDocument() throws IOException {
        Boot first = boot();
        first.ingestion().run();
        int runbookChunks = first.ingestion().ingest(runbook(), RUNBOOK, "upload");
        Files.writeString(directory.resolve("rag-store.json"),
                "{\"version\":1,\"checksums\":{\"" + POLICY + "\":\"stale\"}}");

        Boot restarted = boot();
        restarted.ingestion().run();

        assertEquals(POLICY_CHUNKS, count(restarted.store(), POLICY), "the stale document was replaced, not doubled");
        assertEquals(runbookChunks, count(restarted.store(), RUNBOOK),
                "refreshing the shipped document must leave the uploaded one alone");
    }

    @Test
    void anEvictionIsPersisted() {
        Boot first = boot();
        first.ingestion().ingest(runbook(), RUNBOOK, "upload");
        first.ingestion().evict(RUNBOOK);

        Boot restarted = boot();
        assertTrue(restarted.persistence().restore());
        assertFalse(restarted.persistence().checksumOf(RUNBOOK).isPresent(),
                "the manifest must forget an evicted filename");
        assertTrue(search(restarted.store(), "when is failover allowed?", RUNBOOK).isEmpty());
    }

    @Test
    void aMissingDirectoryIsCreatedBeforeTheFirstSave() {
        Path nested = directory.resolve("does/not/exist/yet");
        SimpleVectorStore store = store();
        new RagDocumentIngestionService(store, new RagStorePersistence(store, true, nested.toString(), true))
                .ingest(runbook(), RUNBOOK, "upload");

        assertTrue(Files.isDirectory(nested));
        assertTrue(Files.isRegularFile(nested.resolve("vector-store.json")),
                "SimpleVectorStore.save uses Files.createFile, so the parent has to exist");
    }

    @Test
    void disablingPersistenceWritesNothing() throws IOException {
        SimpleVectorStore store = store();
        RagStorePersistence off = new RagStorePersistence(store, false, directory.toString(), true);
        assertFalse(off.restore(), "disabled persistence never claims a restore");
        new RagDocumentIngestionService(store, off).ingest(runbook(), RUNBOOK, "upload");

        try (var files = Files.list(directory)) {
            assertEquals(0, files.count(), "app.rag.store.enabled=false must not touch the directory");
        }
    }

    @Test
    void saveOnWriteFalseDelaysTheWriteUntilSomethingAsksForIt() {
        SimpleVectorStore store = store();
        RagStorePersistence lazy = new RagStorePersistence(store, true, directory.toString(), false);
        new RagDocumentIngestionService(store, lazy).ingest(runbook(), RUNBOOK, "upload");
        assertFalse(Files.exists(directory.resolve("vector-store.json")),
                "without save-on-write an index change leaves nothing on disk yet");

        lazy.persist();
        assertTrue(Files.isRegularFile(directory.resolve("vector-store.json")));
    }

    // --- fixtures ---------------------------------------------------------------------------------------

    /** One process: the three beans the container wires, over the shared temp directory. */
    private record Boot(SimpleVectorStore store, RagStorePersistence persistence,
                        RagDocumentIngestionService ingestion) {
    }

    private Boot boot() {
        SimpleVectorStore store = store();
        RagStorePersistence persistence = new RagStorePersistence(store, true, directory.toString(), true);
        return new Boot(store, persistence, new RagDocumentIngestionService(store, persistence));
    }

    private static SimpleVectorStore store() {
        return SimpleVectorStore.builder(new StubEmbeddingModel()).build();
    }

    private static Resource runbook() {
        return new ClassPathResource("rag/corpus/" + RUNBOOK);
    }

    private static List<Document> search(SimpleVectorStore store, String query, String filename) {
        return store.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(20)
                .similarityThresholdAll()
                .filterExpression(new FilterExpressionBuilder().eq("filename", filename).build())
                .build());
    }

    private static int count(SimpleVectorStore store, String filename) {
        return search(store, "policy runbook leave budget", filename).size();
    }
}
