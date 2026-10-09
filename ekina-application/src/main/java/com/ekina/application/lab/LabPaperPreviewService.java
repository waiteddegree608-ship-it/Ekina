package com.ekina.application.lab;

import com.ekina.application.paper.PaperAuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Serves lab-paper cover / figure previews to the right-hand board.
 */
@Service
public class LabPaperPreviewService {

    @Value("${ekina.agent-home:./AgentHome}")
    private String agentHome;

    private final PaperAuditService paperAuditService;

    public LabPaperPreviewService(PaperAuditService paperAuditService) {
        this.paperAuditService = paperAuditService;
    }

    public Map<String, Object> preview(String projectId, String slug) {
        Path paper = resolve(projectId, slug);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId == null ? "" : projectId);
        out.put("slug", slugOf(paper));
        out.put("paperDir", paper.toAbsolutePath().toString());
        Path figures = paper.resolve("figures");
        List<Map<String, Object>> figs = new ArrayList<>();
        Path cover = firstExisting(figures, "cover.jpg", "cover.png", "cover.pdf");
        if (cover != null) {
            out.put("coverUrl", fileUrl(paper, cover));
            out.put("coverName", cover.getFileName().toString());
        }
        if (Files.isDirectory(figures)) {
            try (var stream = Files.list(figures)) {
                stream.sorted().forEach(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.startsWith("fig_") && n.endsWith(".png")) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", p.getFileName().toString());
                        row.put("url", fileUrl(paper, p));
                        figs.add(row);
                    }
                });
            } catch (Exception ignored) {
            }
        }
        out.put("figures", figs);
        out.put("audit", paperAuditService.audit(paper));
        out.put("hasPaper", Files.isRegularFile(paper.resolve("main.tex")));
        return out;
    }

    public Path resolveFile(String rel) {
        if (rel == null || rel.isBlank()) return null;
        String clean = rel.replace('\\', '/');
        if (clean.contains("..")) return null;
        Path academic = Path.of(agentHome, "lab", "academic").toAbsolutePath().normalize();
        Path target = academic.resolve(clean).normalize();
        if (!target.startsWith(academic) || !Files.isRegularFile(target)) return null;
        String name = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".pdf") || name.endsWith(".svg"))) {
            return null;
        }
        return target;
    }

    public MediaType mediaType(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (n.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (n.endsWith(".svg")) return MediaType.parseMediaType("image/svg+xml");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private Path resolve(String projectId, String slug) {
        if (slug != null && !slug.isBlank()) {
            return paperAuditService.resolvePaperDir(slug);
        }
        if (projectId != null && !projectId.isBlank()) {
            return paperAuditService.resolvePaperDir(projectId);
        }
        return paperAuditService.resolvePaperDir("");
    }

    private String fileUrl(Path paper, Path file) {
        Path academic = Path.of(agentHome, "lab", "academic").toAbsolutePath().normalize();
        String rel = academic.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return "/api/lab/papers/file?rel=" + rel;
    }

    private static String slugOf(Path paper) {
        Path p = paper.getFileName().toString().equals("paper") && paper.getParent() != null
                ? paper.getParent() : paper;
        return p.getFileName().toString();
    }

    private static Path firstExisting(Path dir, String... names) {
        for (String n : names) {
            Path p = dir.resolve(n);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }
}
