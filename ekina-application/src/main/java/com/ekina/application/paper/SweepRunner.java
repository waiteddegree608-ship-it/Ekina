package com.ekina.application.paper;

import com.ekina.application.job.SshJobService;
import com.ekina.application.permission.CapabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A100 sweep: upload experiment script, optionally nohup+poll, pull metrics.json.
 */
@Service
public class SweepRunner {

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private SshJobService sshJobService;

    @Autowired
    private CapabilityService capabilityService;

    @Autowired
    private TrainScriptBuilder trainScriptBuilder;

    @Autowired
    private MlflowTracker mlflowTracker;

    @Autowired
    private ExperimentOptimizer experimentOptimizer;

    @Autowired
    private ExperimentGenerator experimentGenerator;

    @Value("${ekina.paper.sweep.iterations:2}")
    private int iterations;

    @Value("${ekina.paper.sweep.mode:auto}")
    private String mode;

    /** Prefer absolute path: quoted ~ is literal and becomes /root/~/... on remote. */
    @Value("${ekina.paper.sweep.remote-root:/root/ekina-sweeps}")
    private String remoteRoot;

    @Value("${ekina.paper.sweep.ssh-timeout-sec:120}")
    private int sshTimeoutSec;

    @Value("${ekina.paper.sweep.async:true}")
    private boolean async;

    @Value("${ekina.paper.sweep.poll-sec:15}")
    private int pollSec;

    @Value("${ekina.paper.sweep.max-wait-sec:1800}")
    private int maxWaitSec;

    @Value("${ekina.paper.sweep.epochs:3}")
    private int epochs;

    /** synthetic (default, fast) or cifar10 (real download; slow on constrained egress). */
    @Value("${ekina.paper.sweep.dataset:synthetic}")
    private String dataset;

    @Value("${ekina.paper.sweep.git-clone-retries:3}")
    private int gitCloneRetries;

    @Value("${ekina.paper.sweep.git-clone-timeout-sec:600}")
    private int gitCloneTimeoutSec;

    @Value("${ekina.reminders.default-user:user-1}")
    private String defaultUser;

