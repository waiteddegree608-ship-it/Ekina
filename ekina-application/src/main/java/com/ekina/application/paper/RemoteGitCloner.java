package com.ekina.application.paper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a remote shell snippet that clones a GitHub (or git) repo onto the SSH host.
 * No local upload — retries direct clone, then public mirrors. Fails closed if all fail.
 */
public final class RemoteGitCloner {

    private RemoteGitCloner() {}

    /**
     * @param destDir absolute remote path for the clone (e.g. .../upstream)
     * @param repoUrl https://github.com/org/repo[.git]
     * @param retries attempts per candidate URL (&gt;=1)
     */
    public static String buildCloneCommand(String destDir, String repoUrl, int retries) {
        String dest = destDir == null ? "" : destDir.trim();
        String repo = normalizeRepoUrl(repoUrl);
        if (dest.isBlank() || repo.isBlank()) {
            throw new IllegalArgumentException("destDir and repoUrl required");
        }
        int n = Math.max(1, Math.min(5, retries));
        List<String> candidates = cloneCandidates(repo);

        StringBuilder sh = new StringBuilder();
        sh.append("set -e; ");
        sh.append("DEST=").append(shellQuote(dest)).append("; ");
        sh.append("PARENT=$(dirname \"$DEST\"); mkdir -p \"$PARENT\"; ");
        sh.append("if [ -d \"$DEST/.git\" ] && { [ -d \"$DEST/models\" ] || [ -f \"$DEST/README.md\" ] || [ -f \"$DEST/main.py\" ]; }; then ");
        sh.append("echo EKINA_CLONE_SKIP already_present; ls \"$DEST\" | head; exit 0; fi; ");
        sh.append("rm -rf \"$DEST\"; ");
        sh.append("export GIT_TERMINAL_PROMPT=0; ");
        sh.append("OK=0; ");
        int i = 0;
        for (String url : candidates) {
            i++;
            sh.append("if [ \"$OK\" -eq 0 ]; then ");
            sh.append("echo EKINA_CLONE_TRY_").append(i).append(" ").append(shellEscapeForEcho(url)).append("; ");
            sh.append("ATTEMPT=1; ");
            sh.append("while [ \"$ATTEMPT\" -le ").append(n).append(" ]; do ");
            sh.append("if git clone --depth 1 ").append(shellQuote(url)).append(" \"$DEST\"; then ");
            sh.append("OK=1; echo EKINA_CLONE_OK url=").append(shellEscapeForEcho(url))
                    .append(" attempt=$ATTEMPT; break; ");
            sh.append("fi; ");
            sh.append("echo EKINA_CLONE_RETRY_$ATTEMPT; rm -rf \"$DEST\"; ATTEMPT=$((ATTEMPT+1)); sleep 2; ");
            sh.append("done; fi; ");
        }
        // Archive fallback (still remote download — not local upload)
        String ownerRepo = githubOwnerRepo(repo);
        if (ownerRepo != null) {
            String archive = "https://github.com/" + ownerRepo + "/archive/refs/heads/master.tar.gz";
            String archiveMain = "https://github.com/" + ownerRepo + "/archive/refs/heads/main.tar.gz";
            sh.append("if [ \"$OK\" -eq 0 ]; then ");
            sh.append("echo EKINA_CLONE_TRY_ARCHIVE; ");
            sh.append("TMPTGZ=\"$PARENT/_ekina_upstream.tgz\"; ");
            sh.append("if wget -q -O \"$TMPTGZ\" ").append(shellQuote(archive))
                    .append(" || wget -q -O \"$TMPTGZ\" ").append(shellQuote(archiveMain))
                    .append(" || curl -fsSL -o \"$TMPTGZ\" ").append(shellQuote(archive))
                    .append(" || curl -fsSL -o \"$TMPTGZ\" ").append(shellQuote(archiveMain)).append("; then ");
            sh.append("mkdir -p \"$DEST\"; tar -xzf \"$TMPTGZ\" -C \"$DEST\" --strip-components=1; ");
            sh.append("rm -f \"$TMPTGZ\"; OK=1; echo EKINA_CLONE_OK method=archive; ");
            sh.append("fi; fi; ");
        }
        sh.append("if [ \"$OK\" -ne 1 ]; then echo EKINA_CLONE_FAIL; exit 128; fi; ");
        sh.append("test -d \"$DEST\"; ls \"$DEST\" | head; echo EKINA_CLONE_DONE");
        return sh.toString();
    }

    public static List<String> cloneCandidates(String repoUrl) {
        String repo = normalizeRepoUrl(repoUrl);
        List<String> out = new ArrayList<>();
        out.add(repo);
        String ownerRepo = githubOwnerRepo(repo);
        if (ownerRepo != null) {
            // Public GitHub proxies commonly usable from CN GPU clouds when github.com flakes.
            out.add("https://ghproxy.net/https://github.com/" + ownerRepo + ".git");
            out.add("https://mirror.ghproxy.com/https://github.com/" + ownerRepo + ".git");
            out.add("https://gitclone.com/github.com/" + ownerRepo);
            out.add("https://github.com.cnpmjs.org/" + ownerRepo + ".git");
        }
        return out;
    }

    public static Map<String, Object> summarizeOutput(String output) {
        Map<String, Object> m = new LinkedHashMap<>();
        String out = output == null ? "" : output;
        m.put("skip", out.contains("EKINA_CLONE_SKIP"));
        m.put("done", out.contains("EKINA_CLONE_DONE") || out.contains("EKINA_CLONE_OK"));
        m.put("failed", out.contains("EKINA_CLONE_FAIL"));
        String okUrl = null;
        for (String line : out.split("\\R")) {
            if (line.contains("EKINA_CLONE_OK")) {
                okUrl = line.trim();
            }
        }
        m.put("okLine", okUrl);
        return m;
    }

    static String normalizeRepoUrl(String repoUrl) {
        if (repoUrl == null) return "";
        String u = repoUrl.trim();
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.startsWith("git@github.com:")) {
            u = "https://github.com/" + u.substring("git@github.com:".length());
        }
        if (!u.endsWith(".git") && u.contains("github.com")) {
            // keep without .git — both work; mirrors vary
        }
        return u;
    }

    static String githubOwnerRepo(String repoUrl) {
        String u = normalizeRepoUrl(repoUrl).toLowerCase(Locale.ROOT);
        int idx = u.indexOf("github.com/");
        if (idx < 0) return null;
        String rest = normalizeRepoUrl(repoUrl).substring(idx + "github.com/".length());
        if (rest.endsWith(".git")) rest = rest.substring(0, rest.length() - 4);
        String[] parts = rest.split("/");
        if (parts.length < 2) return null;
        return parts[0] + "/" + parts[1];
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static String shellEscapeForEcho(String s) {
        return s.replace("'", "").replace("\"", "").replace("`", "").replace("$", "");
    }
}
