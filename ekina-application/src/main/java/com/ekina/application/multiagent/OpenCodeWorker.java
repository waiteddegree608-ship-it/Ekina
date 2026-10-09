package com.ekina.application.multiagent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CODER hands: OpenCode CLI (OpenCode Go, not Zen-only).
 * Job directory is the shared workspace so Chief can read generated files after the run.
 */
@Component
public class OpenCodeWorker {

    @Value("${ekina.multiagent.opencode.enabled:true}")
    private boolean enabled;

    @Value("${ekina.multiagent.opencode.command:./vendors/bin/opencode.cmd}")
    private String command;

    @Value("${ekina.multiagent.opencode.args-template:}")
    private String argsTemplate;

    /** Wall-clock cap for the OpenCode process. 0 or negative = wait until CLI exits (no Ekina kill). */
    @Value("${ekina.multiagent.opencode.timeout-sec:0}")
    private int timeoutSec;

    @Value("${ekina.multiagent.opencode.workdir:./sandbox/opencode-jobs}")
    private String workdir;

    /** OpenCode Go model id, e.g. opencode-go/qwen3.7-plus or opencode-go/kimi-k2.7-code */
    @Value("${ekina.multiagent.opencode.model:opencode-go/qwen3.7-plus}")
    private String model;

    @Value("${ekina.reviews.model:opencode-go/gpt-6-luna}")
    private String reviewModel;

    @Value("${ekina.workspace-dir:.}")
    private String workspaceDir;

    @Value("${ekina.agent-home:./AgentHome}")
    private String agentHome;

    public String run(AgentTask task, SharedApiBudget budget) {
        return run(task, budget, null);
    }

