package com.ekina.application.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Attempts a real upstream baseline smoke (help/compile/import), extracts README train cmds
 * and paper-claimed numbers. Does NOT invent matching metrics.
 */
@Service
public class BaselineRunner {

    private static final Pattern PY_CMD = Pattern.compile(
            "(?i)(?:^|[\\s`\"'])((?:python3?|py)\\s+[\\w./\\\\-]+\\.py(?:\\s+[^`\\n\"']*)?)");
    private static final Pattern METRIC_CLAIM = Pattern.compile(
            "(?i)(dice|iou|miou|accuracy|acc|f1|bleu|rouge|fid|psnr|auc|ap)\\s*[:=]?\\s*"
                    + "(\\d{1,3}(?:\\.\\d+)?)\\s*%?");

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${ekina.paper.baseline.timeout-sec:90}")
    private int timeoutSec;

    @Value("${ekina.paper.baseline.enabled:true}")
    private boolean enabled;

    public Map<String, Object> run(Path paperRoot, Map<String, Object> reproReport) throws Exception {
        Path reproDir = paperRoot.resolve("repro");
        Path upstream = reproDir.resolve("upstream");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("startedAt", Instant.now().toString());
        out.put("enabled", enabled);

        if (!enabled) {
            out.put("status", "skipped_disabled");
            out.put("ok", false);
            write(reproDir, out);
            return out;
        }
        if (!Files.isDirectory(upstream)) {
            out.put("status", "skipped_no_upstream");
            out.put("ok", false);
            write(reproDir, out);
            return out;
        }

        Object invObj = reproReport == null ? null : reproReport.get("inventory");
        @SuppressWarnings("unchecked")
        Map<String, Object> inventory = invObj instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        String entry = inventory.get("suggestedEntry") == null
                ? null : String.valueOf(inventory.get("suggestedEntry"));
        out.put("suggestedEntry", entry);
        out.put("repo", reproReport == null ? null : reproReport.get("repo"));

        List<String> readmeCmds = extractReadmeCommands(upstream);
        out.put("readmeTrainCommands", readmeCmds);
        Files.writeString(reproDir.resolve("baseline_cmds.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(readmeCmds),
                StandardCharsets.UTF_8);

        Map<String, Object> claims = extractClaimedMetrics(paperRoot);
        out.put("claimedMetrics", claims);
        workspaceWrite(reproDir.resolve("claimed_metrics.json"), claims);

        // 1) syntax check
        Map<String, Object> compile = Map.of("skipped", true);
        if (entry != null && entry.endsWith(".py")) {
            compile = runHost(upstream, List.of("python", "-m", "py_compile", entry),
                    Math.min(60, timeoutSec));
        }
        out.put("pyCompile", compile);

        // 2) --help / -h smoke if compile ok-ish
        Map<String, Object> help = Map.of("skipped", true);
        if (entry != null && entry.endsWith(".py") && Boolean.TRUE.equals(compile.get("ok"))) {
            help = runHost(upstream, List.of("python", entry, "--help"), Math.min(60, timeoutSec));
            if (!Boolean.TRUE.equals(help.get("ok"))) {
                Map<String, Object> help2 = runHost(upstream, List.of("python", entry, "-h"),
                        Math.min(60, timeoutSec));
                if (Boolean.TRUE.equals(help2.get("ok"))
                        || containsUsage(str(help2.get("output")))) {
                    help = help2;
                    help.put("ok", true);
                    help.put("note", "accepted via -h/usage text");
                } else if (containsUsage(str(help.get("output")))) {
                    help.put("ok", true);
                    help.put("note", "accepted via usage text despite nonzero exit");
                }
            }
        }
        out.put("entryHelp", help);

        // 3) block reasons from README
        List<String> blockers = detectBlockers(upstream, readmeCmds);
        out.put("blockers", blockers);

        boolean smokeOk = Boolean.TRUE.equals(compile.get("ok"))
                || Boolean.TRUE.equals(help.get("ok"));
        boolean canFullTrain = blockers.isEmpty() && smokeOk && entry != null;

        String status;
        if (canFullTrain) {
            status = "baseline_smoke_ready"; // trainable path looks reachable; full train not auto-forced
        } else if (smokeOk) {
            status = "baseline_smoke_ok_blocked_full_train";
        } else if (entry != null) {
            status = "baseline_entry_present_smoke_failed";
        } else {
            status = "baseline_no_entry";
        }

        out.put("status", status);
        out.put("ok", smokeOk);
        out.put("fullTrainAttempted", false);
        out.put("metricMatch", false);
        out.put("metricMatchNote",
                "Full paper-table alignment requires dataset/checkpoints. "
                        + "This phase verifies entrypoint executability and records claimed numbers only.");
        out.put("finishedAt", Instant.now().toString());

        // Persist a human summary
        StringBuilder md = new StringBuilder();
        md.append("# Baseline report\n\n");
        md.append("- status: `").append(status).append("`\n");
        md.append("- entry: `").append(entry).append("`\n");
        md.append("- py_compile ok: ").append(compile.get("ok")).append("\n");
        md.append("- help/smoke ok: ").append(help.get("ok")).append("\n");
        md.append("- metricMatch: false (not claimed)\n\n");
        md.append("## README commands\n\n");
        for (String c : readmeCmds) md.append("- `").append(c).append("`\n");
        md.append("\n## Blockers for full train\n\n");
        if (blockers.isEmpty()) md.append("- (none detected heuristically)\n");
        else for (String b : blockers) md.append("- ").append(b).append("\n");
        md.append("\n## Claimed metrics (from reading notes / abs)\n\n");
        md.append("```json\n").append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(claims))
                .append("\n```\n");
        Files.writeString(reproDir.resolve("BASELINE.md"), md.toString(), StandardCharsets.UTF_8);

        write(reproDir, out);
        return out;
    }

