package com.example.springai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class RagDocumentIngestionService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentIngestionService.class);

    private final VectorStore vectorStore;

    public RagDocumentIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        try {
            log.info("[RAG-INGESTION] Scanning for knowledge base documents in classpath:/docs/*.md...");
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:/docs/*.md");

            for (Resource resource : resources) {
                int chunks = ingest(resource, resource.getFilename(), "classpath");
                log.info("[RAG-INGESTION] Indexed {} chunks from {}", chunks, resource.getFilename());
            }
        } catch (Exception ex) {
            log.warn("[RAG-INGESTION] Document ingestion deferred or offline: {}", ex.getMessage());
        }
    }

    /**
     * Reads, splits and stores one document.
     *
     * @param origin recorded as metadata so uploads and shipped documents can be told apart when filtering
     * @return the number of chunks written
     */
    public int ingest(Resource resource, String filename, String origin) {
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
        return chunks.size();
    }

    /** Removes previously stored chunks carrying this filename, so re-uploading replaces rather than duplicates. */
    public void evict(String filename) {
        vectorStore.delete(new FilterExpressionBuilder()
                .eq("filename", filename)
                .build());
    }
}
