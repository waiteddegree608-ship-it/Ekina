package com.ekina.application.paper;

import com.ekina.application.channel.ChannelService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight autonomous opener: periodically starts paper pipelines from a domain pool.
 * Disabled by default — enable ekina.paper.scout.enabled=true on the workstation.
 */
@Service
public class TopicScout {

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    private PaperOrchestrator paperOrchestrator;

    @Autowired
    private ChannelService channelService;

    @Autowired
    private com.ekina.application.home.HomeQuotaService homeQuotaService;

    @Value("${ekina.paper.scout.enabled:false}")
    private boolean enabled;

    @Value("${ekina.paper.scout.max-per-day:2}")
    private int maxPerDay;

    @Value("${ekina.reminders.default-user:user-1}")
    private String defaultUser;

    @Value("${ekina.paper.scout.domains:LoRA diffusion,vision transformer fine-tuning,graph neural network robustness,speech enhancement CNN,medical image segmentation U-Net}")
    private String domainsCsv;

    private final List<String> domains = new ArrayList<>();
    private int cursor = 0;

    @PostConstruct
    public void init() {
        domains.clear();
        if (domainsCsv != null) {
            for (String d : domainsCsv.split(",")) {
                if (d != null && !d.isBlank()) domains.add(d.trim());
            }
        }
        if (domains.isEmpty()) {
            domains.add("LoRA diffusion");
        }
    }

    @Scheduled(fixedDelayString = "${ekina.paper.scout.interval-ms:3600000}")
    public void tick() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        try {
            int today = countStartedToday();
            if (today >= maxPerDay) return;
            // Don't stack if something already running
            boolean busy = paperOrchestrator.list(defaultUser, 5).stream()
                    .anyMatch(p -> "running".equalsIgnoreCase(p.getStatus()));
            if (busy) return;

            String domain = domains.get(Math.floorMod(cursor++, domains.size()));
            String title = "Autonomous incremental study: " + domain;
            PaperProject p = paperOrchestrator.start(defaultUser, domain, "CCF-C", title, true);
            appendScoutLog(Map.of(
                    "ts", Instant.now().toString(),
                    "paperId", p.getId(),
                    "domain", domain
            ));
            channelService.append(defaultUser, "system", "system",
                    "🧭 TopicScout 自主开题 `" + p.getId() + "` · " + domain,
                    Map.of("paperId", p.getId(), "domain", domain), "done");
        } catch (Exception e) {
            System.err.println("[TopicScout] " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** Manual trigger for tests / tools. */
    public PaperProject openOne(String domainHint) {
        String domain = (domainHint == null || domainHint.isBlank())
                ? domains.get(Math.floorMod(cursor++, domains.size()))
                : domainHint.trim();
        return paperOrchestrator.start(defaultUser, domain, "CCF-C",
                "Scouted incremental study: " + domain, true);
    }

    private int countStartedToday() {
        String day = LocalDate.now(ZoneId.systemDefault()).toString();
        return (int) paperOrchestrator.list(defaultUser, 50).stream()
                .filter(p -> p.getCreatedAt() != null && p.getCreatedAt().startsWith(day)
                        || (p.getCreatedAt() != null && p.getCreatedAt().contains(day)))
                .count();
        // ISO instant may be UTC date — also count by local file stamp if needed
    }

    private void appendScoutLog(Map<String, Object> row) {
        try {
            Path f = homeQuotaService.homePath().resolve("state").resolve("topic_scout.jsonl");
            Files.createDirectories(f.getParent());
            String line = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(row) + "\n";
            Files.writeString(f, line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
