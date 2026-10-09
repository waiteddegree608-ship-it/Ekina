package com.ekina.application.multiagent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Hands for channel/gateway style work via OpenClaw (龙虾).
 * Process handling mirrors {@link OpenCodeWorker}: async stdout, real timeout, stdin closed.
 */
@Component
public class OpenClawWorker {

    @Value("${ekina.multiagent.openclaw.enabled:true}")
    private boolean enabled;

    @Value("${ekina.multiagent.openclaw.webhook-url:}")
    private String webhookUrl;

    /** Vendored CLI: ./vendors/bin/openclaw.cmd */
    @Value("${ekina.multiagent.openclaw.command:./vendors/bin/openclaw.cmd}")
    private String command;

    @Value("${ekina.multiagent.openclaw.timeout-sec:120}")
    private int timeoutSec;

    @Value("${ekina.workspace-dir:.}")
    private String workspaceDir;

    @Value("${ekina.agent-home:./AgentHome}")
    private String agentHome;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public String run(AgentTask task, SharedApiBudget budget) {
        return run(task, budget, null);
    }

    public String run(AgentTask task, SharedApiBudget budget, ProcessRegistry processes) {
        if (!enabled) {
            return "OpenClaw worker disabled";
        }
        if (!budget.tryAcquire(15_000)) {
            return "API budget busy — OpenClaw task deferred";
        }
        String taskId = task.getId() == null ? "job" : task.getId();
        try {
            if (webhookUrl != null && !webhookUrl.isBlank()) {
                return runWebhook(task);
            }
            return runCli(task, processes);
        } catch (Exception e) {
            return "OpenClaw worker error: " + e.getMessage();
        } finally {
            if (processes != null) {
                processes.unregister(taskId);
            }
            budget.release();
        }
    }

    private String runWebhook(AgentTask task) throws Exception {
        String json = "{"
                + "\"source\":\"ekina-chief\","
                + "\"taskId\":\"" + esc(task.getId()) + "\","
                + "\"title\":\"" + esc(task.getTitle()) + "\","
                + "\"brief\":\"" + esc(task.getBrief()) + "\""
                + "}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(Math.max(10, timeoutSec)))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = resp.statusCode();
        String body = resp.body() == null ? "" : resp.body();
        if (code >= 200 && code < 300 && !body.isBlank()) {
            return "OpenClaw HTTP " + code + "\nRESULT_OK\n" + body;
        }
        return "OpenClaw worker error: HTTP " + code + "\n" + body;
    }

