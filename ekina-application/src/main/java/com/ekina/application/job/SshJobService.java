package com.ekina.application.job;

import com.ekina.application.channel.ChannelService;
import com.ekina.application.home.HomeQuotaService;
import com.ekina.application.permission.CapabilityGrant;
import com.ekina.application.permission.CapabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Bounded SSH job runner. Requires active capability kind=ssh_remote with scope:
 * <ul>
 *   <li>host, user (required)</li>
 *   <li>port (default 22)</li>
 *   <li>keyPath — private key file (auto-detects ~/.ssh/id_ed25519 / id_rsa if omitted)</li>
 *   <li>password — optional; if set, uses password auth (JSch). Prefer passwordEnv.</li>
 *   <li>passwordEnv — env var name holding password (default EKINA_SSH_PASSWORD when usePassword=true)</li>
 *   <li>workdir — remote cd before command</li>
 * </ul>
 * OpenSSH CLI is used for key-only mode; password auth cannot use BatchMode prompts,
 * so password goes through embedded JSch client.
 */
@Service
public class SshJobService {

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private CapabilityService capabilityService;

    @Autowired
    private ChannelService channelService;

    @Autowired
    private HomeQuotaService homeQuotaService;

    @Value("${ekina.jobs.ssh.enabled:true}")
    private boolean enabled;

    @Value("${ekina.jobs.ssh.timeout-sec:120}")
    private int timeoutSec;

    @Value("${ekina.reminders.default-user:user-1}")
    private String defaultUser;

    /** Optional global password from env/.env — never log this. */
    @Value("${ekina.jobs.ssh.password:}")
    private String configuredPassword;

    @Value("${ekina.jobs.ssh.default-key-path:}")
    private String configuredKeyPath;

    public Map<String, Object> runRemote(String userId, String command) {
        return runRemote(userId, command, -1, null);
    }

    public Map<String, Object> runRemoteOn(String userId, String hostHint, String command) {
        return runRemote(userId, command, -1, hostHint);
    }

    /**
     * @param timeoutOverrideSec &lt;=0 uses {@code ekina.jobs.ssh.timeout-sec}
     */
    public Map<String, Object> runRemote(String userId, String command, int timeoutOverrideSec) {
        return runRemote(userId, command, timeoutOverrideSec, null);
    }

    public Map<String, Object> runRemote(String userId, String command, int timeoutOverrideSec, String hostHint) {
        int prev = this.timeoutSec;
        if (timeoutOverrideSec > 0) {
            this.timeoutSec = timeoutOverrideSec;
        }
        try {
            return runRemoteInternal(userId, command, hostHint);
        } finally {
            this.timeoutSec = prev;
        }
    }

    private Map<String, Object> runRemoteInternal(String userId, String command) {
        return runRemoteInternal(userId, command, null);
    }

