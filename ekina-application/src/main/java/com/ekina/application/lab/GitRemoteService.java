package com.ekina.application.lab;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Push the local lab or a paper folder to GitHub / Overleaf Git.
 * Tokens stay in env — remotes are stored without secrets.
 * Overleaf Git cannot create a project; one blank "writing desk" URL is enough.
 */
@Service
public class GitRemoteService {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    private LabLedgerService labLedgerService;

    @Autowired
    private com.ekina.application.paper.JournalFormatService journalFormatService;

    @Value("${ekina.github.token:}")
    private String githubToken;

    @Value("${ekina.github.user:}")
    private String githubUser;

    @Value("${ekina.github.lab-repo:ekina-lab}")
    private String labRepo;

    @Value("${ekina.overleaf.git-url:}")
    private String overleafGitUrl;

    @Value("${ekina.overleaf.token:}")
    private String overleafToken;

    public Map<String, Object> syncLabGithub() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (blank(githubToken) || blank(githubUser)) {
            out.put("ok", false);
            out.put("error", "缺少 EKINA_GITHUB_TOKEN 或 EKINA_GITHUB_USER。写进 .env 后重启后端。");
            return out;
        }
        Path lab = labLedgerService.root();
        try {
            Files.createDirectories(lab);
            writeGitignore(lab);
            Map<String, Object> created = ensureGithubRepo(labRepo, "Ekina lab ledger");
            Map<String, Object> push = pushGithub(lab, labRepo, "lab: " + LocalDate.now());
            out.put("ok", Boolean.TRUE.equals(push.get("ok")));
            out.put("repo", "https://github.com/" + githubUser + "/" + labRepo);
            out.put("ensure", created);
            out.put("push", push);
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return out;
        }
    }

    /**
     * No existing Overleaf/GitHub paper required.
     * Creates local LaTeX under lab/academic/&lt;slug&gt;/paper, private GitHub repo, optional Overleaf desk.
     */
    public Map<String, Object> openNewPaper(String slug, String title, String venue) {
        Map<String, Object> out = new LinkedHashMap<>();
        String id = slugify(slug, title);
        String paperTitle = (title == null || title.isBlank()) ? id : title.trim();
        Path paper = labLedgerService.root().resolve("academic").resolve(id).resolve("paper");
        try {
            boolean seeded = seedPaperIfEmpty(paper, paperTitle, venue);
            labLedgerService.record("academic", id, paperTitle,
                    "开空白稿 canvas：" + paper + "。GitHub 仓 ekina-paper-" + id + "。Overleaf 需要一次性空白写稿桌。",
                    null);
            String repo = "ekina-paper-" + id;
            Map<String, Object> github = new LinkedHashMap<>();
            if (!blank(githubToken) && !blank(githubUser)) {
                github.put("ensure", ensureGithubRepo(repo, paperTitle));
                github.put("push", pushGithub(paper, repo, "paper: open " + paperTitle));
            } else {
                github.put("ok", false);
                github.put("error", "GitHub token 未配置，只建了本地稿。");
            }
            Map<String, Object> overleaf = syncOverleaf(paper.toString());
            out.put("ok", true);
            out.put("slug", id);
            out.put("localPaper", paper.toString());
            out.put("seededBlank", seeded);
            out.put("githubRepo", blank(githubUser) ? repo : ("https://github.com/" + githubUser + "/" + repo));
            out.put("github", github);
            out.put("overleaf", overleaf);
            out.put("overleafHint", overleafHint());
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return out;
        }
    }

    public Map<String, Object> syncOverleaf(String localDir) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (blank(overleafToken)) {
            out.put("ok", false);
            out.put("error", "缺少 EKINA_OVERLEAF_TOKEN。");
            out.put("hint", overleafHint());
            return out;
        }
        if (blank(overleafGitUrl)) {
            out.put("ok", false);
            out.put("error", "Overleaf 不能用 token 新建项目。建一个空白项目当写稿桌即可，不需要现成论文。");
            out.put("hint", overleafHint());
            return out;
        }
        Path dir = localDir == null || localDir.isBlank()
                ? labLedgerService.root().resolve("academic")
                : Path.of(localDir);
        try {
            if (!Files.isDirectory(dir)) {
                out.put("ok", false);
                out.put("error", "本地目录不存在: " + dir);
                return out;
            }
            if (!Files.isDirectory(dir.resolve(".git"))) {
                run(dir, "git", "init", "-b", "main");
            }
            String url = overleafGitUrl.trim()
                    .replace("https://git@", "https://")
                    .replace("git@", "");
            if (url.startsWith("https://git.overleaf.com") == false && url.contains("git.overleaf.com")) {
                int at = url.indexOf("git.overleaf.com");
                url = "https://" + url.substring(at);
            }
            String clean = url.replaceAll("://[^/]*@", "://");
            setRemote(dir, "overleaf", clean);
            run(dir, "git", "add", "-A");
            run(dir, "git", "-c", "user.email=ekina@local", "-c", "user.name=Ekina",
                    "commit", "-m", "overleaf: " + LocalDate.now());
            String authUrl = clean.replace("https://", "https://git:" + overleafToken + "@");
            String branch = detectOverleafBranch(authUrl);
            ExecResult push = run(dir, "git", "-c", "credential.helper=",
                    "push", "-u", authUrl, "HEAD:" + branch);
            if (!push.ok && "main".equals(branch)) {
                push = run(dir, "git", "-c", "credential.helper=",
                        "push", "-u", authUrl, "HEAD:master");
                if (push.ok) branch = "master";
            }
            setRemote(dir, "overleaf", clean);
            out.put("branch", branch);
            out.put("ok", push.ok || push.combined.contains("Everything up-to-date"));
            out.put("dir", dir.toString());
            out.put("push", clip(push.combined));
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return out;
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("githubConfigured", !blank(githubToken) && !blank(githubUser));
        out.put("githubUser", githubUser);
        out.put("labRepo", labRepo);
        out.put("labUrl", blank(githubUser) ? "" : "https://github.com/" + githubUser + "/" + labRepo);
        out.put("overleafTokenSet", !blank(overleafToken));
        out.put("overleafConfigured", !blank(overleafGitUrl) && !blank(overleafToken));
        out.put("overleafGitUrl", blank(overleafGitUrl) ? "" : overleafGitUrl.replaceAll("://.*@", "://***@"));
        out.put("overleafNeedsWritingDesk", blank(overleafGitUrl));
        out.put("overleafProject", overleafProjectUrl());
        out.put("overleafHint", overleafHint());
        out.put("labRoot", labLedgerService.root().toString());
        return out;
    }

    private Map<String, Object> pushGithub(Path dir, String repo, String message) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir.resolve(".git"))) {
            run(dir, "git", "init", "-b", "main");
        }
        String clean = "https://github.com/" + githubUser + "/" + repo + ".git";
        setRemote(dir, "origin", clean);
        writeGitignore(dir);
        run(dir, "git", "add", "-A");
        ExecResult commit = run(dir, "git", "-c", "user.email=ekina@local", "-c", "user.name=Ekina",
                "commit", "-m", message);
        String basic = Base64.getEncoder().encodeToString(
                ("x-access-token:" + githubToken).getBytes(StandardCharsets.UTF_8));
        ExecResult push = run(dir, "git", "-c", "credential.helper=",
                "-c", "http.extraHeader=Authorization: Basic " + basic,
                "push", "-u", "origin", "main");
        setRemote(dir, "origin", clean);
        out.put("ok", push.ok || push.combined.contains("Everything up-to-date"));
        out.put("repo", clean);
        out.put("commit", clip(commit.combined));
        out.put("push", clip(push.combined));
        return out;
    }

    private Map<String, Object> ensureGithubRepo(String name, String description) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        HttpResponse<String> get = github("GET", "/repos/" + githubUser + "/" + name, null);
        if (get.statusCode() == 200) {
            out.put("existed", true);
            out.put("htmlUrl", "https://github.com/" + githubUser + "/" + name);
            return out;
        }
        String body = "{\"name\":\"" + escapeJson(name) + "\",\"private\":true,\"description\":\""
                + escapeJson(description == null ? name : description) + "\"}";
        HttpResponse<String> created = github("POST", "/user/repos", body);
        out.put("existed", false);
        out.put("status", created.statusCode());
        out.put("ok", created.statusCode() == 201);
        if (created.statusCode() != 201) {
            out.put("body", clip(created.body()));
        }
        out.put("htmlUrl", "https://github.com/" + githubUser + "/" + name);
        return out;
    }

    private HttpResponse<String> github(String method, String path, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com" + path))
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ekina-lab");
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private boolean seedPaperIfEmpty(Path paper, String title, String venue) throws Exception {
        Files.createDirectories(paper);
        Path main = paper.resolve("main.tex");
        if (Files.exists(main)) {
            return false;
        }
        String kit = (venue == null || venue.isBlank()) ? "ieee-conf" : venue.trim();
        Map<String, Object> applied = journalFormatService.apply(kit, paper, title);
        if (!Boolean.TRUE.equals(applied.get("ok"))) {
            applied = journalFormatService.apply("ieee-conf", paper, title);
        }
        Object v = applied.get("venue");
        String venueName = v instanceof Map<?, ?> m ? String.valueOf(m.get("name")) : kit;
        Files.writeString(paper.resolve("README.md"),
                "# " + title + "\n\nTarget venue: **" + venueName + "** (`" + kit + "`).\n"
                        + "See `VENUE.md`. Do not fall back to a bare article class.\n"
                        + "Compile: `pdflatex main && bibtex main && pdflatex main && pdflatex main`.\n",
                StandardCharsets.UTF_8);
        return true;
    }

    private String overleafProjectUrl() {
        if (blank(overleafGitUrl)) return "";
        String id = overleafGitUrl.replaceAll(".*/", "").trim();
        return id.isBlank() ? "" : "https://www.overleaf.com/project/" + id;
    }

    private String overleafHint() {
        return "Overleaf Git token 不能新建项目。在 overleaf.com 点 New Project → Blank Project（标题随便），"
                + "打开 Menu → Git，把 https://git.overleaf.com/项目ID 发我或写入 EKINA_OVERLEAF_GIT_URL。"
                + "之后伊姬娜写稿并 git push 到这个写稿桌。没有现成论文也可以。";
    }

    private String detectOverleafBranch(String authUrl) {
        try {
            ExecResult r = run(labLedgerService.root(), "git", "-c", "credential.helper=",
                    "ls-remote", "--symref", authUrl, "HEAD");
            if (r.combined.contains("refs/heads/master")) return "master";
            if (r.combined.contains("refs/heads/main")) return "main";
        } catch (Exception ignored) {
        }
        return "main";
    }

    private void setRemote(Path dir, String name, String url) throws Exception {
        ExecResult got = run(dir, "git", "remote", "get-url", name);
        if (got.ok) {
            run(dir, "git", "remote", "set-url", name, url);
        } else {
            run(dir, "git", "remote", "add", name, url);
        }
    }

    private void writeGitignore(Path dir) throws Exception {
        Path gi = dir.resolve(".gitignore");
        if (!Files.exists(gi)) {
            Files.writeString(gi, "*.aux\n*.bbl\n*.blg\n*.log\n*.out\n*.toc\n*.synctex.gz\n*.pt\n*.ckpt\n*.onnx\n",
                    StandardCharsets.UTF_8);
        }
    }

    private static ExecResult run(Path dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() < 4000) sb.append(line).append('\n');
            }
        }
        boolean ok = p.waitFor(90, TimeUnit.SECONDS) && p.exitValue() == 0;
        return new ExecResult(ok, sb.toString());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 800 ? s.substring(0, 800) : s;
    }

    private static String slugify(String slug, String title) {
        String s = slug == null || slug.isBlank() ? title : slug;
        if (s == null) s = "paper";
        s = s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\-]+", "-");
        if (s.isBlank()) s = "paper";
        if (s.length() > 40) s = s.substring(0, 40);
        return s;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapeLatex(String s) {
        return s.replace("\\", "\\textbackslash{}")
                .replace("&", "\\&")
                .replace("%", "\\%")
                .replace("#", "\\#")
                .replace("_", "\\_");
    }

    private record ExecResult(boolean ok, String combined) {}
}