    public Map<String, Object> prepareAndMaybeRun(PaperProject project, Path paperRoot,
                                                   Map<String, Object> innovPlan, Map<String, Object> hyper) throws Exception {
        Path codeDir = paperRoot.resolve("code");
        Path runsDir = paperRoot.resolve("runs");
        Files.createDirectories(codeDir);
        Files.createDirectories(runsDir);

        String runId = "run_" + Instant.now().getEpochSecond();
        Path runDir = runsDir.resolve(runId);
        Files.createDirectories(runDir);

        Map<String, Object> sweepMeta = new LinkedHashMap<>();
        sweepMeta.put("paperId", project.getId());
        sweepMeta.put("runId", runId);
        sweepMeta.put("domain", project.getDomain());
        sweepMeta.put("variants", innovPlan.get("variants"));
        sweepMeta.put("createdAt", Instant.now().toString());
        Files.writeString(codeDir.resolve("sweep.yaml.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sweepMeta), StandardCharsets.UTF_8);

        Map<String, Object> out = new LinkedHashMap<>();
        String trainPy;
        String dom = (project.getDomain() == null ? "" : project.getDomain());
        String domL = dom.toLowerCase(java.util.Locale.ROOT);
        String seedRepoL = (project.getSeedRepo() == null ? "" : project.getSeedRepo()).toLowerCase(java.util.Locale.ROOT);
        boolean upstreamCifar = seedRepoL.contains("pytorch-cifar");
        boolean diffusion = domL.contains("diffusion") || domL.contains("扩散") || seedRepoL.contains("diffusion");
        if (diffusion) {
            // Real diffusion (DDPM) experiment on CIFAR-10 — non-synthetic metrics.
            trainPy = DiffusionTrainScript.script(project.getId(), runId, epochs, hyper);
            out.put("trainer", "ddpm-cifar10");
            out.put("dataset", "cifar10");
        } else if (upstreamCifar) {
            trainPy = PytorchCifarMiniTrainer.script(project.getId(), runId, epochs);
            out.put("trainer", "pytorch-cifar-mini");
            out.put("dataset", "cifar10");
        } else {
            // Ekina writes her own experiment for this domain (not a hand-coded per-domain script);
            // fall back to the synthetic scaffold only if generation/parse fails.
            String genPy = experimentGenerator.generate(project.getDomain(), project.getTitleWorking(),
                    project.getId(), runId);
            if (genPy != null && genPy.contains("--out") && genPy.length() > 30) {
                trainPy = genPy;
                out.put("trainer", "ekina-generated");
                out.put("dataset", dataset == null || dataset.isBlank() ? "real" : dataset.trim());
            } else {
                trainPy = trainScriptBuilder.build(project, innovPlan, runId);
                out.put("dataset", dataset == null || dataset.isBlank() ? "synthetic" : dataset.trim());
            }
        }
        Files.writeString(codeDir.resolve("train_experiment.py"), trainPy, StandardCharsets.UTF_8);
        Files.writeString(codeDir.resolve("train_stub.py"), trainPy, StandardCharsets.UTF_8); // alias
        Files.writeString(runDir.resolve("train_experiment.py"), trainPy, StandardCharsets.UTF_8);

        String effective = mode == null ? "auto" : mode.trim().toLowerCase();
        String uid = project.getUserId() == null ? defaultUser : project.getUserId();
        PaperMode paperMode = PaperMode.from(project.getMode());
        // Smoke = link test: never block on SSH. Always finish with local synthetic metrics.
        boolean smoke = paperMode == PaperMode.SMOKE;
        boolean wantRemote = !smoke && ("remote".equals(effective)
                || ("auto".equals(effective) && capabilityService.findActive(uid, "ssh_remote").isPresent()));

        out.put("runId", runId);
        out.put("paperId", project.getId());
        out.put("mode", effective);
        out.put("pipelineMode", paperMode.name());
        out.put("wantRemote", wantRemote);
        out.put("async", async);
        if (!out.containsKey("dataset")) {
            out.put("dataset", dataset == null || dataset.isBlank() ? "synthetic" : dataset.trim());
        }

        if (!wantRemote) {
            if (smoke) {
                return finishLocalSyntheticSmoke(runDir, out);
            }
            // Honesty: without an authorized compute backend, do NOT fabricate metrics.
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put("status", "needs_compute");
            skip.put("note", "No compute backend authorized. Grant ssh_remote (or set ekina.paper.sweep.mode=remote) "
                    + "to run real experiments. Scripts are ready under code/train_experiment.py; no metrics were faked.");
            Files.writeString(runDir.resolve("status.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(skip), StandardCharsets.UTF_8);
            out.put("status", "needs_compute");
            out.put("metricsPresent", false);
            out.put("runDir", runDir.toAbsolutePath().toString());
            return out;
        }

        String remoteDir = remoteRoot.replaceAll("/$", "") + "/" + project.getId() + "/" + runId;
        try {
            if (upstreamCifar) {
                prepareRemotePytorchCifar(uid, remoteDir, trainPy, runDir, project.getSeedRepo());
            } else {
                uploadScript(uid, remoteDir, trainPy, runDir);
            }

            if (async) {
                return runAsyncAndPoll(uid, remoteDir, runDir, out, upstreamCifar);
            }
            return runSync(uid, remoteDir, runDir, out, upstreamCifar);
        } catch (Exception e) {
            if (smoke) {
                out.put("sshFallback", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                return finishLocalSyntheticSmoke(runDir, out);
            }
            throw e;
        }
    }

    /** Offline smoke path: write template metrics so FIG/TEX/PACK/GATE can finish. */
    private Map<String, Object> finishLocalSyntheticSmoke(Path runDir, Map<String, Object> out) throws Exception {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("dataset", "synthetic");
        metrics.put("honesty", "smoke_template");
        metrics.put("accuracy", 0.42);
        metrics.put("epochs", 1);
        metrics.put("note", "Local synthetic metrics for SMOKE pipeline link test (not a real train).");
        Files.writeString(runDir.resolve("metrics.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(metrics), StandardCharsets.UTF_8);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "local_synthetic_smoke");
        status.put("note", "SSH skipped/failed; smoke continues with template metrics");
        Files.writeString(runDir.resolve("status.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(status), StandardCharsets.UTF_8);
        out.put("status", "local_synthetic_smoke");
        out.put("metricsPresent", true);
        out.put("dataset", "synthetic");
        out.put("runDir", runDir.toAbsolutePath().toString());
        return out;
    }

    /**
     * Remote-only prep: git clone upstream on the SSH host (retries + mirrors).
     * Never uploads the model repo from the laptop — only the tiny Ekina trainer adapter.
     */
    private void prepareRemotePytorchCifar(String uid, String remoteDir, String trainPy, Path runDir,
                                          String seedRepo) throws Exception {
        String mk = "mkdir -p " + shellQuote(remoteDir);
        Map<String, Object> mkRes = sshJobService.runRemote(uid, mk, Math.max(30, sshTimeoutSec));
        if (!Boolean.TRUE.equals(mkRes.get("ok"))) {
            throw new IllegalStateException("remote mkdir failed: " + sshFailDetail(mkRes));
        }

        String repo = (seedRepo == null || seedRepo.isBlank())
                ? "https://github.com/kuangliu/pytorch-cifar" : seedRepo.trim();
        String upstreamDir = remoteDir + "/upstream";
        // Force a fresh clone for this run (avoid stale local-upload leftovers).
        sshJobService.runRemote(uid,
                "rm -rf " + shellQuote(upstreamDir),
                Math.max(30, sshTimeoutSec));

        String cloneCmd = RemoteGitCloner.buildCloneCommand(upstreamDir, repo, gitCloneRetries);
        int cloneTimeout = Math.max(120, gitCloneTimeoutSec);
        Map<String, Object> clone = sshJobService.runRemote(uid, cloneCmd, cloneTimeout);
        Map<String, Object> brief = briefSsh(clone);
        brief.putAll(RemoteGitCloner.summarizeOutput(str(clone.get("output"))));
        brief.put("candidates", RemoteGitCloner.cloneCandidates(repo));
        Files.writeString(runDir.resolve("remote_clone.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(brief), StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("remote_clone_out.txt"),
                str(clone.get("output")), StandardCharsets.UTF_8);

        if (!Boolean.TRUE.equals(clone.get("ok"))) {
            throw new IllegalStateException(
                    "remote git clone FAILED (no local-upload fallback): " + sshFailDetail(clone)
                            + " | out=" + tail(str(clone.get("output")), 800));
        }
        // Verify we got a real git tree (or archive extract), not an empty dir.
        Map<String, Object> verify = sshJobService.runRemote(uid,
                "test -d " + shellQuote(upstreamDir + "/models")
                        + " && test -f " + shellQuote(upstreamDir + "/models/resnet.py")
                        + " && (test -d " + shellQuote(upstreamDir + "/.git")
                        + " && echo SOURCE=git || echo SOURCE=archive)"
                        + " && ls " + shellQuote(upstreamDir + "/models") + " | head",
                Math.max(30, sshTimeoutSec));
        Files.writeString(runDir.resolve("remote_clone_verify.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(briefSsh(verify)), StandardCharsets.UTF_8);
        if (!Boolean.TRUE.equals(verify.get("ok"))) {
            throw new IllegalStateException("remote clone verify failed: " + sshFailDetail(verify)
                    + " | out=" + tail(str(verify.get("output")), 400));
        }
        outMetaNote(runDir, "clone_source", str(verify.get("output")).contains("SOURCE=git") ? "git" : "archive");

        // Only upload Ekina's tiny adapter script — never the upstream weights/code tree.
        uploadScriptTo(uid, upstreamDir, "ekina_mini_train.py", trainPy, runDir);
    }

    private void outMetaNote(Path runDir, String key, String value) throws Exception {
        Path p = runDir.resolve("remote_prep_meta.json");
        Map<String, Object> m = new LinkedHashMap<>();
        if (Files.isRegularFile(p)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> existing = mapper.readValue(Files.readString(p), Map.class);
                m.putAll(existing);
            } catch (Exception ignored) {
            }
        }
        m.put(key, value);
        Files.writeString(p, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(m), StandardCharsets.UTF_8);
    }

    private static String sshFailDetail(Map<String, Object> ssh) {
        if (ssh == null) return "null";
        Object err = ssh.get("error");
        if (err != null && !String.valueOf(err).isBlank() && !"null".equals(String.valueOf(err))) {
            return String.valueOf(err);
        }
        return "exit=" + ssh.get("exitCode") + " output=" + tail(str(ssh.get("output")), 500);
    }

    private void uploadScriptTo(String uid, String remoteDir, String fileName, String content, Path runDir)
            throws Exception {
        String b64 = java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        List<String> chunks = chunk(b64, 4000);
        String b64Path = remoteDir + "/" + fileName + ".b64";
        String outPath = remoteDir + "/" + fileName;
        StringBuilder cmd = new StringBuilder();
        cmd.append("mkdir -p ").append(shellQuote(remoteDir)).append(" && : > ").append(shellQuote(b64Path));
        for (String c : chunks) {
            cmd.append(" && printf %s ").append(shellQuote(c)).append(" >> ").append(shellQuote(b64Path));
        }
        cmd.append(" && base64 -d ").append(shellQuote(b64Path)).append(" > ").append(shellQuote(outPath))
                .append(" && wc -c ").append(shellQuote(outPath));
        Map<String, Object> up = sshJobService.runRemote(uid, cmd.toString(), Math.max(60, sshTimeoutSec));
        Files.writeString(runDir.resolve("upload_" + fileName + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(briefSsh(up)), StandardCharsets.UTF_8);
        if (!Boolean.TRUE.equals(up.get("ok"))) {
            throw new IllegalStateException("upload failed: " + sshFailDetail(up));
        }
    }

    private void uploadScript(String uid, String remoteDir, String trainPy, Path runDir) throws Exception {
        uploadScriptTo(uid, remoteDir, "train_experiment.py", trainPy, runDir);
    }

    private Map<String, Object> runSync(String uid, String remoteDir, Path runDir, Map<String, Object> out,
                                        boolean upstreamCifar) throws Exception {
        String cmd;
        if (upstreamCifar) {
            cmd = "cd " + shellQuote(remoteDir + "/upstream")
                    + " && TQDM_DISABLE=1 PYTHONUNBUFFERED=1 EKINA_DATA_ROOT=/tmp/ekina_data"
                    + " python3 ekina_mini_train.py --out ../metrics.json --epochs " + epochs + " --subset 8000"
                    + " && test -f ../metrics.json && wc -c ../metrics.json && cat ../metrics.json";
        } else {
            String ds = dataset == null || dataset.isBlank() ? "synthetic" : dataset.trim();
            cmd = "cd " + shellQuote(remoteDir)
                    + " && TQDM_DISABLE=1 PYTHONUNBUFFERED=1 python3 train_experiment.py --out metrics.json --epochs "
                    + epochs + " --dataset " + shellQuote(ds)
                    + " && test -f metrics.json && wc -c metrics.json && cat metrics.json";
        }
        Map<String, Object> ssh = sshJobService.runRemote(uid, cmd, Math.max(sshTimeoutSec, 600));
        Files.writeString(runDir.resolve("ssh_result.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ssh), StandardCharsets.UTF_8);
        out.put("ssh", briefSsh(ssh));
        return finishFromSshOutput(ssh, runDir, out, remoteDir);
    }

    private Map<String, Object> runAsyncAndPoll(String uid, String remoteDir, Path runDir, Map<String, Object> out,
                                                boolean upstreamCifar) throws Exception {
        String start;
        if (upstreamCifar) {
            // Avoid `(cd && nohup ... & echo $! >../pid)`: & binds so ../pid lands outside runDir.
            start = "cd " + shellQuote(remoteDir + "/upstream")
                    + " && rm -f ../metrics.json ../DONE ../FAIL ../train.log ../pid.txt"
                    + " && nohup env TQDM_DISABLE=1 PYTHONUNBUFFERED=1 EKINA_DATA_ROOT=/tmp/ekina_data"
                    + " python3 ekina_mini_train.py --out ../metrics.json --epochs " + epochs + " --subset 8000"
                    + " </dev/null >../train.log 2>&1 &"
                    + " echo $! > ../pid.txt"
                    + " && sleep 2"
                    + " && cat ../pid.txt"
                    + " && (kill -0 $(cat ../pid.txt) 2>/dev/null && echo LAUNCHED || (echo LAUNCH_FAIL; tail -c 2000 ../train.log; exit 1))";
        } else {
            String ds = dataset == null || dataset.isBlank() ? "synthetic" : dataset.trim();
            start = "cd " + shellQuote(remoteDir)
                    + " && rm -f metrics.json DONE FAIL train.log pid.txt"
                    + " && (nohup env TQDM_DISABLE=1 PYTHONUNBUFFERED=1 python3 train_experiment.py --out metrics.json --epochs "
                    + epochs + " --dataset " + shellQuote(ds)
                    + " </dev/null >train.log 2>&1 & echo $! >pid.txt)"
                    + " && sleep 2"
                    + " && cat pid.txt"
                    + " && (kill -0 $(cat pid.txt) 2>/dev/null && echo LAUNCHED || (echo LAUNCH_FAIL; tail -c 2000 train.log; exit 1))";
        }
        Map<String, Object> launched = sshJobService.runRemote(uid, start, Math.max(90, sshTimeoutSec));
        Files.writeString(runDir.resolve("launch_result.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(launched), StandardCharsets.UTF_8);
        out.put("launch", briefSsh(launched));
        if (!Boolean.TRUE.equals(launched.get("ok"))) {
            out.put("status", "failed");
            out.put("metricsPresent", false);
            out.put("error", "launch failed: " + launched.get("error"));
            out.put("runDir", runDir.toAbsolutePath().toString());
            out.put("remoteDir", remoteDir);
            return out;
        }

        long deadline = System.currentTimeMillis() + Math.max(60, maxWaitSec) * 1000L;
        int polls = 0;
        while (System.currentTimeMillis() < deadline) {
            polls++;
            Thread.sleep(Math.max(5, pollSec) * 1000L);
            String pollCmd = "cd " + shellQuote(remoteDir) + " && "
                    + "if test -f metrics.json; then echo READY; wc -c metrics.json; "
                    + "elif test -f FAIL; then echo FAIL; tail -c 2000 train.log; "
                    + "elif test -f pid.txt && kill -0 $(cat pid.txt) 2>/dev/null; then echo RUNNING; "
                    + "grep -E '^EKINA_|^Traceback|Error' train.log | tail -n 8; "
                    + "else echo DEAD; tail -c 2000 train.log; fi";
            Map<String, Object> poll = sshJobService.runRemote(uid, pollCmd, Math.max(30, sshTimeoutSec));
            String pout = str(poll.get("output"));
            Files.writeString(runDir.resolve("poll_" + polls + ".txt"), pout, StandardCharsets.UTF_8);
            if (pout.contains("READY")) {
                Map<String, Object> cat = sshJobService.runRemote(uid,
                        "cat " + shellQuote(remoteDir + "/metrics.json"), Math.max(30, sshTimeoutSec));
                out.put("ssh", briefSsh(cat));
                out.put("polls", polls);
                return finishFromSshOutput(cat, runDir, out, remoteDir);
            }
            if (pout.contains("FAIL") || pout.contains("DEAD")) {
                out.put("status", "failed");
                out.put("metricsPresent", false);
                out.put("error", "remote process ended without metrics");
                out.put("logTail", tail(pout, 1500));
                out.put("polls", polls);
                out.put("runDir", runDir.toAbsolutePath().toString());
                out.put("remoteDir", remoteDir);
                Files.writeString(runDir.resolve("status.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out), StandardCharsets.UTF_8);
                return out;
            }
        }
        out.put("status", "timeout");
        out.put("metricsPresent", false);
        out.put("error", "max-wait-sec exceeded (" + maxWaitSec + ")");
        out.put("polls", polls);
        out.put("runDir", runDir.toAbsolutePath().toString());
        out.put("remoteDir", remoteDir);
        return out;
    }

    private Map<String, Object> finishFromSshOutput(Map<String, Object> ssh, Path runDir,
                                                    Map<String, Object> out, String remoteDir) throws Exception {
        String output = str(ssh.get("output"));
        String metricsJson = extractJsonObject(output);
        if (Boolean.TRUE.equals(ssh.get("ok")) && metricsJson != null) {
            Files.writeString(runDir.resolve("metrics.json"), metricsJson, StandardCharsets.UTF_8);
            Files.writeString(runDir.getParent().resolve("latest_metrics.json"), metricsJson, StandardCharsets.UTF_8);
            out.put("status", "succeeded");
            out.put("metricsPresent", true);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(metricsJson, Map.class);
                Object mean = null;
                if (parsed.get("results") instanceof Map<?, ?> r) {
                    mean = r.get("mean_val_accuracy");
                }
                out.put("mean_val_accuracy", mean);
            } catch (Exception ignored) {
            }
            // Best-effort experiment tracking: log this real run to MLflow if configured.
            try {
                Object mean = out.get("mean_val_accuracy");
                Object paperId = out.get("paperId");
                String rid = mlflowTracker.logRun(
                        String.valueOf(paperId == null ? "" : paperId),
                        String.valueOf(out.getOrDefault("runId", "")),
                        String.valueOf(out.getOrDefault("dataset", "")),
                        java.util.Map.of(
                                "paperId", String.valueOf(paperId == null ? "" : paperId),
                                "runId", String.valueOf(out.getOrDefault("runId", "")),
                                "dataset", String.valueOf(out.getOrDefault("dataset", "")),
                                "mode", String.valueOf(out.getOrDefault("mode", ""))),
                        mean instanceof Number num ? num.doubleValue() : null,
                        "mean_val_accuracy");
                out.put("mlflowRunId", rid);
            } catch (Exception ignored) {
            }
        } else {
            out.put("status", "failed");
            out.put("metricsPresent", false);
            out.put("error", ssh.get("error"));
            out.put("outputTail", tail(output, 1500));
        }
        out.put("runDir", runDir.toAbsolutePath().toString());
        out.put("remoteDir", remoteDir);
        return out;
    }

    private static Map<String, Object> briefSsh(Map<String, Object> ssh) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (ssh == null) return m;
        m.put("ok", ssh.get("ok"));
        m.put("phase", ssh.get("phase"));
        m.put("exitCode", ssh.get("exitCode"));
        m.put("error", ssh.get("error"));
        m.put("jobId", ssh.get("jobId"));
        return m;
    }

    private static List<String> chunk(String s, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i += size) {
            out.add(s.substring(i, Math.min(s.length(), i + size)));
        }
        return out;
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static String extractJsonObject(String output) {
        if (output == null) return null;
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        String cand = output.substring(start, end + 1);
        try {
            new ObjectMapper().readTree(cand);
            return cand;
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String tail(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