    private List<String> extractReadmeCommands(Path upstream) {
        List<String> cmds = new ArrayList<>();
        try (var walk = Files.walk(upstream, 2)) {
            walk.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("readme"))
                    .forEach(p -> {
                        try {
                            String txt = Files.readString(p, StandardCharsets.UTF_8);
                            Matcher m = PY_CMD.matcher(txt);
                            while (m.find() && cmds.size() < 12) {
                                String c = m.group(1).trim();
                                if (c.toLowerCase(Locale.ROOT).contains("train")
                                        || c.toLowerCase(Locale.ROOT).contains("main")
                                        || c.toLowerCase(Locale.ROOT).contains("run")) {
                                    if (!cmds.contains(c)) cmds.add(c);
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
        return cmds;
    }

    private Map<String, Object> extractClaimedMetrics(Path paperRoot) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> hits = new ArrayList<>();
        Path readings = paperRoot.resolve("lit").resolve("readings");
        try {
            if (Files.isDirectory(readings)) {
                try (var walk = Files.walk(readings, 3)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> {
                                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                                return n.endsWith(".md") || n.endsWith(".txt") || n.equals("reading.json");
                            })
                            .forEach(p -> {
                                try {
                                    String txt = Files.readString(p, StandardCharsets.UTF_8);
                                    Matcher m = METRIC_CLAIM.matcher(txt);
                                    while (m.find() && hits.size() < 30) {
                                        Map<String, Object> h = new LinkedHashMap<>();
                                        h.put("metric", m.group(1).toLowerCase(Locale.ROOT));
                                        h.put("value", m.group(2));
                                        h.put("source", paperRoot.relativize(p).toString().replace('\\', '/'));
                                        hits.add(h);
                                    }
                                } catch (Exception ignored) {
                                }
                            });
                }
            }
        } catch (Exception ignored) {
        }
        out.put("hits", hits);
        out.put("note", "Heuristic extractions from reading notes — not verified table cells.");
        return out;
    }

    private List<String> detectBlockers(Path upstream, List<String> cmds) {
        List<String> blockers = new ArrayList<>();
        String joined = String.join("\n", cmds).toLowerCase(Locale.ROOT);
        try {
            String readmeBlob = "";
            Path readme = upstream.resolve("README.md");
            if (Files.isRegularFile(readme)) {
                readmeBlob = Files.readString(readme, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            }
            String all = joined + "\n" + readmeBlob;
            if (all.contains("download") && (all.contains("dataset") || all.contains("data_path")
                    || all.contains("isic") || all.contains("coco") || all.contains("imagenet"))) {
                blockers.add("Likely needs external dataset download before full train");
            }
            if (all.contains("checkpoint") || all.contains("pretrained") || all.contains("sam_vit")
                    || all.contains(".pth") || all.contains("wget http")) {
                blockers.add("Likely needs pretrained weights / checkpoint download");
            }
            if (all.contains("conda env create") || Files.isRegularFile(upstream.resolve("environment.yml"))) {
                blockers.add("Uses conda environment.yml — host may need env bootstrap");
            }
            if (!Files.isDirectory(upstream.resolve("data")) && all.contains("data_path")) {
                blockers.add("No local data/ directory present");
            }
        } catch (Exception ignored) {
        }
        return blockers;
    }

    private Map<String, Object> runHost(Path cwd, List<String> cmd, int timeout) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cmd", cmd);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = br.lines().collect(Collectors.joining("\n"));
            }
            boolean finished = p.waitFor(Math.max(10, timeout), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                m.put("ok", false);
                m.put("error", "timeout");
                m.put("output", tail(out, 2000));
                return m;
            }
            m.put("exitCode", p.exitValue());
            m.put("output", tail(out, 2000));
            m.put("ok", p.exitValue() == 0);
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", e.getMessage());
        }
        return m;
    }

    private static boolean containsUsage(String s) {
        if (s == null) return false;
        String l = s.toLowerCase(Locale.ROOT);
        return l.contains("usage:") || l.contains("optional arguments") || l.contains("options:")
                || l.contains("argparse");
    }

    private void write(Path reproDir, Map<String, Object> out) throws Exception {
        Files.writeString(reproDir.resolve("baseline_report.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out),
                StandardCharsets.UTF_8);
    }

    private void workspaceWrite(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String tail(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
