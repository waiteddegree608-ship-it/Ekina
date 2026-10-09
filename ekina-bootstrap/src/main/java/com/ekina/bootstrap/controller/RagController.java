package com.ekina.bootstrap.controller;

import com.ekina.application.rag.DocumentIngestionService;
import com.ekina.application.rag.RagOrchestrator;
import com.ekina.domain.rag.KnowledgeChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private RagOrchestrator ragOrchestrator;

    public static class TextIngestRequest {
        private String text;
        private String title;
        private String domainTag;

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDomainTag() {
            return domainTag;
        }

        public void setDomainTag(String domainTag) {
            this.domainTag = domainTag;
        }
    }

    public static class SearchResultDto {
        private String id;
        private String title;
        private String domainTag;
        private String content;

        public SearchResultDto(String id, String title, String domainTag, String content) {
            this.id = id;
            this.title = title;
            this.domainTag = domainTag;
            this.content = content;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getDomainTag() {
            return domainTag;
        }

        public String getContent() {
            return content;
        }
    }

    @PostMapping("/ingest/text")
    public ResponseEntity<Map<String, String>> ingestText(@RequestBody TextIngestRequest request) {
        ingestionService.ingestText(request.getText(), request.getTitle(), request.getDomainTag());
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Text ingested successfully"));
    }

    @PostMapping("/ingest/file")
    public ResponseEntity<Map<String, String>> ingestFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "domainTag", defaultValue = "general") String domainTag) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "File is empty"));
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Filename is missing"));
        }

        try (InputStream is = file.getInputStream()) {
            String lowerName = fileName.toLowerCase();
            if (lowerName.endsWith(".pdf")) {
                ingestionService.ingestPdf(is, title, domainTag);
            } else if (lowerName.endsWith(".docx")) {
                ingestionService.ingestDocx(is, title, domainTag);
            } else if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
                ingestionService.ingestXlsx(is, title, domainTag);
            } else {
                byte[] bytes = file.getBytes();
                String textContent = new String(bytes, StandardCharsets.UTF_8);
                ingestionService.ingestText(textContent, title, domainTag);
            }
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "File '" + fileName + "' ingested successfully"));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", "Failed to ingest file: " + e.getMessage()));
        }
    }

    @GetMapping("/search")
    public ResponseEntity<List<SearchResultDto>> search(
            @RequestParam("query") String query,
            @RequestParam(value = "domains", required = false) List<String> domains,
            @RequestParam(value = "targetCount", defaultValue = "5") int targetCount) {

        List<KnowledgeChunk> chunks = ragOrchestrator.retrieveContext(query, domains, targetCount);
        List<SearchResultDto> results = chunks.stream()
                .map(chunk -> new SearchResultDto(
                        chunk.getId().toString(),
                        chunk.getTitle(),
                        chunk.getDomainTag(),
                        chunk.getContent()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(results);
    }
}
