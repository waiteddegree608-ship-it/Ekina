package com.ekina.application.home;

import com.ekina.application.channel.ChannelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Soft disk quota for Agent Home (default 30GB). Emits permission_request cards when high water.
 */
@Service
public class HomeQuotaService {

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private ChannelService channelService;

    @Value("${ekina.agent-home:./AgentHome}")
    private String agentHome;

    @Value("${ekina.sandbox-dir:./sandbox}")
    private String sandboxDir;

    @Value("${ekina.home.quota-gb:30}")
    private long quotaGb;

    @Value("${ekina.home.warn-ratio:0.70}")
    private double warnRatio;

    @Value("${ekina.home.critical-ratio:0.90}")
    private double criticalRatio;

    @Value("${ekina.reminders.default-user:user-1}")
    private String defaultUser;

    private volatile long lastAlertMs = 0;

    @PostConstruct
    public void init() {
        try {
            Path home = Path.of(agentHome).toAbsolutePath().normalize();
            Files.createDirectories(home.resolve("workspace"));
            Files.createDirectories(home.resolve("inbox"));
            Files.createDirectories(home.resolve("outbox"));
            Files.createDirectories(home.resolve("diary"));
            Files.createDirectories(home.resolve("peeks"));
            Files.createDirectories(home.resolve("cache"));
            Files.createDirectories(home.resolve("state"));
            writeQuotaSnapshot(scanBytes());
            System.out.println("[HomeQuota] agent-home=" + home + " quotaGb=" + quotaGb);
        } catch (Exception e) {
            System.err.println("[HomeQuota] init failed: " + e.getMessage());
        }
    }

    public Path homePath() {
        return Path.of(agentHome).toAbsolutePath().normalize();
    }

    public Map<String, Object> status() {
        long used = scanBytes();
        long limit = quotaGb * 1024L * 1024L * 1024L;
        double ratio = limit <= 0 ? 0 : (double) used / (double) limit;
        Map<String, Object> m = new HashMap<>();
        m.put("agentHome", homePath().toString());
        m.put("sandboxDir", Path.of(sandboxDir).toAbsolutePath().normalize().toString());
        m.put("usedBytes", used);
        m.put("limitBytes", limit);
        m.put("quotaGb", quotaGb);
        m.put("usedRatio", ratio);
        m.put("usedHuman", human(used));
        m.put("limitHuman", human(limit));
        m.put("level", level(ratio));
        m.put("ts", Instant.now().toString());
        return m;
    }

    public synchronized void setQuotaGb(long gb) {
        this.quotaGb = Math.max(1, gb);
        writeQuotaSnapshot(scanBytes());
    }

    @Scheduled(fixedDelayString = "${ekina.home.scan-ms:600000}")
    public void scheduledScan() {
        Map<String, Object> st = status();
        writeQuotaSnapshot(((Number) st.get("usedBytes")).longValue());
        double ratio = ((Number) st.get("usedRatio")).doubleValue();
        String level = String.valueOf(st.get("level"));
        if ("ok".equals(level)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAlertMs < 6 * 60 * 60 * 1000L) {
            return; // at most one alert / 6h
        }
        lastAlertMs = now;
        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", "disk_quota");
        payload.put("needGb", Math.max(5, (long) Math.ceil(quotaGb * 0.25)));
        payload.put("status", st);
        payload.put("risk", "critical".equals(level) ? "high" : "medium");
        String text = "主人，姬娜的家磁盘快满了～ 已用 "
                + st.get("usedHuman") + " / " + st.get("limitHuman")
                + "。能不能再多分一点空间给姬娜呀？ (｡•́︿•̀｡)";
        channelService.append(defaultUser, "agent", "permission_request", text, payload, "pending");
    }

    private String level(double ratio) {
        if (ratio >= criticalRatio) return "critical";
        if (ratio >= warnRatio) return "warn";
        return "ok";
    }

    private long scanBytes() {
        AtomicLong total = new AtomicLong();
        for (Path root : new Path[]{homePath(), Path.of(sandboxDir).toAbsolutePath().normalize()}) {
            if (!Files.exists(root)) continue;
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        total.addAndGet(attrs.size());
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Exception ignored) {
            }
        }
        return total.get();
    }

    private void writeQuotaSnapshot(long used) {
        try {
            Path f = homePath().resolve("state").resolve("quota.json");
            Files.createDirectories(f.getParent());
            Map<String, Object> m = new HashMap<>();
            m.put("usedBytes", used);
            m.put("quotaGb", quotaGb);
            m.put("ts", Instant.now().toString());
            Files.writeString(f, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(m));
        } catch (Exception ignored) {
        }
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do {
            v /= 1024.0;
            i++;
        } while (v >= 1024 && i < u.length - 1);
        return String.format(java.util.Locale.ROOT, "%.2f %s", v, u[i]);
    }
}
