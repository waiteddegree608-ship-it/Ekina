package com.ekina.application.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Clones upstream paper code (shallow) and inventories entrypoints / deps / datasets hints.
 * Does not claim metric match unless a real baseline run artifact exists.
 */
@Service
public class ReproRunner {

    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> reproduce(Path paperRoot, Map<String, Object> target) throws Exception {
        Path reproDir = paperRoot.resolve("repro");
        Path upstream = reproDir.resolve("upstream");
        Files.createDirectories(reproDir);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", Instant.now().toString());
        report.put("target", target);

        if (target == null || target.isEmpty()) {
            report.put("status", "skipped_no_target");
            report.put("ok", false);
            write(reproDir, report);
            return report;
        }

        Object codeObj = target.get("code");
        List<String> candidates = new ArrayList<>();
        if (codeObj instanceof Map<?, ?> cm) {
            if (cm.get("repos") instanceof List<?> repos) {
                for (Object r : repos) {
                    String u = String.valueOf(r);
                    if (u != null && !u.isBlank() && !"null".equals(u) && !candidates.contains(u)) {
                        candidates.add(u);
                    }
                }
            }
            if (cm.get("primary") != null) {
                String p = String.valueOf(cm.get("primary"));
                if (p != null && !p.isBlank() && !"null".equals(p)) {
                    candidates.remove(p);
                    candidates.add(0, p);
                }
            }
        }
        // Prefer non-list names first; cap attempts
        candidates.sort((a, b) -> Boolean.compare(
                CodeRepoFinder.looksLikeListRepo(a), CodeRepoFinder.looksLikeListRepo(b)));
        if (candidates.size() > 3) {
            candidates = new ArrayList<>(candidates.subList(0, 3));
        }

        if (candidates.isEmpty()) {
            report.put("status", "failed_no_repo");
            report.put("ok", false);
            write(reproDir, report);
            return report;
        }

        Map<String, Object> lastInventory = Map.of();
        String chosenRepo = null;
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (String repo : candidates) {
            if (CodeRepoFinder.looksLikeListRepo(repo) && candidates.stream().anyMatch(r -> !CodeRepoFinder.looksLikeListRepo(r))) {
                // skip list repos when a better candidate exists
                Map<String, Object> skip = new LinkedHashMap<>();
                skip.put("repo", repo);
                skip.put("skipped", true);
                skip.put("reason", "list-like repo demoted");
                attempts.add(skip);
                continue;
            }
            // clean previous failed/sparse clone
            deleteRecursive(upstream);
            Map<String, Object> clone = gitClone(repo, upstream);
            Map<String, Object> attempt = new LinkedHashMap<>();
            attempt.put("repo", repo);
            attempt.put("clone", clone);
            if (!Boolean.TRUE.equals(clone.get("ok"))) {
                attempt.put("ok", false);
                attempts.add(attempt);
                continue;
            }
            Map<String, Object> inventory = inventory(upstream);
            Map<String, Object> invBrief = new LinkedHashMap<>();
            invBrief.put("suggestedEntry", inventory.get("suggestedEntry"));
            invBrief.put("requirementFiles", inventory.get("requirementFiles"));
            invBrief.put("fileCount", inventory.get("fileCount"));
            invBrief.put("trainCandidates", inventory.get("trainCandidates"));
            attempt.put("inventory", invBrief);
            boolean runnableHint = inventory.get("suggestedEntry") != null
                    || (inventory.get("requirementFiles") instanceof List<?> r && !r.isEmpty())
                    || (inventory.get("trainCandidates") instanceof List<?> t && !t.isEmpty());
            attempt.put("runnableHint", runnableHint);
            attempts.add(attempt);
            lastInventory = inventory;
            chosenRepo = repo;
            if (runnableHint) break;
        }
        report.put("attempts", attempts);

        if (chosenRepo == null) {
            report.put("status", "clone_failed");
            report.put("ok", false);
            write(reproDir, report);
            return report;
        }

        String repo = chosenRepo;
        report.put("repo", repo);
        Map<String, Object> inventory = lastInventory;
        Files.writeString(reproDir.resolve("INVENTORY.md"), inventoryMarkdown(repo, inventory),
                StandardCharsets.UTF_8);

        Map<String, Object> envProbe = probePythonEnv(upstream);
        report.put("envProbe", envProbe);
        report.put("inventory", inventory);

        String adapter = """
                # Ekina repro adapter
                # Upstream: %s
                # This file is generated. Replace run_baseline() with the paper's real entrypoint
                # after human/agent verification of README commands.
                
                def describe():
                    return {
                        "repo": "%s",
                        "suggested_entry": %s,
                        "requirements": %s,
                    }
                
                if __name__ == "__main__":
                    import json
                    print(json.dumps(describe(), ensure_ascii=False, indent=2))
                """.formatted(
                repo,
                repo,
                toPy(inventory.get("suggestedEntry")),
                toPy(inventory.get("requirementFiles"))
        );
        Files.writeString(reproDir.resolve("adapter_stub.py"), adapter, StandardCharsets.UTF_8);

        boolean runnableHint = inventory.get("suggestedEntry") != null
                || (inventory.get("requirementFiles") instanceof List<?> r && !r.isEmpty())
                || (inventory.get("trainCandidates") instanceof List<?> t && !t.isEmpty());
        report.put("status", runnableHint ? "cloned_inventoried" : "cloned_sparse");
        report.put("ok", true);
        report.put("metricMatch", false);
        report.put("metricMatchNote", "Baseline metric alignment not yet executed — inventory only in this phase.");
        report.put("finishedAt", Instant.now().toString());
        write(reproDir, report);
        return report;
    }

