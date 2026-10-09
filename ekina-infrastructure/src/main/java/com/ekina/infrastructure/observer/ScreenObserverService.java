package com.ekina.infrastructure.observer;

import com.ekina.application.channel.ChannelService;
import com.ekina.application.home.HomeQuotaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Screen / window observer. Default is lightweight (foreground window title).
 * Full screenshot optional; vision summarization is best-effort via VisionClient when available.
 */
@Service
public class ScreenObserverService {

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private HomeQuotaService homeQuotaService;

    @Autowired
    private ChannelService channelService;

    @Autowired(required = false)
    private com.ekina.infrastructure.agent.video.VisionClient visionClient;

    @Value("${ekina.observer.enabled:true}")
    private boolean enabled;

    @Value("${ekina.observer.default-user:user-1}")
    private String defaultUser;

    @Value("${ekina.observer.blacklist:1Password,KeePass,Bank,密码,钱包}")
    private String blacklistCsv;

    /**
     * @param mode window | screen
     * @param notify whether to push channel card
     */
    public Map<String, Object> peek(String mode, boolean notify) {
        Map<String, Object> result = new HashMap<>();
        result.put("ts", Instant.now().toString());
        if (!enabled) {
            result.put("error", "observer disabled");
            return result;
        }
        String m = mode == null ? "window" : mode.toLowerCase(Locale.ROOT);
        try {
            Map<String, String> fg = foregroundWindow();
            result.put("window", fg);
            if (isBlacklisted(fg.getOrDefault("title", "") + " " + fg.getOrDefault("process", ""))) {
                result.put("blocked", true);
                result.put("summary", "敏感窗口，已跳过偷看。");
                return result;
            }

            String summary;
            if ("screen".equals(m) || "full".equals(m)) {
                Path img = captureScreenshot();
                result.put("screenshot", img.toString());
                summary = "前台：" + fg.getOrDefault("title", "?")
                        + " (" + fg.getOrDefault("process", "?") + ")";
                if (visionClient != null && img.toFile().isFile()) {
                    String desc = visionClient.describeImageFile(img.toFile(),
                            "这是用户桌面截图。用中文简洁说明：主要窗口/应用、用户可能在做什么。不要读出密码或隐私字段。");
                    result.put("vision", desc);
                    summary = summary + " | 画面：" + desc;
                } else {
                    summary = summary + " | （无视觉模型描述，仅窗口信息）";
                }
            } else {
                summary = "前台窗口：" + fg.getOrDefault("title", "?")
                        + " / 进程 " + fg.getOrDefault("process", "?");
            }
            result.put("summary", summary);

            Path peeks = homeQuotaService.homePath().resolve("peeks");
            Files.createDirectories(peeks);
            String id = "peek_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path meta = peeks.resolve(id + ".json");
            result.put("id", id);
            Files.writeString(meta, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), StandardCharsets.UTF_8);
            result.put("metaFile", meta.toString());

            if (notify) {
                channelService.append(defaultUser, "agent", "system",
                        "（观察笔记）" + summary, result, "done");
            }
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    private boolean isBlacklisted(String hay) {
        String h = hay.toLowerCase(Locale.ROOT);
        for (String b : blacklistCsv.split(",")) {
            if (!b.isBlank() && h.contains(b.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> foregroundWindow() {
        Map<String, String> m = new HashMap<>();
        m.put("title", "");
        m.put("process", "");
        Path script = null;
        try {
            // Write script to temp file to avoid quoting/encoding breakage on Windows
            String ps = String.join("\n",
                    "Add-Type -TypeDefinition @'",
                    "using System;",
                    "using System.Runtime.InteropServices;",
                    "using System.Text;",
                    "public class EkinaFgWin {",
                    "  [DllImport(\"user32.dll\")] public static extern IntPtr GetForegroundWindow();",
                    "  [DllImport(\"user32.dll\", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);",
                    "  [DllImport(\"user32.dll\")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);",
                    "}",
                    "'@",
                    "$h = [EkinaFgWin]::GetForegroundWindow()",
                    "$sb = New-Object System.Text.StringBuilder 1024",
                    "[void][EkinaFgWin]::GetWindowText($h, $sb, $sb.Capacity)",
                    "$procId = 0",
                    "[void][EkinaFgWin]::GetWindowThreadProcessId($h, [ref]$procId)",
                    "$p = Get-Process -Id $procId -ErrorAction SilentlyContinue",
                    "Write-Output ('TITLE=' + $sb.ToString())",
                    "Write-Output ('PROC=' + $(if ($p) { $p.ProcessName } else { '' }))"
            );
            script = Files.createTempFile("ekina-fg-", ".ps1");
            Files.writeString(script, ps, StandardCharsets.UTF_8);
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return m;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("TITLE=")) {
                        m.put("title", line.substring(6).trim());
                    } else if (line.startsWith("PROC=")) {
                        m.put("process", line.substring(5).trim());
                    }
                }
            }
        } catch (Exception e) {
            m.put("error", e.getMessage());
        } finally {
            if (script != null) {
                try { Files.deleteIfExists(script); } catch (Exception ignored) {}
            }
        }
        return m;
    }

    private Path captureScreenshot() throws Exception {
        // Ensure AWT can access the desktop when running as a local companion process
        System.setProperty("java.awt.headless", "false");
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage img = new Robot().createScreenCapture(screen);
        // downscale for cost/privacy
        int w = Math.min(1280, img.getWidth());
        int h = (int) (img.getHeight() * (w / (double) img.getWidth()));
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        Path dir = homeQuotaService.homePath().resolve("peeks").resolve("raw");
        Files.createDirectories(dir);
        Path file = dir.resolve("screen_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".jpg");
        ImageIO.write(small, "jpg", file.toFile());
        return file;
    }
}
