package com.ekina.application.rag;

import com.ekina.domain.agent.LlmService;
import com.ekina.domain.rag.DocumentParser;
import com.ekina.domain.rag.KnowledgeChunk;
import com.ekina.domain.repository.KnowledgeChunkDomainRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service responsible for importing, parsing, chunking, and vectorizing documents
 * to populate the shared public RAG knowledge base.
 */
@Service
public class DocumentIngestionService {

    @Autowired
    private DocumentParser documentParser;

    @Autowired
    private LlmService llmService;

    @Autowired
    private KnowledgeChunkDomainRepository knowledgeChunkRepository;

    private static final int CHUNK_SIZE_LIMIT = 800; // Character limit per semantic chunk

    public void ingestPdf(InputStream stream, String title, String domainTag) {
        String text = documentParser.parsePdf(stream);
        ingestText(text, title, domainTag);
    }

    public void ingestDocx(InputStream stream, String title, String domainTag) {
        String text = documentParser.parseDocx(stream);
        ingestText(text, title, domainTag);
    }

    public void ingestXlsx(InputStream stream, String title, String domainTag) {
        String text = documentParser.parseXlsx(stream);
        ingestText(text, title, domainTag);
    }

    /**
     * Splitting text into paragraph-coherent semantic chunks, generating vector embeddings, and saving to database.
     */
    public void ingestText(String text, String title, String domainTag) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        // Semantic Chunking: split into paragraphs by double newlines
        String[] paragraphs = text.split("\n\\s*\n");
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) {
                continue;
            }

            // If adding this paragraph exceeds the soft limit, flush current chunk
            if (currentChunk.length() + para.length() > CHUNK_SIZE_LIMIT) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }
            }
            currentChunk.append(para).append("\n\n");
        }
        
        // Flush remaining buffer
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        // Vectorize each chunk and save to vector store
        for (String chunkText : chunks) {
            if (chunkText.trim().isEmpty()) {
                continue;
            }
            try {
                float[] embedding = llmService.embed(chunkText);
                KnowledgeChunk chunk = KnowledgeChunk.builder()
                        .id(UUID.randomUUID())
                        .domainTag(domainTag != null ? domainTag.trim().toLowerCase() : "general")
                        .title(title)
                        .content(chunkText)
                        .embedding(embedding)
                        .createdAt(LocalDateTime.now())
                        .build();
                knowledgeChunkRepository.save(chunk);
            } catch (Exception e) {
                System.err.println("Failed to vectorize knowledge chunk in '" + title + "': " + e.getMessage());
            }
        }
    }
}