    public String run(AgentTask task, SharedApiBudget budget, ProcessRegistry processes) {
        if (!enabled) {
            return "OpenCode worker disabled (ekina.multiagent.opencode.enabled=false)";
        }
        if (!budget.tryAcquire(30_000)) {
            return "API budget busy — OpenCode task deferred (shared Go key concurrency full)";
        }
        Process p = null;
        Path dir = null;
        String jobId = task.getId() == null ? "job" : task.getId();
        try {
            dir = Path.of(workdir, jobId);
            Files.createDirectories(dir);

            // Deliverables land in AgentHome so Chief/user see real files (not only sandbox copies)
            Path deliverDir = Path.of(agentHome, "workspace", "opencode-deliverables", jobId);
            Files.createDirectories(deliverDir);

            Path promptFile = dir.resolve("task.md");
            String brief = nullToEmpty(task.getBrief());
            String title = nullToEmpty(task.getTitle());
            boolean reviewOnly = task.getMeta()!=null && Boolean.TRUE.equals(task.getMeta().get("reviewOnly"));
            String selectedModel = reviewOnly ? reviewModel : model;
            if(reviewOnly && task.getMeta().get("reviewRecoveryModel") instanceof String recoveryModel
                    && java.util.Set.of("opencode-go/gpt-6-luna","opencode-go/glm-5.3-flash").contains(recoveryModel))selectedModel=recoveryModel;
            boolean scribe = task.getAssignee() == AgentRole.SCRIBE;
            String prompt = scribe
                    ? "# Task from 伊姬娜 → 排版工人 (SCRIBE)\n\n"
                    + "You are Ekina's typesetting worker (not a chat contact). Use official venue kits under templates/venues. "
                    + "Never emit a bare article class pretending to be a journal. "
                    + "Identify the venue in VENUE.md and the PDF running header. "
                    + "Polish English, figures (booktabs, colorblind palettes, one claim per figure), and captions. "
                    + "If figures/cover.jpg exists, page 1 MUST be a full-page cover via "
                    + "\\\\onecolumn\\\\includepdf[pages=1,pagecommand={\\\\thispagestyle{empty}}]{figures/cover}\\\\twocolumn. "
                    + "NEVER \\\\includegraphics[width=\\\\paperwidth] inside IEEEtran twocolumn (cover collapses to the right half). "
                    + "Re-run scripts/make_figures.py; do not hand-edit a single plotted number. "
                    + "Do not invent numbers. Write RESULT.md with venue id + paths + a 验收 checklist.\n\n"
                    : "# Task from 伊姬娜 (Chief)\n\n"
                    + "You are the HANDS. Chief only dispatches. Write RESULT.md in the deliverable dir with numbers and paths so she can recordLabEntry.\n\n";
            prompt += ""
                    + "## Title\n" + title + "\n\n"
                    + "## Brief\n" + brief + "\n\n"
                    + "## Paths\n"
                    + "- Job workspace (write code/temp here): `" + dir.toAbsolutePath() + "`\n"
                    + "- Deliverables (copy final user-facing files here): `" + deliverDir.toAbsolutePath() + "`\n"
                    + "- If brief mentions AgentHome paths, ALSO copy those files into the Deliverables folder above.\n\n"
                    + "## Rules\n"
                    + "- Prefer concrete shell/file actions over endless web browsing.\n"
                    + "- Do NOT spam WebFetch on github.com/search or guessed repo URLs; GitHub rate-limits (429) and wrong paths (404) waste the run.\n"
                    + "- After a few useful fetches (or repeated 429/404), STOP fetching and WRITE outputs from what you have.\n"
                    + "- Write RESULT.md + RESULT.json BEFORE optional polish. Copy artifacts into Deliverables.\n"
                    + "- When done, write BOTH:\n"
                    + "  1. `RESULT.md` — short Chinese summary; first line should include SUCCESS if successful.\n"
                    + "  2. `RESULT.json` — `{\"status\":\"succeeded|failed\",\"summary\":\"...\",\"artifacts\":[\"rel/paths\"]}`\n"
                    + "- status=succeeded only if you actually created the requested outputs.\n";
            Files.writeString(promptFile, prompt, StandardCharsets.UTF_8);
            if (reviewOnly) {
                // OpenCode treats the containing Git worktree as inside its boundary.
                // Give each review its own repository so external_directory=deny is meaningful.
                Process init=new ProcessBuilder("git","init","--quiet",dir.toAbsolutePath().toString()).redirectErrorStream(true).start();
                if(!init.waitFor(15,TimeUnit.SECONDS)){init.destroyForcibly();throw new IllegalStateException("综述工作目录隔离超时");}
                if(init.exitValue()!=0)throw new IllegalStateException("综述工作目录隔离失败");
                prompt = "# Ekina 文献综述任务\n\n" + title + "\n\n" + brief
                        + "\n\n只在当前工作目录写输出。inputs/ 是只读前置材料，不得修改、覆盖或删除；修订必须写到当前目录的新产物。可以用 websearch/webfetch 查公开文献，禁止 shell/SSH/子代理/外部写入。"
                        + "不要执行旧实验或训练。遵守当前任务的产物要求。"+reviewResultInstruction(Boolean.TRUE.equals(task.getMeta().get("figureManifestFromPlan")))
                        + "创建文件成功不代表综述已通过科学审查。资料不足时明确写缺口，不编造来源。";
                Files.writeString(promptFile, prompt, StandardCharsets.UTF_8);
                copyReviewInputs(task, dir);
            }

            // Mirror task into AgentHome so Chief/UI can always open it
            Path reportDir = Path.of(agentHome, "workspace", "opencode-reports", jobId);
            Files.createDirectories(reportDir);
            Files.writeString(reportDir.resolve("task.md"), prompt, StandardCharsets.UTF_8);

            if (task.getMeta() == null) {
                task.setMeta(new java.util.LinkedHashMap<>());
            }
            task.getMeta().put("jobDir", dir.toAbsolutePath().toString());
            task.getMeta().put("reportDir", reportDir.toAbsolutePath().toString());
            task.getMeta().put("deliverDir", deliverDir.toAbsolutePath().toString());
            task.getMeta().put("model", selectedModel);

            String cmdPath = resolveCommand();
            List<String> cmd = buildCommand(cmdPath, dir.toString(), promptFile.toString(), title, brief, reviewOnly, selectedModel);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            // Always inject Go credentials (overwrite empty)
            String key = firstNonBlank(System.getProperty("OPENCODE_API_KEY"), System.getenv("OPENCODE_API_KEY"));
            String base = firstNonBlank(System.getProperty("OPENCODE_BASE_URL"), System.getenv("OPENCODE_BASE_URL"),
                    "https://opencode.ai/zen/go/v1");
            if (key != null && !key.isBlank()) {
                env.put("OPENCODE_API_KEY", key);
            }
            env.put("OPENCODE_BASE_URL", base);
            if (reviewOnly) {
                env.put("OPENCODE_CONFIG_CONTENT", task.getMeta().get("paperFigureOutput") instanceof String output?
                    paperFigureConfig(selectedModel,output):reviewConfig(selectedModel,task.getMeta().containsKey("auditSchema"),"illustration".equals(task.getMeta().get("reviewStage")),Boolean.TRUE.equals(task.getMeta().get("reviewSynthesisOnly"))));
                env.put("OPENCODE_ENABLE_EXA", "true");
            }
            // Reduce interactive noise
            env.put("NO_COLOR", "1");
            env.put("FORCE_COLOR", "0");

            p = pb.start();
            if (processes != null) {
                processes.register(jobId, p);
            }
            final Process proc = p;
            // Critical on Windows: close stdin or some CLIs block waiting for interactive input
            try {
                proc.getOutputStream().close();
            } catch (Exception ignored) {
            }
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                     var eventLog = Files.newBufferedWriter(promptFile.getParent().resolve("events.jsonl"), StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        eventLog.write(line); eventLog.newLine(); eventLog.flush();
                        synchronized (out) {
                            if (out.length() < 80_000) {
                                out.append(line).append('\n');
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "opencode-stdout-" + jobId);
            reader.setDaemon(true);
            reader.start();

            // Default: no Ekina-side wall clock — wait until OpenCode exits.
            // Optional emergency cap: ekina.multiagent.opencode.timeout-sec > 0.
            boolean finished = true;
            int taskTimeout = reviewOnly ? 1800 : timeoutSec;
            if (taskTimeout <= 0) {
                proc.waitFor();
            } else {
                int waitSec = Math.max(45, taskTimeout);
                finished = proc.waitFor(waitSec, TimeUnit.SECONDS);
                if (!finished) {
                    killProcessTree(proc);
                    reader.join(2000);
                    String partial;
                    synchronized (out) {
                        partial = out.toString();
                    }
                    String artifacts = collectArtifacts(dir, reportDir, deliverDir, partial, -1, true, selectedModel);
                    return "OpenCode timed out after " + waitSec + "s.\n"
                            + "jobDir=" + dir.toAbsolutePath() + "\n"
                            + artifacts + "\nPartial stdout:\n" + partial;
                }
            }
            reader.join(3000);
            int code = proc.exitValue();
            String stdout;
            synchronized (out) {
                stdout = out.toString();
            }
            ensureResultJson(dir, code, stdout);
            syncDeliverables(dir, deliverDir);
            String artifacts = collectArtifacts(dir, reportDir, deliverDir, stdout, code, false, selectedModel);
            boolean hasResult = hasSucceededResultJson(dir.resolve("RESULT.json"))
                    || (!reviewOnly && !hasFailedResultJson(dir.resolve("RESULT.json")) && Files.isRegularFile(dir.resolve("RESULT.md")));
            boolean inputsUnchanged = !reviewOnly || reviewInputsUnchanged(task,dir);
            hasResult = hasResult && inputsUnchanged;
            String marker = (code == 0 && hasResult) ? "RESULT_OK\n" : "";
            return "OpenCode exit=" + code + "\n"
                    + marker
                    + (inputsUnchanged ? "" : "INPUTS_MODIFIED: 前置材料被修改或缺失，审查不能通过。\n")
                    + "model=" + selectedModel + "\n"
                    + "jobDir=" + dir.toAbsolutePath() + "\n"
                    + "deliverDir=" + deliverDir.toAbsolutePath() + "\n"
                    + artifacts + "\n--- stdout ---\n" + stdout
                    + (code != 0 ? "\n(cmd=" + cmd + ")" : "");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (msg.contains("Cannot run program") || msg.toLowerCase().contains("error=2")) {
                return "OpenCode CLI 未找到。\n"
                        + "一体包安装: powershell -File scripts/install-agents.ps1\n"
                        + "应出现: " + workspaceDir + "/vendors/bin/opencode.cmd\n"
                        + "任务: " + task.getTitle();
            }
            return "OpenCode worker error: " + msg;
        } finally {
            if (p != null && p.isAlive()) {
                killProcessTree(p);
            }
            if (processes != null) {
                processes.unregister(jobId);
            }
            budget.release();
        }
    }

    /** Absolute job directory for a task id (shared with Chief). */
    public Path jobDirFor(String taskId) {
        return Path.of(workdir, taskId == null ? "job" : taskId);
    }

    public Path reportDirFor(String taskId) {
        return Path.of(agentHome, "workspace", "opencode-reports", taskId == null ? "job" : taskId);
    }

    public Path deliverDirFor(String taskId) {
        return Path.of(agentHome, "workspace", "opencode-deliverables", taskId == null ? "job" : taskId);
    }

    private String collectArtifacts(Path jobDir, Path reportDir, Path deliverDir, String stdout, int exitCode,
                                    boolean timedOut, String selectedModel) throws Exception {
        List<String> files = listFiles(jobDir, 40);
        List<String> delivered = listFiles(deliverDir, 40);
        StringBuilder summary = new StringBuilder();
        summary.append("timedOut=").append(timedOut).append('\n');
        summary.append("exitCode=").append(exitCode).append('\n');
        summary.append("finishedAt=").append(Instant.now()).append('\n');
        summary.append("files:\n");
        for (String f : files) {
            summary.append("  - ").append(f).append('\n');
        }
        if (!delivered.isEmpty()) {
            summary.append("deliverables:\n");
            for (String f : delivered) {
                summary.append("  - ").append(f).append('\n');
            }
        }
        // Prefer RESULT.md content if present
        Path resultMd = jobDir.resolve("RESULT.md");
        if (Files.isRegularFile(resultMd)) {
            String body = Files.readString(resultMd, StandardCharsets.UTF_8);
            if (body.length() > 4000) body = body.substring(0, 4000) + "\n…";
            summary.append("RESULT.md:\n").append(body).append('\n');
        }
        Path resultJson = jobDir.resolve("RESULT.json");
        if (Files.isRegularFile(resultJson)) {
            String body = Files.readString(resultJson, StandardCharsets.UTF_8);
            if (body.length() > 2000) body = body.substring(0, 2000) + "\n…";
            summary.append("RESULT.json:\n").append(body).append('\n');
        }
        // Write bridge report for Chief
        Files.createDirectories(reportDir);
        String bridge = "# OpenCode → Ekina report\n\n"
                + "- jobDir: `" + jobDir.toAbsolutePath() + "`\n"
                + "- deliverDir: `" + deliverDir.toAbsolutePath() + "`\n"
                + "- exit: " + exitCode + (timedOut ? " (TIMEOUT)" : "") + "\n"
                + "- model: `" + selectedModel + "`\n\n"
                + "## Files\n"
                + files.stream().map(f -> "- `" + f + "`").collect(Collectors.joining("\n"))
                + "\n\n## Deliverables\n"
                + (delivered.isEmpty() ? "- (none)" : delivered.stream().map(f -> "- `" + f + "`").collect(Collectors.joining("\n")))
                + "\n\n## Stdout (tail)\n```\n"
                + tail(stdout, 3000)
                + "\n```\n";
        if (Files.isRegularFile(resultMd)) {
            bridge += "\n## RESULT.md\n\n" + Files.readString(resultMd, StandardCharsets.UTF_8) + "\n";
        }
        Files.writeString(reportDir.resolve("bridge.md"), bridge, StandardCharsets.UTF_8);
        Files.writeString(reportDir.resolve("stdout.txt"), nullToEmpty(stdout), StandardCharsets.UTF_8);
        writeUsage(jobDir,reportDir,selectedModel);

        return "artifacts:\n" + summary
                + "reportDir=" + reportDir.toAbsolutePath() + "\n"
                + "deliverDir=" + deliverDir.toAbsolutePath() + "\n";
    }

    /** Preserve provider-reported usage; this is an estimate, not a billing receipt. */
    private void writeUsage(Path jobDir,Path reportDir,String selectedModel) throws Exception {
        Path events=jobDir.resolve("events.jsonl");if(!Files.isRegularFile(events))return;
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        long input=0,output=0,reasoning=0,cacheRead=0,cacheWrite=0;double cost=0;int steps=0;boolean costReported=false;
        var seen=new java.util.HashSet<String>();
        try(var lines=Files.lines(events)) {
            for(String line:(Iterable<String>)lines::iterator) try {
                var event=mapper.readTree(line);if(!"step_finish".equals(event.path("type").asText()))continue;
                var part=event.path("part");String id=part.path("id").asText();
                if(!id.isBlank()&&!seen.add(id))continue;
                var tokens=part.path("tokens");input+=tokens.path("input").asLong();output+=tokens.path("output").asLong();reasoning+=tokens.path("reasoning").asLong();
                cacheRead+=tokens.path("cache").path("read").asLong();cacheWrite+=tokens.path("cache").path("write").asLong();
                if(part.path("cost").isNumber()){cost+=part.path("cost").asDouble();costReported=true;}steps++;
            }catch(Exception ignored){}
        }
        Map<String,Object> usage=new java.util.LinkedHashMap<>();usage.put("model",selectedModel);usage.put("steps",steps);
        usage.put("inputTokens",input);usage.put("outputTokens",output);usage.put("reasoningTokens",reasoning);usage.put("cacheReadTokens",cacheRead);usage.put("cacheWriteTokens",cacheWrite);
        usage.put("providerEstimatedCostUsd",costReported?cost:null);usage.put("billingVerified",false);
        Files.writeString(reportDir.resolve("usage.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(usage));
    }

    /** If CLI forgot RESULT.json but left RESULT.md + exit 0, synthesize a structured result. */
    private void ensureResultJson(Path jobDir, int exitCode, String stdout) {
        try {
            Path resultJson = jobDir.resolve("RESULT.json");
            if (hasSucceededResultJson(resultJson) || hasFailedResultJson(resultJson)) {
                return;
            }
            Path resultMd = jobDir.resolve("RESULT.md");
            boolean hasMd = Files.isRegularFile(resultMd);
            String summary = hasMd
                    ? Files.readString(resultMd, StandardCharsets.UTF_8)
                    : tail(stdout, 800);
            String status = (exitCode == 0 && hasMd) ? "succeeded" : (exitCode == 0 ? "failed" : "failed");
            if (exitCode == 0 && !hasMd) {
                // exit=0 with no RESULT.md is a false success — mark failed for orchestrator
                summary = "OpenCode exited 0 but produced no RESULT.md. stdout_tail=" + tail(stdout, 400);
            }
            String json = "{\n"
                    + "  \"status\": \"" + status + "\",\n"
                    + "  \"summary\": " + jsonQuote(summary) + ",\n"
                    + "  \"exitCode\": " + exitCode + ",\n"
                    + "  \"finishedAt\": \"" + Instant.now() + "\"\n"
                    + "}\n";
            Files.writeString(resultJson, json, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Copy non-meta artifacts from jobDir into AgentHome deliverables. */
    private void syncDeliverables(Path jobDir, Path deliverDir) {
        try {
            Files.createDirectories(deliverDir);
            if (!Files.isDirectory(jobDir)) return;
            try (Stream<Path> s = Files.list(jobDir)) {
                for (Path p : s.collect(Collectors.toList())) {
                    String name = p.getFileName().toString();
                      if ("task.md".equalsIgnoreCase(name)
                              || ".git".equalsIgnoreCase(name)
                              || "events.jsonl".equalsIgnoreCase(name)
                            || "RESULT.md".equalsIgnoreCase(name)
                            || "RESULT.json".equalsIgnoreCase(name)
                            || "bridge.md".equalsIgnoreCase(name)
                            || "stdout.txt".equalsIgnoreCase(name)) {
                        continue;
                    }
                    Path dest = deliverDir.resolve(name);
                    if (Files.isDirectory(p)) {
                        copyTree(p, dest);
                    } else if (Files.isRegularFile(p)) {
                        Files.copy(p, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            // Also mirror RESULT.* into deliverDir for Chief convenience
            for (String n : List.of("RESULT.md", "RESULT.json")) {
                Path src = jobDir.resolve(n);
                if (Files.isRegularFile(src)) {
                    Files.copy(src, deliverDir.resolve(n), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void copyTree(Path src, Path dest) throws Exception {
        Files.walk(src).forEach(p -> {
            try {
                Path rel = src.relativize(p);
                Path target = dest.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception ignored) {
            }
        });
    }

    static boolean hasSucceededResultJson(Path resultJson) {
        try {
            if (resultJson == null || !Files.isRegularFile(resultJson)) return false;
            var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(resultJson, StandardCharsets.UTF_8));
            return "succeeded".equals(body.path("status").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasFailedResultJson(Path resultJson) {
        try {
            if (resultJson == null || !Files.isRegularFile(resultJson)) return false;
            String body = Files.readString(resultJson, StandardCharsets.UTF_8);
            return body.contains("\"failed\"");
        } catch (Exception e) {
            return false;
        }
    }

    private static String jsonQuote(String s) {
        if (s == null) return "\"\"";
        String t = s.length() > 1500 ? s.substring(0, 1500) : s;
        return "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static List<String> listFiles(Path dir, int max) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir, 3)) {
            return s.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .limit(max)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of("(list failed: " + e.getMessage() + ")");
        }
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(s.length() - max);
    }

    private String resolveCommand() {
        Path configured = Path.of(command);
        if (Files.isRegularFile(configured)) {
            return configured.toAbsolutePath().toString();
        }
        Path vendored = Path.of(workspaceDir, "vendors", "bin", "opencode.cmd");
        if (Files.isRegularFile(vendored)) {
            return vendored.toAbsolutePath().toString();
        }
        Path vendored2 = Path.of(workspaceDir, "vendors", "node_modules", ".bin", "opencode.cmd");
        if (Files.isRegularFile(vendored2)) {
            return vendored2.toAbsolutePath().toString();
        }
        return command;
    }

    private List<String> buildCommand(String cmdPath, String dir, String promptFile, String title, String brief, boolean reviewOnly, String selectedModel) {
        // Keep the CLI argv message ASCII-only: Windows ProcessBuilder often mangles Chinese in argv.
        // Full Chinese brief lives in UTF-8 task.md which OpenCode reads from disk.
        String message = "You are Ekina worker (OpenCode Go). "
                + "Read and execute the UTF-8 task file exactly: " + promptFile
                + " | Work ONLY under: " + dir
                + " | Title: " + (title == null ? "" : title.replaceAll("[^\\x20-\\x7E]", " "))
                + " | Create files here, run needed commands, write RESULT.md when done.";

        List<String> inner = new ArrayList<>();
        if (!reviewOnly && argsTemplate != null && !argsTemplate.isBlank()) {
            String expanded = argsTemplate
                    .replace("{prompt}", message.replace("\"", "'"))
                    .replace("{workdir}", dir)
                    .replace("{promptFile}", promptFile)
                    .replace("{model}", model);
            for (String part : expanded.trim().split("\\s+")) {
                if (!part.isBlank()) inner.add(part);
            }
        } else {
            // --auto: non-interactive permission approve (without this, CLI can hang forever)
            // --dir: pin workspace to job folder so Chief can read outputs
            // --pure: less plugin noise
            inner.add("run");
            if (!reviewOnly) inner.add("--auto");
            inner.add("--pure");
            inner.add("--dir");
            inner.add(dir);
            inner.add("-m");
            inner.add(selectedModel == null || selectedModel.isBlank() ? "opencode-go/qwen3.7-plus" : selectedModel.trim());
            if (reviewOnly) { inner.add("--agent");inner.add("literature-review");inner.add("--format");inner.add("json"); }
            inner.add(message);
        }

        // Prefer launching opencode.exe directly (avoids cmd.exe quoting/hang quirks)
        String resolved = cmdPath;
        try {
            Path c = Path.of(cmdPath);
            if (c.getFileName().toString().equalsIgnoreCase("opencode.cmd")) {
                Path exe = Path.of(workspaceDir, "vendors", "node_modules", "opencode-ai", "bin", "opencode.exe");
                if (Files.isRegularFile(exe)) {
                    resolved = exe.toAbsolutePath().toString();
                }
            }
        } catch (Exception ignored) {
        }
        String lower = resolved.toLowerCase();
        if (lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            List<String> cmd = new ArrayList<>();
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add(resolved);
            cmd.addAll(inner);
            return cmd;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(resolved);
        cmd.addAll(inner);
        return cmd;
    }

    static String reviewConfig(String selectedModel) throws Exception {
        return reviewConfig(selectedModel,false);
    }
    @SuppressWarnings("unchecked") static String paperFigureConfig(String model,String output)throws Exception {
        if(!java.util.Set.of("paper-plan.json","paper-selections.json","paper-audit.json","fulltext-sources.json","fulltext-plan.json","fulltext-reading.json").contains(output))throw new IllegalArgumentException("Invalid paper-figure output");
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var config=mapper.readValue(reviewConfig(model,false,false),Map.class);
        var permissions=(Map<String,Object>)config.get("permission");Map<String,String> edits=new java.util.LinkedHashMap<>();edits.put("*","deny");
        for(String name:List.of(output,"RESULT.md","RESULT.json"))for(String pattern:List.of(name,"*/"+name,"*\\"+name))edits.put(pattern,"allow");
        for(String pattern:List.of("inputs/*","*/inputs/*","inputs\\*","*\\inputs\\*"))edits.put(pattern,"deny");
        permissions.put("edit",edits);permissions.put("write",edits);
        if(!java.util.Set.of("paper-plan.json","fulltext-plan.json","fulltext-sources.json").contains(output)){permissions.put("webfetch","deny");permissions.put("websearch","deny");}
        var agent=(Map<String,Object>)((Map<?,?>)config.get("agent")).get("literature-review");agent.put("permission",permissions);
        return mapper.writeValueAsString(config);
    }
    static String reviewResultInstruction(boolean softwareManifest) {
        return softwareManifest?"最后只写纯文本 RESULT.md，首行SUCCESS或FAILED，说明实际产物，然后立即结束。不要写JSON文件；图注清单和结构化执行结果由软件根据原计划与真实执行结果生成。":
                "最后在当前目录写 RESULT.md 和 RESULT.json={\"status\":\"succeeded|failed\",\"summary\":\"...\",\"artifacts\":[]}。";
    }
    static String reviewConfig(String selectedModel,boolean audit) throws Exception {
        return reviewConfig(selectedModel,audit,false);
    }
    static String reviewConfig(String selectedModel,boolean audit,boolean illustration) throws Exception {
        return reviewConfig(selectedModel,audit,illustration,false);
    }
    static String reviewConfig(String selectedModel,boolean audit,boolean illustration,boolean synthesisOnly) throws Exception {
        if (!List.of("opencode-go/gpt-6-luna", "opencode-go/deepseek-v4.1-flash", "opencode-go/glm-5.3-flash").contains(selectedModel))
            throw new IllegalArgumentException("综述仅允许已配置的低价模型");
        Map<String,Object> permissions = new java.util.LinkedHashMap<>();
        permissions.put("*", "deny");
          for (String allowed : List.of("read", "glob", "grep", "list", "edit", "write", "webfetch", "websearch", "todowrite", "todoread")) permissions.put(allowed, "allow");
          if(synthesisOnly&&!audit)for(String web:List.of("webfetch","websearch"))permissions.put(web,"deny");
        Map<String,String> edits=new java.util.LinkedHashMap<>();edits.put("*",audit?"deny":"allow");
        if(audit)for(String name:List.of("audit.md","audit.json","RESULT.md","RESULT.json")) {
            edits.put(name,"allow");edits.put("*/"+name,"allow");edits.put("*\\"+name,"allow");
        }
        for(String pattern:List.of("inputs/*","*/inputs/*","inputs\\*","*\\inputs\\*"))edits.put(pattern,"deny");
        permissions.put("edit",edits);permissions.put("write",edits);
        permissions.put("external_directory", "deny");permissions.put("bash", "deny");permissions.put("task", "deny");
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "model", selectedModel, "small_model", selectedModel, "share", "disabled", "autoupdate", false,
                "agent", Map.of("literature-review", Map.of("description", "Bounded source-grounded literature review", "mode", "primary", "model", selectedModel, "steps", 36, "permission", permissions,
                        "options",(illustration||(synthesisOnly&&!audit))&&List.of("opencode-go/glm-5.3-flash","opencode-go/gpt-6-luna").contains(selectedModel)?Map.of("reasoningEffort","low"):Map.of())),
                "permission", permissions));
    }

    static boolean reviewInputsUnchanged(AgentTask task,Path jobDir) throws Exception {
        if(!(task.getMeta().get("reviewInputs") instanceof Map<?,?> inputs))return true;
        for(var entry:inputs.entrySet()) {
            Path original=Path.of(entry.getValue().toString()),copy=jobDir.resolve("inputs").resolve(entry.getKey().toString());
            if(!Files.isRegularFile(original)||!Files.isRegularFile(copy)||Files.mismatch(original,copy)!=-1)return false;
        }
        return true;
    }

    private void copyReviewInputs(AgentTask task, Path jobDir) throws Exception {
        if (!(task.getMeta().get("reviewInputs") instanceof Map<?,?> inputs)) return;
        Path allowed = Path.of(agentHome, "workspace", "opencode-deliverables").toRealPath();
        Path destination = jobDir.resolve("inputs");Files.createDirectories(destination);
        for (var entry : inputs.entrySet()) {
            String name = entry.getKey().toString();
            if (!name.matches("[a-zA-Z0-9_-][a-zA-Z0-9_.-]*")) throw new IllegalArgumentException("无效综述输入文件名");
            Path source = Path.of(entry.getValue().toString()).toRealPath();
            if (!source.startsWith(allowed) || !Files.isRegularFile(source) || Files.size(source)>4_000_000)
                throw new IllegalArgumentException("综述输入文件越界或过大");
            Files.copy(source,destination.resolve(name),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void killProcessTree(Process p) {
        if (p == null) return;
        try {
            long pid = p.pid();
            // Windows: kill tree
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return "";
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }
}
