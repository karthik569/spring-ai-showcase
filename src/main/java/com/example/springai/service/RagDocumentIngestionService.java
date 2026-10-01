package com.example.springai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import com.example.springai.vectorstore.KeywordLegRebuildable;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Service
public class RagDocumentIngestionService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentIngestionService.class);

    private final VectorStore vectorStore;
    private final RagStorePersistence persistence;

    public RagDocumentIngestionService(VectorStore vectorStore, RagStorePersistence persistence) {
        this.vectorStore = vectorStore;
        this.persistence = persistence;
    }

    @Override
    public void run(String... args) {
        try {
            // Restore first: uploads live only in the store, so a run that skips this step loses them.
            boolean restored = persistence.restore();
            persistence.beginBatch();
            try {
                log.info("[RAG-INGESTION] Scanning for knowledge base documents in classpath:/docs/*.md...");
                PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
                Resource[] resources = resolver.getResources("classpath:/docs/*.md");

                for (Resource resource : resources) {
                    String filename = resource.getFilename();
                    String checksum = digestOf(resource);
                    if (restored && checksum != null
                            && checksum.equals(persistence.checksumOf(filename).orElse(null))) {
                        log.info("[RAG-INGESTION] Kept persisted chunks for {} (unchanged)", filename);
                        continue;
                    }
                    // Unconditional rather than only on the restored path: a snapshot the probe rejected can
                    // still hold half of a document's chunks, and deleting from an empty store costs nothing.
                    evict(filename);
                    int chunks = ingest(resource, filename, "classpath");
                    log.info("[RAG-INGESTION] Indexed {} chunks from {}", chunks, filename);
                }
            } finally {
                persistence.endBatch();
            }
        } catch (Exception ex) {
            log.warn("[RAG-INGESTION] Document ingestion deferred or offline: {}", ex.getMessage());
        }
        // A restore writes chunks straight into the inner store, bypassing the hybrid decorator's add() and
        // therefore its keyword index. Rebuilding here — after both the restore and the refresh above — is
        // what keeps the keyword leg populated across a restart. The check is on the keyword-leg capability,
        // not on HybridVectorStore: the primary store is a re-ranking decorator that wraps it.
        if (vectorStore instanceof KeywordLegRebuildable rebuildable) {
            rebuildable.rebuildKeywordIndex();
        }
    }

    /**
     * Reads, splits and stores one document.
     *
     * @param origin recorded as metadata so uploads and shipped documents can be told apart when filtering
     * @return the number of chunks written
     */
    public int ingest(Resource resource, String filename, String origin) {
        // Digest before the reader consumes the stream, so the recorded bytes match what was indexed.
        String checksum = digestOf(resource);
        TextReader textReader = new TextReader(resource);
        textReader.getCustomMetadata().put("filename", filename);
        textReader.getCustomMetadata().put("origin", origin);
        textReader.getCustomMetadata().put("ingestedAt", Instant.now().toString());

        // One chunk per section, not one per document: with a whole policy file in a single embedding,
        // every question matches the same vector and a small model answers from whichever section it
        // reads first.
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(120)
                .withMinChunkSizeChars(50)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(200)
                .withKeepSeparator(true)
                .build();

        List<Document> chunks = splitter.apply(textReader.get());
        if (chunks.isEmpty()) {
            return 0;
        }
        vectorStore.add(chunks);
        persistence.remember(filename, checksum);
        return chunks.size();
    }

    /** Removes previously stored chunks carrying this filename, so re-uploading replaces rather than duplicates. */
    public void evict(String filename) {
        vectorStore.delete(new FilterExpressionBuilder()
                .eq("filename", filename)
                .build());
        persistence.forget(filename);
    }

    /** A resource that cannot be read records no checksum, which means the next boot indexes it again. */
    private static String digestOf(Resource resource) {
        try {
            return RagStorePersistence.sha256(resource);
        } catch (IOException ex) {
            log.warn("[RAG-INGESTION] Could not read {} for a checksum: {}", resource, ex.getMessage());
            return null;
        }
    }
}