    private static void deleteRecursive(Path root) {
        try {
            if (!Files.exists(root)) return;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> gitClone(String repo, Path dest) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            if (Files.isDirectory(dest)) {
                try (Stream<Path> ls = Files.list(dest)) {
                    if (ls.findAny().isPresent()) {
                        m.put("ok", true);
                        m.put("skipped", true);
                        m.put("note", "upstream already present");
                        return m;
                    }
                }
            }
            Files.createDirectories(dest.getParent());
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "clone", "--depth", "1", repo, dest.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = br.lines().collect(Collectors.joining("\n"));
            }
            boolean finished = p.waitFor(180, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                m.put("ok", false);
                m.put("error", "git clone timeout");
                m.put("output", tail(out, 1500));
                return m;
            }
            m.put("exitCode", p.exitValue());
            m.put("output", tail(out, 1500));
            m.put("ok", p.exitValue() == 0 && Files.isDirectory(dest));
            if (!Boolean.TRUE.equals(m.get("ok"))) {
                m.put("error", "git clone failed");
            }
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", e.getMessage());
        }
        return m;
    }

    private Map<String, Object> inventory(Path upstream) throws Exception {
        Map<String, Object> inv = new LinkedHashMap<>();
        List<String> reqs = new ArrayList<>();
        List<String> readmes = new ArrayList<>();
        List<String> trainish = new ArrayList<>();
        List<String> configs = new ArrayList<>();
        List<String> datasetHints = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(upstream, 4)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String rel = upstream.relativize(p).toString().replace('\\', '/');
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.equals("requirements.txt") || name.equals("environment.yml")
                        || name.equals("pyproject.toml") || name.equals("setup.py")
                        || name.equals("pipfile") || name.endsWith(".lock")) {
                    reqs.add(rel);
                }
                if (name.startsWith("readme")) readmes.add(rel);
                if (name.contains("train") || name.contains("main") || name.equals("run.py")
                        || name.contains("finetune") || name.contains("pretrain")) {
                    if (name.endsWith(".py") || name.endsWith(".sh")) trainish.add(rel);
                }
                if (name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json")
                        || name.endsWith(".toml")) {
                    if (rel.toLowerCase(Locale.ROOT).contains("config")
                            || name.contains("config") || name.contains("hparams")) {
                        configs.add(rel);
                    }
                }
            });
        }

        // dataset hints from README
        for (String r : readmes) {
            try {
                String txt = Files.readString(upstream.resolve(r), StandardCharsets.UTF_8);
                String lower = txt.toLowerCase(Locale.ROOT);
                for (String ds : List.of("cifar", "imagenet", "mnist", "coco", "wikitext", "glue", "squad",
                        "huggingface", "torchvision", "datasets.load")) {
                    if (lower.contains(ds) && !datasetHints.contains(ds)) datasetHints.add(ds);
                }
            } catch (Exception ignored) {
            }
        }

        inv.put("requirementFiles", reqs.stream().limit(12).toList());
        inv.put("readmes", readmes.stream().limit(8).toList());
        inv.put("trainCandidates", trainish.stream().limit(20).toList());
        inv.put("configCandidates", configs.stream().limit(20).toList());
        inv.put("datasetHints", datasetHints);
        inv.put("suggestedEntry", trainish.isEmpty() ? null : trainish.get(0));
        inv.put("fileCount", countFiles(upstream));
        return inv;
    }

    private Map<String, Object> probePythonEnv(Path upstream) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("python", "-c",
                    "import sys; print(sys.version); import importlib.util as u; "
                            + "print('torch', bool(u.find_spec('torch'))); "
                            + "print('torchvision', bool(u.find_spec('torchvision')))");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = br.lines().collect(Collectors.joining("\n"));
            }
            p.waitFor(30, TimeUnit.SECONDS);
            m.put("ok", p.exitValue() == 0);
            m.put("output", out);
            m.put("cwdNote", "Host python probe only; A100 env may differ");
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", e.getMessage());
        }
        return m;
    }

    private static long countFiles(Path root) {
        try (Stream<Path> w = Files.walk(root)) {
            return w.filter(Files::isRegularFile).count();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String inventoryMarkdown(String repo, Map<String, Object> inv) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Repro inventory\n\n");
        sb.append("- repo: `").append(repo).append("`\n");
        sb.append("- files: ").append(inv.get("fileCount")).append("\n");
        sb.append("- suggested entry: `").append(inv.get("suggestedEntry")).append("`\n\n");
        sb.append("## Requirements\n\n");
        for (Object x : (List<?>) inv.getOrDefault("requirementFiles", List.of())) {
            sb.append("- ").append(x).append("\n");
        }
        sb.append("\n## Train-like scripts\n\n");
        for (Object x : (List<?>) inv.getOrDefault("trainCandidates", List.of())) {
            sb.append("- ").append(x).append("\n");
        }
        sb.append("\n## Dataset hints\n\n");
        for (Object x : (List<?>) inv.getOrDefault("datasetHints", List.of())) {
            sb.append("- ").append(x).append("\n");
        }
        sb.append("\n> Metric matching against the paper table is a later phase.\n");
        return sb.toString();
    }

    private void write(Path reproDir, Map<String, Object> report) throws Exception {
        Files.writeString(reproDir.resolve("repro_report.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);
    }

    private static String toPy(Object o) {
        try {
            return new ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "None";
        }
    }

    private static String tail(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
