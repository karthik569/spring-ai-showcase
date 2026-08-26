package com.example.springai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

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
                log.info("[RAG-INGESTION] Ingesting document: {}", resource.getFilename());
                TextReader textReader = new TextReader(resource);
                textReader.getCustomMetadata().put("filename", resource.getFilename());
                List<Document> documents = textReader.get();

                TokenTextSplitter splitter = new TokenTextSplitter(400, 100, 5, 1000, true);
                List<Document> splitDocuments = splitter.apply(documents);

                vectorStore.accept(splitDocuments);
                log.info("[RAG-INGESTION] Successfully indexed {} chunks from {}", splitDocuments.size(), resource.getFilename());
            }
        } catch (Exception ex) {
            log.warn("[RAG-INGESTION] Document ingestion deferred or offline: {}", ex.getMessage());
        }
    }
}
