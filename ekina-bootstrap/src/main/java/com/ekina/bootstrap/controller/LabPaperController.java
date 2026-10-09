package com.ekina.bootstrap.controller;

import com.ekina.application.lab.LabPaperPreviewService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/lab")
public class LabPaperController {

    private final LabPaperPreviewService previewService;

    public LabPaperController(LabPaperPreviewService previewService) {
        this.previewService = previewService;
    }

    @GetMapping("/papers/preview")
    public Map<String, Object> preview(
            @RequestParam(name = "projectId", required = false) String projectId,
            @RequestParam(name = "slug", required = false) String slug) {
        return previewService.preview(projectId, slug);
    }

    @GetMapping("/papers/file")
    public ResponseEntity<byte[]> file(@RequestParam("rel") String rel) throws Exception {
        Path p = previewService.resolveFile(rel);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(previewService.mediaType(p))
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePublic())
                .body(Files.readAllBytes(p));
    }
}