    private String runCli(AgentTask task, ProcessRegistry processes) throws Exception {
        String jobId = task.getId() == null ? "job" : task.getId();
        Path workDir = Path.of(agentHome, "workspace", "openclaw-jobs", jobId);
        Files.createDirectories(workDir);

        Path messageFile = workDir.resolve("message.md");
        String title = nullSafe(task.getTitle());
        String brief = nullSafe(task.getBrief());
        String message = "# Task from 伊姬娜 (Chief)\n\n"
                + "## Title\n" + title + "\n\n"
                + "## Brief\n" + brief + "\n\n"
                + "## Rules\n"
                + "- Reply in Chinese when summarizing.\n"
                + "- Prefer concrete actions over speculation.\n"
                + "- When finished, include a clear success line containing RESULT_OK or write RESULT.json.\n";
        Files.writeString(messageFile, message, StandardCharsets.UTF_8);

        Path resultHint = workDir.resolve("RESULT.json");
        // Placeholder so success checks know where to look; CLI may overwrite via tools.
        if (!Files.exists(resultHint)) {
            Files.writeString(resultHint, "{\n  \"status\": \"running\",\n  \"summary\": \"\"\n}\n",
                    StandardCharsets.UTF_8);
        }

        if (task.getMeta() == null) {
            task.setMeta(new java.util.LinkedHashMap<>());
        }
        task.getMeta().put("jobDir", workDir.toAbsolutePath().toString());
        task.getMeta().put("messageFile", messageFile.toAbsolutePath().toString());

        List<String> cmd = buildCommand(messageFile.toAbsolutePath().toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        String key = firstNonBlank(System.getProperty("OPENCODE_API_KEY"), System.getenv("OPENCODE_API_KEY"));
        String base = firstNonBlank(System.getProperty("OPENCODE_BASE_URL"), System.getenv("OPENCODE_BASE_URL"),
                "https://opencode.ai/zen/go/v1");
        if (key != null && !key.isBlank()) {
            env.put("OPENCODE_API_KEY", key);
        }
        env.put("OPENCODE_BASE_URL", base);
        env.put("NO_COLOR", "1");
        env.put("FORCE_COLOR", "0");

        Process proc = pb.start();
        if (processes != null) {
            processes.register(jobId, proc);
        }
        try {
            proc.getOutputStream().close();
        } catch (Exception ignored) {
        }

        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (out) {
                        if (out.length() < 80_000) {
                            out.append(line).append('\n');
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }, "openclaw-stdout-" + jobId);
        reader.setDaemon(true);
        reader.start();

        int waitSec = Math.max(45, timeoutSec);
        boolean finished = proc.waitFor(waitSec, TimeUnit.SECONDS);
        if (!finished) {
            killProcessTree(proc);
            reader.join(2000);
            String partial;
            synchronized (out) {
                partial = out.toString();
            }
            writeBridge(workDir, -1, true, partial);
            return "OpenClaw CLI timed out after " + waitSec + "s.\n"
                    + "jobDir=" + workDir.toAbsolutePath() + "\n"
                    + "Partial stdout:\n" + partial;
        }
        reader.join(3000);
        int code = proc.exitValue();
        String stdout;
        synchronized (out) {
            stdout = out.toString();
        }

        // Prefer structured RESULT.json if CLI or tools wrote a terminal status
        Path resultJson = workDir.resolve("RESULT.json");
        boolean hasResultJson = false;
        String resultBody = "";
        if (Files.isRegularFile(resultJson)) {
            resultBody = Files.readString(resultJson, StandardCharsets.UTF_8);
            hasResultJson = resultBody.contains("\"succeeded\"")
                    || resultBody.contains("\"failed\"")
                    || resultBody.contains("RESULT_OK");
            // Ignore placeholder "running"
            if (resultBody.contains("\"status\": \"running\"") && !resultBody.contains("RESULT_OK")) {
                hasResultJson = false;
            }
        }
        boolean stdoutOk = stdout.contains("RESULT_OK")
                || stdout.contains("\"stopReason\":\"stop\"")
                || stdout.contains("stopReason=stop");
        if (code == 0 && !hasResultJson && stdoutOk) {
            Files.writeString(resultJson, "{\n  \"status\": \"succeeded\",\n  \"summary\": "
                    + jsonQuote(tail(stdout, 1500)) + ",\n  \"exitCode\": 0,\n  \"finishedAt\": \""
                    + Instant.now() + "\"\n}\n", StandardCharsets.UTF_8);
            hasResultJson = true;
            resultBody = Files.readString(resultJson, StandardCharsets.UTF_8);
        }

        writeBridge(workDir, code, false, stdout);
        StringBuilder ret = new StringBuilder();
        ret.append("OpenClaw CLI exit=").append(code).append('\n');
        ret.append("jobDir=").append(workDir.toAbsolutePath()).append('\n');
        if (hasResultJson) {
            ret.append("RESULT.json:\n").append(resultBody).append('\n');
        }
        if (stdoutOk || hasResultJson) {
            ret.append("RESULT_OK\n");
        }
        ret.append("--- stdout ---\n").append(stdout);
        if (code != 0) {
            ret.append("\n(cmd=").append(cmd).append(')');
        }
        return ret.toString();
    }

    private List<String> buildCommand(String messageFile) {
        String cmdPath = resolveCommand();
        List<String> inner = new ArrayList<>();
        // Prefer launching node + entry directly (avoids fragile .cmd argv quoting)
        Path node24 = Path.of(workspaceDir, "vendors", "node24", "node.exe");
        Path entry = Path.of(workspaceDir, "vendors", "node_modules", "openclaw", "openclaw.mjs");
        if (Files.isRegularFile(node24) && Files.isRegularFile(entry)) {
            List<String> cmd = new ArrayList<>();
            cmd.add(node24.toAbsolutePath().toString());
            cmd.add(entry.toAbsolutePath().toString());
            cmd.add("agent");
            cmd.add("--local");
            cmd.add("--agent");
            cmd.add("main");
            cmd.add("--json");
            cmd.add("--message-file");
            cmd.add(messageFile);
            cmd.add("--timeout");
            cmd.add(String.valueOf(Math.max(60, timeoutSec)));
            return cmd;
        }

        inner.add("agent");
        inner.add("--local");
        inner.add("--agent");
        inner.add("main");
        inner.add("--json");
        inner.add("--message-file");
        inner.add(messageFile);
        inner.add("--timeout");
        inner.add(String.valueOf(Math.max(60, timeoutSec)));

        String lower = cmdPath.toLowerCase();
        if (lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            List<String> cmd = new ArrayList<>();
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add(cmdPath);
            cmd.addAll(inner);
            return cmd;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(cmdPath);
        cmd.addAll(inner);
        return cmd;
    }

    private void writeBridge(Path workDir, int exitCode, boolean timedOut, String stdout) {
        try {
            String bridge = "# OpenClaw → Ekina report\n\n"
                    + "- jobDir: `" + workDir.toAbsolutePath() + "`\n"
                    + "- exit: " + exitCode + (timedOut ? " (TIMEOUT)" : "") + "\n\n"
                    + "## Stdout (tail)\n```\n" + tail(stdout, 3000) + "\n```\n";
            Files.writeString(workDir.resolve("bridge.md"), bridge, StandardCharsets.UTF_8);
            Files.writeString(workDir.resolve("stdout.txt"), nullSafe(stdout), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private String resolveCommand() {
        Path configured = Path.of(command);
        if (Files.isRegularFile(configured)) {
            return configured.toAbsolutePath().toString();
        }
        Path vendored = Path.of(workspaceDir, "vendors", "bin", "openclaw.cmd");
        if (Files.isRegularFile(vendored)) {
            return vendored.toAbsolutePath().toString();
        }
        return command;
    }

    static void killProcessTree(Process p) {
        if (p == null) return;
        try {
            long pid = p.pid();
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid))
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(5, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
        }
        try {
            p.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return "";
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static String jsonQuote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(s.length() - max);
    }
}
