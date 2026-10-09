package com.ekina.application.paper;

import com.ekina.application.channel.ChannelService;
import com.ekina.application.home.HomeQuotaService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Paper factory orchestrator (phase 1): SCOUT → LIT → TEX → CITE_CHECK → PACK → GATE.
 * Refuses to invent literature or empirical results.
 */
@Service
public class PaperOrchestrator {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<PaperProject> projects = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(1, r -> {
        Thread t = new Thread(r, "ekina-paper");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    @Autowired
    private HomeQuotaService homeQuotaService;

    @Autowired
    private ChannelService channelService;

    @Autowired
    private com.ekina.application.project.ProjectService projectService;

    @Autowired
    private com.ekina.application.project.ProjectBoardService projectBoardService;

    @Autowired
    private PaperWorkspace workspace;

    @Autowired
    private LiteratureClient literatureClient;

    @Autowired
    private LatexPackager latexPackager;

    @Autowired
    private CitationVerifier citationVerifier;

    @Autowired
    private GateKeeper gateKeeper;

    @Autowired
    private InnovationMenu innovationMenu;

    @Autowired
    private SweepRunner sweepRunner;

    @Autowired
    private FigureBuilder figureBuilder;

    @Autowired
    private FigureBeautifyService figureBeautifyService;

    @Autowired
    private PaperReader paperReader;

    @Autowired
    private ReproTargetSelector reproTargetSelector;

    @Autowired
    private ReproRunner reproRunner;

    @Autowired
    private OutlineBuilder outlineBuilder;

    @Autowired
    private DiagramBuilder diagramBuilder;

    @Autowired
    private BaselineRunner baselineRunner;

    @Autowired
    private VenueRegistry venueRegistry;

    @Autowired
    private AnalysisEngine analysisEngine;

    @Autowired
    private ResultPresenter resultPresenter;

    @Autowired
    private PatchApplier patchApplier;

    @Autowired
    private AblationPlanner ablationPlanner;

    @Autowired
    private ComparisonBuilder comparisonBuilder;

    @Autowired
    private ExperimentOptimizer experimentOptimizer;

    @Autowired
    private ExperimentAdvisor experimentAdvisor;

    @Value("${ekina.paper.sweep.iterations:2}")
    private int sweepIterations;

    @Value("${ekina.paper.sweep.epochs:3}")
    private int baselineEpochs;

    @Value("${ekina.reminders.default-user:user-1}")
    private String defaultUser;

    @Value("${ekina.paper.enabled:true}")
    private boolean enabled;

    @Value("${ekina.paper.lit-limit:12}")
    private int litLimit;

    @Value("${ekina.paper.mode:research}")
    private String defaultMode;

    @Value("${ekina.paper.read-top-n:3}")
    private int readTopN;

    @PostConstruct
    public void init() {
        load();
        resumeRunning();
    }

    /** After a restart, resume any pipeline that was left in status=running so experiments don't stall. */
    private synchronized void resumeRunning() {
        int n = 0;
        for (PaperProject p : projects) {
            if ("running".equals(p.getStatus())) {
                pool.submit(() -> runToGate(p.getId()));
                n++;
            }
        }
        if (n > 0) {
            System.out.println("[Paper] resumed " + n + " running pipeline(s) after restart");
        }
    }

    public PaperProject start(String userId, String domain, String venueTier, String titleHint, boolean autoRun) {
        return start(userId, domain, venueTier, titleHint, null, null, autoRun);
    }

    public PaperProject start(String userId, String domain, String venueTier, String titleHint,
                              String mode, boolean autoRun) {
        return start(userId, domain, venueTier, titleHint, mode, null, autoRun);
    }

    public PaperProject start(String userId, String domain, String venueTier, String titleHint,
                              String mode, String seedRepo, boolean autoRun) {
        if (!enabled) throw new IllegalStateException("paper pipeline disabled");
        if (domain == null || domain.isBlank()) throw new IllegalArgumentException("domain required");

        PaperMode pm = PaperMode.from(mode == null || mode.isBlank() ? defaultMode : mode);
        PaperProject p = new PaperProject();
        p.setId("paper_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        p.setUserId(userId == null || userId.isBlank() ? defaultUser : userId);
        p.setDomain(domain.trim());
        p.setVenueTier(venueTier == null || venueTier.isBlank() ? "CCF-C" : venueTier.trim());
        p.setTitleWorking(titleHint);
        p.setMode(pm.name());
        if (seedRepo != null && !seedRepo.isBlank() && !"null".equalsIgnoreCase(seedRepo)) {
            p.setSeedRepo(seedRepo.trim());
            p.getMeta().put("seedRepo", p.getSeedRepo());
            p.getMeta().put("miniGoal", true);
        }
        p.setStatus("running");
        p.setStage(PaperStage.SCOUT);
        p.setCreatedAt(Instant.now().toString());
        p.setUpdatedAt(p.getCreatedAt());
        p.getMeta().put("mode", pm.name());

        // Step 1: propose candidate target journals (CCF-C / 中科院3-4区) matched to the domain.
        try {
            List<Map<String, Object>> venues = venueRegistry.candidates(p.getDomain(), p.getVenueTier());
            if (!venues.isEmpty()) {
                p.getMeta().put("venueCandidates", venues);
                p.getMeta().put("venue", venues.get(0));
            }
        } catch (Exception e) {
            System.err.println("[Paper] venue selection failed: " + e.getMessage());
        }

        try {
            Path papersRoot = homeQuotaService.homePath().resolve("papers");
            Files.createDirectories(papersRoot);
            workspace.init(papersRoot, p);
        } catch (Exception e) {
            throw new IllegalStateException("workspace init failed: " + e.getMessage(), e);
        }

        synchronized (this) {
            projects.add(0, p);
            save();
        }
        try {
            var proj = projectService.ensureForPaper(p.getUserId(), p.getId(),
                    p.getTitleWorking(), p.getDomain());
            try {
                projectBoardService.seedPaper(proj, p);
            } catch (Exception be) {
                System.err.println("[Paper] seed board: " + be.getMessage());
            }
            channelService.appendToThread(p.getUserId(), proj.getThreadId(), "system", "system",
                    "📄 论文流水线启动 `" + p.getId() + "` mode=" + pm.name()
                            + (p.getSeedRepo() != null ? " seed=" + p.getSeedRepo() : "")
                            + " domain=" + p.getDomain() + " tier=" + p.getVenueTier(),
                    Map.of("paperId", p.getId(), "mode", pm.name(),
                            "projectId", proj.getId(), "threadId", proj.getThreadId()), "done");
        } catch (Exception e) {
            channelService.append(p.getUserId(), "system", "system",
                    "📄 论文流水线启动 `" + p.getId() + "` mode=" + pm.name()
                            + (p.getSeedRepo() != null ? " seed=" + p.getSeedRepo() : "")
                            + " domain=" + p.getDomain() + " tier=" + p.getVenueTier(),
                    Map.of("paperId", p.getId(), "mode", pm.name()), "done");
        }

        if (autoRun) {
            pool.submit(() -> runToGate(p.getId()));
        }
        return p;
    }

    public synchronized Optional<PaperProject> get(String id) {
        return projects.stream().filter(p -> id != null && id.equals(p.getId())).findFirst();
    }

    public synchronized List<PaperProject> list(String userId, int limit) {
        int n = Math.max(1, Math.min(50, limit));
        return projects.stream()
                .filter(p -> userId == null || userId.isBlank() || userId.equals(p.getUserId()))
                .sorted(Comparator.comparing(PaperProject::getCreatedAt, Comparator.nullsLast(String::compareTo)).reversed())
                .limit(n)
                .collect(Collectors.toList());
    }

    public PaperProject runToGate(String paperId) {
        PaperProject p = get(paperId).orElseThrow(() -> new IllegalArgumentException("not found: " + paperId));
        try {
            runStage(p, PaperStage.SCOUT);
            runStage(p, PaperStage.LIT);
            runStage(p, PaperStage.REPRO);   // innov menu + train stub
            runStage(p, PaperStage.SWEEP);   // A100 / SSH or needs_compute
            runStage(p, PaperStage.COMPARE); // ours (measured) vs baseline/top-venue (claimed)
            runStage(p, PaperStage.ABLATE);  // leave-one-out matrix + table
            runStage(p, PaperStage.ANALYZE); // data-driven stats + conclusion
            runStage(p, PaperStage.FIG);
            runStage(p, PaperStage.TEX);
            runStage(p, PaperStage.CITE_CHECK);
            runStage(p, PaperStage.PACK);
            runStage(p, PaperStage.GATE);
            // Step 9: aggregate everything into a human-facing result package.
            try {
                Map<String, Object> r = resultPresenter.present(p, workspace.rootOf(p));
                p.getMeta().put("result", r);
                persist(p);
            } catch (Exception pe) {
                System.err.println("[Paper] present failed: " + pe.getMessage());
            }
        } catch (Exception e) {
            p.setStatus("failed");
            p.setStage(PaperStage.FAILED);
            p.setFinishedAt(Instant.now().toString());
            p.getStageLog().add("FAIL: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            persist(p);
            channelService.append(p.getUserId(), "system", "system",
                    "❌ 论文流水线失败 `" + p.getId() + "`: "
                            + (e.getMessage() == null ? e.toString() : e.getMessage()),
                    Map.of("paperId", p.getId()), "done");
            e.printStackTrace();
        }
        return get(paperId).orElse(p);
    }

    public PaperProject advanceOne(String paperId) throws Exception {
        PaperProject p = get(paperId).orElseThrow(() -> new IllegalArgumentException("not found: " + paperId));
        PaperStage next = switch (p.getStage() == null ? PaperStage.SCOUT : p.getStage()) {
            case SCOUT -> PaperStage.SCOUT;
            case LIT -> PaperStage.LIT;
            case TEX -> PaperStage.TEX;
            case CITE_CHECK -> PaperStage.CITE_CHECK;
            case PACK -> PaperStage.PACK;
            case GATE -> PaperStage.GATE;
            case ANALYZE -> PaperStage.ANALYZE;
            case ABLATE -> PaperStage.ABLATE;
            case COMPARE -> PaperStage.COMPARE;
            case DONE, FAILED -> p.getStage();
            default -> PaperStage.LIT; // skip unimplemented REPRO/SWEEP/FIG in phase-1 auto path
        };
        // If already past a stage, move forward
        if (p.getStage() == PaperStage.SCOUT && stageDone(p, "SCOUT")) next = PaperStage.LIT;
        else if (p.getStage() == PaperStage.LIT && stageDone(p, "LIT")) next = PaperStage.TEX;
        else if (p.getStage() == PaperStage.TEX && stageDone(p, "TEX")) next = PaperStage.CITE_CHECK;
        else if (p.getStage() == PaperStage.CITE_CHECK && stageDone(p, "CITE_CHECK")) next = PaperStage.PACK;
        else if (p.getStage() == PaperStage.PACK && stageDone(p, "PACK")) next = PaperStage.GATE;
        else if (p.getStage() == PaperStage.DONE) return p;

        runStage(p, next);
        return get(paperId).orElse(p);
    }

    private boolean stageDone(PaperProject p, String key) {
        Path root = workspace.rootOf(p);
        Map<String, Object> arts = workspace.readJsonMap(root.resolve("ARTIFACTS.json"));
        Object stage = arts.get(key);
        if (!(stage instanceof Map<?, ?> m)) return false;
        return "ok".equals(String.valueOf(m.get("status")));
    }

    private void runStage(PaperProject p, PaperStage stage) throws Exception {
        Path root = workspace.rootOf(p);
        p.setStage(stage);
        p.setUpdatedAt(Instant.now().toString());
        workspace.appendDiary(root, "stage " + stage);
        switch (stage) {
            case SCOUT -> doScout(p, root);
            case LIT -> doLit(p, root);
            case REPRO -> doRepro(p, root);
            case SWEEP -> doSweep(p, root);
            case COMPARE -> doCompare(p, root);
            case ABLATE -> doAblate(p, root);
            case ANALYZE -> doAnalyze(p, root);
            case FIG -> doFig(p, root);
            case TEX -> doTex(p, root);
            case CITE_CHECK -> doCite(p, root);
            case PACK -> doPack(p, root);
            case GATE -> doGate(p, root);
            default -> throw new IllegalStateException("stage not implemented: " + stage);
        }
        persist(p);
        try {
            projectBoardService.visibleForPaper(p.getUserId(), p.getId()).ifPresent(proj -> {
                String note = p.getStageLog() == null || p.getStageLog().isEmpty()
                        ? ""
                        : String.valueOf(p.getStageLog().get(p.getStageLog().size() - 1));
                projectBoardService.syncPaperStage(proj, p, stage, note);
            });
        } catch (Exception be) {
            System.err.println("[Paper] board sync: " + be.getMessage());
        }
    }

    private void doScout(PaperProject p, Path root) throws Exception {
        PaperMode mode = PaperMode.from(p.getMode());
        Map<String, Object> scout = new LinkedHashMap<>();
        scout.put("domain", p.getDomain());
        scout.put("venueTier", p.getVenueTier());
        scout.put("mode", mode.name());
        scout.put("note", mode == PaperMode.SMOKE
                ? "Smoke scout: topic lock only."
                : "Research scout: will prefer open-code seed papers in LIT/REPRO.");
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(Map.of(
                "titleHint", p.getTitleWorking() == null || p.getTitleWorking().isBlank()
                        ? "Toward Robust " + p.getDomain() + " with Lightweight Adaptation"
                        : p.getTitleWorking(),
                "query", p.getDomain() + " 2024 2025",
                "rationale", "Domain keyword track"
        ));
        candidates.add(Map.of(
                "titleHint", "A Practical Study of " + p.getDomain() + " Baselines",
                "query", p.getDomain() + " benchmark baseline github",
                "rationale", "Reproducible baseline track"
        ));
        scout.put("candidates", candidates);
        String chosen = String.valueOf(candidates.get(0).get("titleHint"));
        p.setTitleWorking(chosen);
        scout.put("chosen", chosen);
        workspace.writeJson(root.resolve("scout.json"), scout);
        workspace.writeArtifacts(root, "SCOUT", Map.of("status", "ok", "chosen", chosen, "mode", mode.name()));
        p.getStageLog().add("SCOUT ok: " + chosen + " mode=" + mode.name());
        p.setStage(PaperStage.LIT);
    }

    private void doLit(PaperProject p, Path root) throws Exception {
        String query = normalizeDomainQuery(p.getDomain());
        // Don't append the (possibly very specific / non-ASCII) working title to the search query —
        // it over-narrows the API and returns zero results. The title is used elsewhere.
        List<LitPaper> papers = literatureClient.search(query, litLimit);
        papers = papers.stream().filter(LitPaper::hasStableId).collect(Collectors.toList());
        // Prefer ML-code-friendly recall
        List<LitPaper> codeish = literatureClient.search(
                normalizeDomainQuery(p.getDomain()) + " github code implementation", Math.max(8, litLimit / 2));
        Map<String, LitPaper> merged = new LinkedHashMap<>();
        for (LitPaper x : papers) merged.put(stableKey(x), x);
        for (LitPaper x : codeish) {
            if (x != null && x.hasStableId()) merged.putIfAbsent(stableKey(x), x);
        }
        papers = new ArrayList<>(merged.values());
        if (papers.size() < 5) {
            papers = literatureClient.search(normalizeDomainQuery(p.getDomain()), litLimit + 8).stream()
                    .filter(LitPaper::hasStableId).collect(Collectors.toList());
        }
        if (papers.isEmpty()) {
            throw new IllegalStateException("LIT failed: no DOI/arXiv papers found for query=" + query);
        }
        workspace.writeJson(root.resolve("lit").resolve("catalog.json"), papers);

        // Deep-read top-N (abs HTML / optional PDF text)
        Path readings = root.resolve("lit").resolve("readings");
        Files.createDirectories(readings);
        int nRead = Math.min(Math.max(1, readTopN), papers.size());
        List<Map<String, Object>> readingIndex = new ArrayList<>();
        for (int i = 0; i < nRead; i++) {
            LitPaper lit = papers.get(i);
            String folder = "p" + (i + 1);
            Path out = readings.resolve(folder);
            Map<String, Object> note = paperReader.readAndNote(lit, out);
            workspace.writeJson(out.resolve("reading.json"), note);
            readingIndex.add(Map.of(
                    "folder", folder,
                    "id", lit.getId() == null ? "" : lit.getId(),
                    "title", lit.getTitle() == null ? "" : lit.getTitle()
            ));
        }
        workspace.writeJson(readings.resolve("index.json"), readingIndex);

        StringBuilder notes = new StringBuilder("# Literature notes\n\n");
        notes.append("Deep-read top ").append(nRead).append(" under `lit/readings/`.\n\n");
        for (LitPaper lit : papers) {
            notes.append("## ").append(lit.getTitle()).append("\n");
            notes.append("- id: ").append(lit.getId()).append("\n");
            if (lit.getDoi() != null) notes.append("- doi: ").append(lit.getDoi()).append("\n");
            if (lit.getArxivId() != null) notes.append("- arxiv: ").append(lit.getArxivId()).append("\n");
            if (lit.getVenue() != null) notes.append("- venue: ").append(lit.getVenue()).append("\n");
            if (lit.getVenueTier() != null) notes.append("- tier: ").append(lit.getVenueTier())
                    .append(lit.isTopVenue() ? " (顶会/顶刊)" : "").append("\n");
            if (lit.getCitationCount() != null) notes.append("- citations: ").append(lit.getCitationCount()).append("\n");
            if (lit.getTldr() != null) notes.append("- tldr: ").append(lit.getTldr()).append("\n");
            notes.append("- year: ").append(lit.getYear()).append(" source: ").append(lit.getSource()).append("\n");
            if (lit.getAbstractText() != null) {
                String a = lit.getAbstractText();
                if (a.length() > 800) a = a.substring(0, 800) + "...";
                notes.append("\n").append(a).append("\n\n");
            }
        }
        Files.writeString(root.resolve("lit").resolve("notes.md"), notes.toString(), StandardCharsets.UTF_8);
        workspace.writeArtifacts(root, "LIT", Map.of(
                "status", "ok",
                "count", papers.size(),
                "readTopN", nRead,
                "catalog", "lit/catalog.json"
        ));
        p.getStageLog().add("LIT ok: " + papers.size() + " papers, read=" + nRead);
        p.getMeta().put("litCount", papers.size());
        p.setStage(PaperStage.REPRO);
    }

    private void doRepro(PaperProject p, Path root) throws Exception {
        PaperMode mode = PaperMode.from(p.getMode());
        List<LitPaper> catalog = mapper.readValue(
                Files.readString(root.resolve("lit").resolve("catalog.json"), StandardCharsets.UTF_8),
                new TypeReference<>() {});

        Map<String, Object> targetSel = new LinkedHashMap<>();
        Map<String, Object> reproReport = new LinkedHashMap<>();
        if (mode.needsUpstream()) {
            Map<String, Object> chosen = null;
            boolean has;
            if (p.getSeedRepo() != null && !p.getSeedRepo().isBlank()) {
                chosen = pinnedTarget(p.getSeedRepo(), p.getTitleWorking());
                targetSel.put("chosen", chosen);
                targetSel.put("hasReproTarget", true);
                targetSel.put("pinned", true);
                has = true;
                workspace.writeJson(root.resolve("repro").resolve("target.json"), targetSel);
            } else {
                targetSel = reproTargetSelector.select(catalog, p.getVenueTier());
                workspace.writeJson(root.resolve("repro").resolve("target.json"), targetSel);
                @SuppressWarnings("unchecked")
                Map<String, Object> c = (Map<String, Object>) targetSel.get("chosen");
                chosen = c;
                has = Boolean.TRUE.equals(targetSel.get("hasReproTarget"));
            }
            if (mode == PaperMode.REPRODUCE && !has) {
                throw new IllegalStateException(
                        "REPRODUCE mode requires an open-code seed paper; none found via PapersWithCode/arXiv");
            }
            if (has && chosen != null) {
                reproReport = reproRunner.reproduce(root, chosen);
                if (mode == PaperMode.REPRODUCE && !Boolean.TRUE.equals(reproReport.get("ok"))) {
                    throw new IllegalStateException("REPRO clone/inventory failed: " + reproReport.get("status"));
                }
                Map<String, Object> baseline = baselineRunner.run(root, reproReport);
                reproReport.put("baseline", baseline);
                workspace.writeJson(root.resolve("repro").resolve("repro_report.json"), reproReport);
                if (chosen.get("title") != null) {
                    String seedTitle = String.valueOf(chosen.get("title"));
                    if (p.getTitleWorking() != null && p.getTitleWorking().startsWith("Toward Robust")) {
                        p.setTitleWorking("Incremental Study on: " + seedTitle);
                    }
                }
            } else {
                reproReport.put("status", "skipped_no_open_code");
                reproReport.put("ok", false);
                workspace.writeJson(root.resolve("repro").resolve("repro_report.json"), reproReport);
            }
        } else {
            reproReport.put("status", "skipped_smoke_mode");
            reproReport.put("ok", true);
            workspace.writeJson(root.resolve("repro").resolve("repro_report.json"), reproReport);
        }

        Map<String, Object> plan = innovationMenu.planFor(p.getDomain(), p.getTitleWorking());
        workspace.writeJson(root.resolve("innov.json"), plan);

        // Step 5: turn innovation variants into real (reviewable) code patches + report.
        Map<String, Object> patchReport = patchApplier.apply(p, root, plan);
        workspace.writeJson(root.resolve("repro").resolve("patch_report.json"), patchReport);
        p.getMeta().put("patch", patchReport);

        Map<String, Object> outline = outlineBuilder.build(root, p);
        workspace.writeJson(root.resolve("notes").resolve("outline_stage.json"), outline);

        Files.writeString(root.resolve("code").resolve("README.md"),
                "# Experiment code\n\n"
                        + "- mode: `" + mode.name() + "`\n"
                        + "- innov plan: `../innov.json`\n"
                        + "- upstream clone (if any): `../repro/upstream`\n"
                        + "- inventory: `../repro/INVENTORY.md`\n"
                        + "- Ekina train template prepared in SWEEP (`train_experiment.py`)\n"
                        + "- Metric match vs paper table: not claimed until baseline runner exists\n",
                StandardCharsets.UTF_8);

        Map<String, Object> art = new LinkedHashMap<>();
        art.put("status", "ok");
        art.put("mode", mode.name());
        art.put("hasReproTarget", targetSel.get("hasReproTarget"));
        art.put("reproStatus", reproReport.get("status"));
        art.put("variants", plan.get("variants"));
        art.put("innov", "innov.json");
        art.put("outline", outline.get("outlineMd"));
        if (reproReport.get("baseline") instanceof Map<?, ?> bm) {
            art.put("baselineStatus", bm.get("status"));
            art.put("baselineOk", bm.get("ok"));
        }
        workspace.writeArtifacts(root, "REPRO", art);
        p.getStageLog().add("REPRO ok: mode=" + mode.name()
                + " repro=" + reproReport.get("status")
                + " baseline=" + (reproReport.get("baseline") instanceof Map<?, ?> bm2 ? bm2.get("status") : "n/a")
                + " innov=planned");
        p.getMeta().put("innov", plan);
        Map<String, Object> reproMeta = new LinkedHashMap<>();
        reproMeta.put("status", reproReport.get("status"));
        reproMeta.put("ok", reproReport.get("ok"));
        reproMeta.put("repo", reproReport.get("repo"));
        reproMeta.put("hasReproTarget", targetSel.get("hasReproTarget"));
        Object baseline = reproReport.get("baseline");
        if (baseline instanceof Map<?, ?> bm) {
            reproMeta.put("baselineStatus", bm.get("status"));
            reproMeta.put("baselineOk", bm.get("ok"));
            reproMeta.put("metricMatch", bm.get("metricMatch"));
        }
        p.getMeta().put("repro", reproMeta);
        p.setStage(PaperStage.SWEEP);
    }

    private void doSweep(PaperProject p, Path root) throws Exception {
        Map<String, Object> plan = workspace.readJsonMap(root.resolve("innov.json"));
        if (plan.isEmpty()) {
            plan = innovationMenu.planFor(p.getDomain(), p.getTitleWorking());
        }
        // Closed-loop research: run a config, analyze, and if the result is poor keep improving
        // (bigger model / more epochs) up to the iteration budget. Each run lands in runs/<runId>/
        // so COMPARE / ABLATE / ANALYZE aggregate every iteration into a real evidence table.
        boolean diffusion = isDiffusion(p);
        // The "run -> analyze -> optimize/innovate" loop is a diffusion (FID) improvement ladder: the
        // hyper map it proposes (ch/ema/cfg/lr) and the target metric it climbs (results.fid) only
        // make sense for a diffusion model. For any other domain there is no FID to improve, and each
        // extra iteration would just generate a fresh, unrelated experiment (not a refinement of the
        // previous one), which corrupts the evidence tables and burns compute/API. So non-diffusion
        // domains run one honest experiment and stop.
        int maxIter = diffusion ? Math.max(1, sweepIterations) : 1;
        List<Map<String, Object>> iterations = new ArrayList<>();
        Map<String, Object> sweep = null;
        Map<String, Object> prevHyper = null;
        Double prevFid = null;
        for (int it = 0; it < maxIter; it++) {
            // Baseline on iteration 0; afterwards Ekina decides (via her own LLM) what to try next.
            Map<String, Object> hyper = (it == 0)
                    ? experimentOptimizer.hyperForIteration(0, Math.max(2, baselineEpochs))
                    : experimentAdvisor.propose(prevFid, prevHyper, it, maxIter);
            if (hyper == null) break; // Ekina judged she should stop
            sweep = sweepRunner.prepareAndMaybeRun(p, root, plan, hyper);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("iteration", it);
            rec.put("hyper", hyper);
            rec.put("status", sweep.get("status"));
            rec.put("metricsPresent", sweep.get("metricsPresent"));
            rec.put("runDir", sweep.get("runDir"));
            Double target = readTargetMetric(root, diffusion);
            rec.put(diffusion ? "fid" : "mean_val_accuracy", target);
            iterations.add(rec);
            prevHyper = hyper;
            prevFid = target;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("iterations", iterations);
        summary.put("pipelineMode", p.getMode());
        workspace.writeJson(root.resolve("runs").resolve("iterations.json"), summary);

        sweep = sweep == null ? Map.of() : sweep;
        sweep.put("pipelineMode", p.getMode());
        workspace.writeJson(root.resolve("runs").resolve("sweep_summary.json"), sweep);
        workspace.writeArtifacts(root, "SWEEP", sweep);
        p.getStageLog().add("SWEEP iterations=" + iterations.size() + " last=" + String.valueOf(sweep.get("status"))
                + " metrics=" + sweep.get("metricsPresent"));
        p.getMeta().put("sweep", sweep);
        p.getMeta().put("iterations", iterations);
        p.setStage(PaperStage.COMPARE);
    }

    /** Read the domain's target metric from latest_metrics.json: FID for diffusion, val accuracy otherwise. */
    private Double readTargetMetric(Path root, boolean diffusion) {
        try {
            Path f = root.resolve("runs").resolve("latest_metrics.json");
            if (!Files.isRegularFile(f)) return null;
            var n = mapper.readTree(Files.readString(f, StandardCharsets.UTF_8));
            var res = n.path("results");
            if (diffusion) {
                return res.path("fid").isNumber() ? res.path("fid").asDouble() : null;
            }
            return res.path("mean_val_accuracy").isNumber() ? res.path("mean_val_accuracy").asDouble() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void doCompare(PaperProject p, Path root) throws Exception {
        Map<String, Object> cmp = comparisonBuilder.build(root);
        workspace.writeJson(root.resolve("figures").resolve("comparison.json"), cmp);
        workspace.writeArtifacts(root, "COMPARE", Map.of(
                "status", "ok",
                "rows", cmp.get("rows"),
                "anyNonSynthetic", cmp.get("anyNonSynthetic"),
                "claimedFound", cmp.get("claimedFound"),
                "table", cmp.get("table")
        ));
        p.getStageLog().add("COMPARE ok: rows=" + cmp.get("rows") + " real=" + cmp.get("anyNonSynthetic")
                + " claimed=" + cmp.get("claimedFound"));
        p.getMeta().put("compare", cmp);
        p.setStage(PaperStage.ABLATE);
    }

    private void doAblate(PaperProject p, Path root) throws Exception {
        Map<String, Object> plan = workspace.readJsonMap(root.resolve("innov.json"));
        if (plan.isEmpty()) {
            plan = innovationMenu.planFor(p.getDomain(), p.getTitleWorking());
        }
        Map<String, Object> matrix = ablationPlanner.plan(p, plan);
        workspace.writeJson(root.resolve("runs").resolve("ablation_matrix.json"), matrix);
        Map<String, Object> tables = ablationPlanner.buildTables(root);
        workspace.writeArtifacts(root, "ABLATE", Map.of(
                "status", "ok",
                "configs", tables.get("configs"),
                "anyNonSynthetic", tables.get("anyNonSynthetic"),
                "table", tables.get("table")
        ));
        p.getStageLog().add("ABLATE ok: configs=" + tables.get("configs") + " real=" + tables.get("anyNonSynthetic"));
        p.getMeta().put("ablation", tables);
        p.setStage(PaperStage.ANALYZE);
    }

    private void doAnalyze(PaperProject p, Path root) throws Exception {
        // AnalysisEngine persists notes/analysis.json + notes/analysis.md.
        Map<String, Object> analysis = analysisEngine.analyze(p, root);
        workspace.writeArtifacts(root, "ANALYZE", Map.of(
                "status", "ok",
                "runCount", analysis.get("runCount"),
                "hasRealMetrics", analysis.get("hasRealMetrics"),
                "conclusion", analysis.get("conclusion")
        ));
        p.getStageLog().add("ANALYZE ok: runs=" + analysis.get("runCount")
                + " real=" + analysis.get("hasRealMetrics"));
        p.getMeta().put("analysis", analysis);
        p.setStage(PaperStage.FIG);
    }

    private void doFig(PaperProject p, Path root) throws Exception {
        Map<String, Object> fig = figureBuilder.buildFromRuns(root);
        Map<String, Object> diagrams = diagramBuilder.build(root, p);
        Map<String, Object> polished = figureBeautifyService.beautify(root);
        Map<String, Object> merged = new LinkedHashMap<>(fig);
        merged.put("diagrams", diagrams);
        merged.put("beautify", polished);
        workspace.writeArtifacts(root, "FIG", merged);
        p.getStageLog().add("FIG " + fig.get("status") + " diagrams=" + diagrams.get("status"));
        p.setStage(PaperStage.TEX);
    }

    private void doTex(PaperProject p, Path root) throws Exception {
        List<LitPaper> catalog = mapper.readValue(
                Files.readString(root.resolve("lit").resolve("catalog.json"), StandardCharsets.UTF_8),
                new TypeReference<>() {});
        Map<String, Object> art = latexPackager.generateTex(root, p, catalog);
        workspace.writeArtifacts(root, "TEX", art);
        p.getStageLog().add("TEX ok: " + art.get("title"));
        p.setStage(PaperStage.CITE_CHECK);
    }

    private void doCite(PaperProject p, Path root) throws Exception {
        Path bib = root.resolve("tex").resolve("refs.bib");
        Map<String, Object> report = citationVerifier.verifyBibFile(bib);
        workspace.writeJson(root.resolve("lit").resolve("cite_report.json"), report);
        if (!Boolean.TRUE.equals(report.get("ok"))) {
            String filtered = citationVerifier.filterVerifiedBib(bib, report);
            if (filtered.isBlank() || toInt(report.get("verified")) == 0) {
                workspace.writeArtifacts(root, "CITE_CHECK", Map.of(
                        "status", "failed",
                        "report", report
                ));
                throw new IllegalStateException("CITE_CHECK failed: no verified bibliography entries");
            }
            Files.writeString(bib, filtered, StandardCharsets.UTF_8);
            report = citationVerifier.verifyBibFile(bib);
            workspace.writeJson(root.resolve("lit").resolve("cite_report.json"), report);
        }
        if (!Boolean.TRUE.equals(report.get("ok"))) {
            workspace.writeArtifacts(root, "CITE_CHECK", Map.of("status", "failed", "report", report));
            throw new IllegalStateException("CITE_CHECK failed after filter");
        }
        workspace.writeArtifacts(root, "CITE_CHECK", Map.of(
                "status", "ok",
                "verified", report.get("verified"),
                "failed", report.get("failed")
        ));
        p.getStageLog().add("CITE_CHECK ok: verified=" + report.get("verified"));
        p.setStage(PaperStage.PACK);
    }

    private void doPack(PaperProject p, Path root) throws Exception {
        Map<String, Object> art = latexPackager.packZip(root, p);
        workspace.writeArtifacts(root, "PACK", art);
        p.getStageLog().add("PACK ok: " + art.get("zip"));
        p.getMeta().put("zip", art.get("zip"));
        p.setStage(PaperStage.GATE);
    }

    private void doGate(PaperProject p, Path root) throws Exception {
        Map<String, Object> citeReport = workspace.readJsonMap(root.resolve("lit").resolve("cite_report.json"));
        Map<String, Object> gate = gateKeeper.evaluate(root, citeReport, PaperMode.from(p.getMode()));
        workspace.writeJson(root.resolve("gate_report.json"), gate);
        workspace.writeArtifacts(root, "GATE", gate);
        p.setGateReport(gate);
        boolean pass = Boolean.TRUE.equals(gate.get("pass"));
        p.setStatus(pass ? "succeeded" : "failed");
        p.setStage(pass ? PaperStage.DONE : PaperStage.FAILED);
        p.setFinishedAt(Instant.now().toString());
        p.getStageLog().add("GATE " + (pass ? "PASS" : "FAIL")
                + " mode=" + p.getMode()
                + " score=" + gate.get("score")
                + " level=" + gate.get("honestyLevel"));
        channelService.append(p.getUserId(), "system", "system",
                (pass ? "✅" : "❌") + " 论文流水线 Gate `" + p.getId() + "` mode=" + p.getMode()
                        + " score=" + gate.get("score") + "/" + gate.get("maxScore")
                        + " (" + gate.get("honestyLevel") + ")",
                Map.of("paperId", p.getId(), "pass", pass, "mode", p.getMode()), "done");
    }

    @Scheduled(fixedDelayString = "${ekina.paper.poll-ms:15000}")
    public void tick() {
        if (!enabled || !ticking.compareAndSet(false, true)) return;
        try {
            // no-op for now; autoRun uses pool. Hook for factory queue later.
        } finally {
            ticking.set(false);
        }
    }

    private void persist(PaperProject p) {
        try {
            workspace.writeJson(Path.of(p.getRootPath()).resolve("project.json"), p);
        } catch (Exception ignored) {
        }
        synchronized (this) {
            save();
        }
    }

    private Path stateFile() {
        return homeQuotaService.homePath().resolve("state").resolve("papers.json");
    }

    private synchronized void load() {
        Path f = stateFile();
        if (!Files.isRegularFile(f)) return;
        try {
            List<PaperProject> loaded = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8),
                    new TypeReference<>() {});
            projects.clear();
            if (loaded != null) projects.addAll(loaded);
        } catch (Exception e) {
            System.err.println("[Paper] load failed: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Path f = stateFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(projects),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Paper] save failed: " + e.getMessage());
        }
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    /** Avoid IoT "LoRa" collision when user means LoRA adapters. */
    private static String normalizeDomainQuery(String domain) {
        if (domain == null) return "";
        String d = domain.trim();
        // Strip CJK ideographs, punctuation and symbols so the search API gets a clean English
        // query — otherwise a Chinese domain like "扩散模型" yields zero results and LIT fails.
        d = d.replaceAll("[^A-Za-z0-9 ]+", " ").replaceAll("\\s+", " ").trim();
        if (d.isBlank()) return "machine learning";
        if (d.equalsIgnoreCase("lora") || d.equalsIgnoreCase("LoRA")) {
            return "LoRA Low-Rank Adaptation large language models";
        }
        if (d.toLowerCase(Locale.ROOT).contains("lora")
                && !d.toLowerCase(Locale.ROOT).contains("adaptation")
                && !d.toLowerCase(Locale.ROOT).contains("rank")) {
            return d + " Low-Rank Adaptation PEFT";
        }
        return d;
    }

    private static String stableKey(LitPaper p) {
        if (p.getDoi() != null && !p.getDoi().isBlank()) return "doi:" + p.getDoi().toLowerCase(Locale.ROOT);
        if (p.getArxivId() != null && !p.getArxivId().isBlank()) return "arxiv:" + p.getArxivId().toLowerCase(Locale.ROOT);
        return "t:" + String.valueOf(p.getTitle()).toLowerCase(Locale.ROOT);
    }

    /** Matches SweepRunner's diffusion detection so the pipeline agrees on which domains get an FID ladder. */
    private static boolean isDiffusion(PaperProject p) {
        String dom = (p.getDomain() == null ? "" : p.getDomain()).toLowerCase(Locale.ROOT);
        String seed = (p.getSeedRepo() == null ? "" : p.getSeedRepo()).toLowerCase(Locale.ROOT);
        return dom.contains("diffusion") || dom.contains("扩散") || seed.contains("diffusion");
    }

    private static Map<String, Object> pinnedTarget(String seedRepo, String titleHint) {
        Map<String, Object> code = new LinkedHashMap<>();
        code.put("primary", seedRepo);
        code.put("repos", List.of(seedRepo));
        code.put("ok", true);
        code.put("sources", List.of("pinned"));
        Map<String, Object> chosen = new LinkedHashMap<>();
        chosen.put("title", titleHint == null || titleHint.isBlank() ? seedRepo : titleHint);
        chosen.put("hasCode", true);
        chosen.put("code", code);
        chosen.put("score", 999);
        chosen.put("pinned", true);
        return chosen;
    }
}