    private Map<String, Object> runRemoteInternal(String userId, String command, String hostHint) {
        Map<String, Object> result = new HashMap<>();
        String uid = userId == null ? defaultUser : userId;
        if (!enabled) {
            result.put("ok", false);
            result.put("error", "ssh jobs disabled");
            return result;
        }
        Optional<CapabilityGrant> cap = (hostHint == null || hostHint.isBlank())
                ? capabilityService.findActive(uid, "ssh_remote")
                : capabilityService.findActiveSsh(uid, hostHint);
        if (cap.isEmpty()) {
            result.put("ok", false);
            result.put("error", "AWAITING_USER_APPROVAL no active ssh_remote (with host) — stopped waiting for owner");
            channelService.append(uid, "agent", "permission_request",
                    "主人，姬娜想执行远程命令，但还没有可用的 SSH 权限（需要 host/user）。请在专线回复「同意」或选 Y。",
                    Map.of("kind", "ssh_remote", "reason", "run remote job: " + (command == null ? "" : command),
                            "command", command == null ? "" : command),
                    "pending");
            return result;
        }
        Map<String, Object> scope = cap.get().getScope() == null ? Map.of() : cap.get().getScope();
        String host = str(scope.get("host"));
        String user = str(scope.get("user"));
        String keyPath = firstNonBlank(str(scope.get("keyPath")), configuredKeyPath, detectDefaultKey());
        String workdir = str(scope.get("workdir"));
        int port = 22;
        Object portObj = scope.get("port");
        if (portObj instanceof Number n) {
            port = n.intValue();
        } else if (portObj != null && !str(portObj).isBlank()) {
            try {
                port = Integer.parseInt(str(portObj));
            } catch (NumberFormatException ignored) {
            }
        }

        String password = resolvePassword(scope);

        if (host.isBlank() || user.isBlank()) {
            result.put("ok", false);
            result.put("error", "ssh scope missing host/user");
            return result;
        }
        if (command == null || command.isBlank()) {
            result.put("ok", false);
            result.put("error", "empty command");
            return result;
        }
        // safety: reject obvious dangerous patterns unless allowDangerous.
        // Note: do NOT match "rm -rf /tmp/..." — only wipe of filesystem root.
        if (!Boolean.TRUE.equals(scope.get("allowDangerous"))) {
            String c = command.toLowerCase();
            boolean wipeRoot = java.util.regex.Pattern
                    .compile("\\brm\\s+-[a-z]*rf[a-z]*\\s+/(?:\\s|$|;|&|\\*)")
                    .matcher(c).find();
            if (wipeRoot || c.contains("mkfs") || c.contains(":(){:|:&};:")) {
                result.put("ok", false);
                result.put("error", "command blocked by safety filter");
                return result;
            }
        }

        String remoteCmd = command;
        if (!workdir.isBlank()) {
            remoteCmd = "cd " + shellQuote(workdir) + " && " + command;
        }

        String jobId = "job_" + UUID.randomUUID().toString().substring(0, 8);
        result.put("jobId", jobId);
        result.put("host", host);
        result.put("port", port);
        result.put("user", user);
        result.put("phase", "running");
        result.put("authMode", !password.isBlank() ? "password" : (!keyPath.isBlank() ? "key" : "default-agent"));
        if (!keyPath.isBlank()) {
            result.put("keyPath", keyPath);
        }

        // Fine-grained SSH start/end stays in the job ledger — never spam owner chat
        channelService.append(uid, "system", "job_status",
                "远程任务开始 " + jobId + " @ " + user + "@" + host + ":" + port
                        + " auth=" + result.get("authMode"),
                Map.of("job_id", jobId, "phase", "running", "command", command,
                        "authMode", String.valueOf(result.get("authMode")),
                        "silent", true, "kind", "project_digest"), "done");

        // Password (or explicit usePassword) → JSch. Key-only → OpenSSH CLI first, JSch key fallback.
        boolean preferPassword = !password.isBlank()
                || Boolean.TRUE.equals(scope.get("usePassword"))
                || "password".equalsIgnoreCase(str(scope.get("auth")));

        try {
            if (preferPassword) {
                if (password.isBlank()) {
                    result.put("ok", false);
                    result.put("phase", "failed");
                    result.put("error", "password auth requested but no password found. "
                            + "Set scope.password, or scope.passwordEnv, or env EKINA_SSH_PASSWORD "
                            + "(recommended), then re-approve ssh_remote.");
                } else {
                    runWithJsch(host, port, user, keyPath, password, remoteCmd, result);
                }
            } else {
                runWithOpenSshCli(host, port, user, keyPath, remoteCmd, result);
                String errOut = str(result.get("output")) + " " + str(result.get("error"));
                String fallbackPw = resolvePassword(scope);
                if (!Boolean.TRUE.equals(result.get("ok"))
                        && looksLikeAuthFailure(errOut)
                        && !fallbackPw.isBlank()) {
                    result.put("authMode", "password-fallback");
                    runWithJsch(host, port, user, keyPath, fallbackPw, remoteCmd, result);
                } else if (!Boolean.TRUE.equals(result.get("ok"))
                        && looksLikeAuthFailure(errOut)
                        && fallbackPw.isBlank()) {
                    result.put("hint", "服务器似乎要求密钥或密码登录，而姬娜这边没有可用的密钥/密码。"
                            + " 请任选其一：1) scope 加 keyPath 指向私钥；"
                            + " 2) 在 .env 写 EKINA_SSH_PASSWORD=你的密码 后重启后端；"
                            + " 3) 批准权限时带 \"auth\":\"password\" 与 passwordEnv。");
                }
            }
        } catch (Exception e) {
            result.put("ok", false);
            result.put("phase", "failed");
            result.put("error", e.getMessage());
        }

        result.put("ts", Instant.now().toString());
        // never persist password
        result.remove("password");

        try {
            Path dir = homeQuotaService.homePath().resolve("workspace").resolve("jobs").resolve(jobId);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("result.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), StandardCharsets.UTF_8);
            result.put("resultFile", dir.resolve("result.json").toString());
        } catch (Exception ignored) {
        }

        Object exit = result.get("exitCode");
        channelService.append(uid, "system", "job_status",
                "远程任务结束 " + jobId + " phase=" + result.get("phase"),
                Map.of("job_id", jobId, "phase", String.valueOf(result.get("phase")),
                        "exitCode", exit == null ? -1 : exit,
                        "silent", true, "kind", "project_digest"),
                "done");
        return result;
    }

    private void runWithOpenSshCli(String host, int port, String user, String keyPath,
                                   String remoteCmd, Map<String, Object> result) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        cmd.add("-o");
        cmd.add("BatchMode=yes");
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=accept-new");
        cmd.add("-o");
        cmd.add("ConnectTimeout=15");
        cmd.add("-o");
        cmd.add("PreferredAuthentications=publickey");
        cmd.add("-o");
        cmd.add("PubkeyAuthentication=yes");
        cmd.add("-o");
        cmd.add("PasswordAuthentication=no");
        if (!keyPath.isBlank()) {
            cmd.add("-i");
            cmd.add(keyPath);
            cmd.add("-o");
            cmd.add("IdentitiesOnly=yes");
        }
        if (port != 22) {
            cmd.add("-p");
            cmd.add(String.valueOf(port));
        }
        cmd.add(user + "@" + host);
        cmd.add(remoteCmd);

        StringBuilder out = new StringBuilder();
        int exit = -1;
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean finished = p.waitFor(Math.max(10, timeoutSec), TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            result.put("ok", false);
            result.put("phase", "failed");
            result.put("error", "timeout after " + timeoutSec + "s");
            return;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (out.length() < 50_000) {
                    out.append(line).append('\n');
                }
            }
        }
        exit = p.exitValue();
        result.put("ok", exit == 0);
        result.put("phase", exit == 0 ? "succeeded" : "failed");
        result.put("exitCode", exit);
        result.put("output", out.toString());
        if (exit != 0 && looksLikeAuthFailure(out.toString())) {
            result.put("error", "SSH key auth failed (Permission denied). "
                    + "This host may require password login — set EKINA_SSH_PASSWORD and restart, "
                    + "or provide a matching private key via keyPath.");
        }
    }

    private void runWithJsch(String host, int port, String user, String keyPath, String password,
                             String remoteCmd, Map<String, Object> result) throws Exception {
        JSch jsch = new JSch();
        if (!keyPath.isBlank() && Files.isRegularFile(Path.of(keyPath))) {
            // Optional: key + password (key passphrase is separate; here we also allow password auth)
            try {
                jsch.addIdentity(keyPath);
            } catch (Exception e) {
                // key load failed — continue with password-only
                result.put("keyLoadWarning", e.getMessage());
            }
        }
        Session session = jsch.getSession(user, host, port);
        if (password != null && !password.isBlank()) {
            session.setPassword(password);
        }
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        // Prefer password when provided; still allow publickey if identity loaded
        if (password != null && !password.isBlank()) {
            config.put("PreferredAuthentications", "password,keyboard-interactive,publickey");
        } else {
            config.put("PreferredAuthentications", "publickey");
        }
        session.setConfig(config);
        session.setTimeout(Math.max(10, timeoutSec) * 1000);
        try {
            session.connect(Math.min(30_000, Math.max(10, timeoutSec) * 1000));
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(remoteCmd);
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            channel.setErrStream(err);
            InputStream in = channel.getInputStream();
            channel.connect(10_000);
            StringBuilder out = new StringBuilder();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + Math.max(10, timeoutSec) * 1000L;
            while (true) {
                while (in.available() > 0) {
                    int n = in.read(buf, 0, buf.length);
                    if (n < 0) break;
                    if (out.length() < 50_000) {
                        out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
                if (channel.isClosed()) {
                    while (in.available() > 0) {
                        int n = in.read(buf, 0, buf.length);
                        if (n < 0) break;
                        if (out.length() < 50_000) {
                            out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        }
                    }
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    channel.disconnect();
                    session.disconnect();
                    result.put("ok", false);
                    result.put("phase", "failed");
                    result.put("error", "timeout after " + timeoutSec + "s");
                    result.put("output", out.toString());
                    return;
                }
                Thread.sleep(80);
            }
            int exit = channel.getExitStatus();
            String errText = err.toString(StandardCharsets.UTF_8);
            if (!errText.isBlank() && out.length() < 50_000) {
                out.append(errText);
            }
            channel.disconnect();
            session.disconnect();
            result.put("ok", exit == 0);
            result.put("phase", exit == 0 ? "succeeded" : "failed");
            result.put("exitCode", exit);
            result.put("output", out.toString());
            if (exit != 0 && looksLikeAuthFailure(out.toString() + errText)) {
                result.put("error", "password/key auth failed — check EKINA_SSH_PASSWORD or keyPath");
            }
        } catch (Exception e) {
            try {
                session.disconnect();
            } catch (Exception ignored) {
            }
            result.put("ok", false);
            result.put("phase", "failed");
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            result.put("error", "JSch connect failed: " + msg);
            if (looksLikeAuthFailure(msg)) {
                result.put("hint", "认证失败。请确认密码写入 .env 的 EKINA_SSH_PASSWORD=... 并重启后端，"
                        + "或把正确私钥路径放进 scope.keyPath。");
            }
        }
    }

    private String resolvePassword(Map<String, Object> scope) {
        // 1) explicit scope.password (discouraged but works)
        String direct = str(scope.get("password"));
        if (!direct.isBlank()) {
            return direct;
        }
        // 2) scope.passwordEnv or usePassword → env
        String envName = str(scope.get("passwordEnv"));
        if (envName.isBlank() && (Boolean.TRUE.equals(scope.get("usePassword"))
                || "password".equalsIgnoreCase(str(scope.get("auth"))))) {
            envName = "EKINA_SSH_PASSWORD";
        }
        if (!envName.isBlank()) {
            String fromEnv = System.getenv(envName);
            if (fromEnv != null && !fromEnv.isBlank()) {
                return fromEnv.trim();
            }
            // also allow -D system property
            String prop = System.getProperty(envName);
            if (prop != null && !prop.isBlank()) {
                return prop.trim();
            }
        }
        // 3) Spring-configured ekina.jobs.ssh.password / EKINA_SSH_PASSWORD via application.yml
        if (configuredPassword != null && !configuredPassword.isBlank()) {
            return configuredPassword.trim();
        }
        // 4) Always try EKINA_SSH_PASSWORD if set (env or .env→System property via EkinaApplication.loadEnv)
        String global = System.getenv("EKINA_SSH_PASSWORD");
        if (global == null || global.isBlank()) {
            global = System.getProperty("EKINA_SSH_PASSWORD");
        }
        if (global != null && !global.isBlank()) {
            return global.trim();
        }
        return "";
    }

    private static boolean looksLikeAuthFailure(String s) {
        if (s == null) return false;
        String l = s.toLowerCase();
        return l.contains("permission denied")
                || l.contains("authentication failed")
                || l.contains("auth fail")
                || l.contains("too many authentication")
                || l.contains("publickey")
                || l.contains("无法验证")
                || l.contains("denied (publickey");
    }

    private static String detectDefaultKey() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return "";
        Path sshDir = Path.of(home, ".ssh");
        for (String name : List.of("id_ed25519", "id_rsa", "id_ecdsa")) {
            Path k = sshDir.resolve(name);
            if (Files.isRegularFile(k)) {
                return k.toAbsolutePath().toString();
            }
        }
        return "";
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return "";
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
