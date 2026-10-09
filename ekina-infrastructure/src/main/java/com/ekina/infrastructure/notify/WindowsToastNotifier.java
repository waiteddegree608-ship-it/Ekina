package com.ekina.infrastructure.notify;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Windows tray balloon / toast.
 * <p>
 * Encoding note (Chinese Windows): PowerShell 5.1 {@code -File} treats .ps1 without BOM
 * as system ANSI (GBK/CP936). A UTF-8 script then shows mojibake in BalloonTip.
 * We pass the script via {@code -EncodedCommand} (UTF-16LE Base64), which is encoding-safe.
 * Fallback: temp .ps1 written as UTF-8 <b>with BOM</b>.
 */
@Component
public class WindowsToastNotifier {

    @Value("${ekina.notify.enabled:true}")
    private boolean enabled;

    @Value("${ekina.notify.permission-dialog:true}")
    private boolean permissionDialog;

    @Value("${ekina.notify.callback-base:http://127.0.0.1:8081}")
    private String callbackBase;

    public void notify(String title, String body) {
        if (!enabled) return;
        String t = sanitize(title == null ? "伊姬娜" : title);
        String b = sanitize(body == null ? "" : body);
        String ps = String.join("\r\n",
                "Add-Type -AssemblyName System.Windows.Forms",
                "Add-Type -AssemblyName System.Drawing",
                "$n = New-Object System.Windows.Forms.NotifyIcon",
                "$n.Icon = [System.Drawing.SystemIcons]::Information",
                "$n.Visible = $true",
                "$n.BalloonTipIcon = [System.Windows.Forms.ToolTipIcon]::Info",
                "$n.BalloonTipTitle = " + psQuote(truncate(t, 63)),
                "$n.BalloonTipText = " + psQuote(truncate(b, 250)),
                "$n.ShowBalloonTip(6000)",
                "Start-Sleep -Milliseconds 6500",
                "$n.Dispose()"
        );

        try {
            if (!runEncoded(ps)) {
                runFileWithBom(ps);
            }
        } catch (Exception e) {
            System.err.println("[Notify] " + e.getMessage());
        }
    }

    /**
     * Actionable permission popup (Windows modal Yes/No). On the owner's choice it POSTs the
     * decision back to {@code /api/permissions/decide} so the capability is granted/denied without
     * touching the chat. Fire-and-forget: the dialog never blocks the agent.
     */
    public void showPermissionDialog(String msgId, String userId, String title, String body) {
        if (!enabled || !permissionDialog || msgId == null || msgId.isBlank()) return;
        String script = String.join("\r\n",
                "Add-Type -AssemblyName System.Windows.Forms",
                "$d = [System.Windows.Forms.MessageBox]::Show(" + psQuote(truncate(body, 700)) + ", "
                        + psQuote(truncate(title, 60)) + ", [System.Windows.Forms.MessageBoxButtons]::YesNo, "
                        + "[System.Windows.Forms.MessageBoxIcon]::Question)",
                "$decision = 'denied'",
                "if ($d -eq [System.Windows.Forms.DialogResult]::Yes) { $decision = 'granted' }",
                "$payload = @{ userId = " + psQuote(userId) + "; messageId = " + psQuote(msgId)
                        + "; decision = $decision } | ConvertTo-Json",
                "try { Invoke-RestMethod -Uri " + psQuote(callbackBase + "/api/permissions/decide")
                        + " -Method Post -Body $payload -ContentType 'application/json' | Out-Null } catch { }"
        );
        launch(script);
    }

    /**
     * Actionable plan/方案 popup (Yes/No). On Yes the plan card is acked (approved), on No cancelled.
     */
    public void showPlanDialog(String msgId, String userId, String title, String body) {
        if (!enabled || !permissionDialog || msgId == null || msgId.isBlank()) return;
        String script = String.join("\r\n",
                "Add-Type -AssemblyName System.Windows.Forms",
                "$d = [System.Windows.Forms.MessageBox]::Show(" + psQuote(truncate(body, 700)) + ", "
                        + psQuote(truncate(title, 60)) + ", [System.Windows.Forms.MessageBoxButtons]::YesNo, "
                        + "[System.Windows.Forms.MessageBoxIcon]::Question)",
                "$status = 'cancelled'",
                "if ($d -eq [System.Windows.Forms.DialogResult]::Yes) { $status = 'acked' }",
                "$payload = @{ userId = " + psQuote(userId) + "; id = " + psQuote(msgId)
                        + "; status = $status } | ConvertTo-Json",
                "try { Invoke-RestMethod -Uri " + psQuote(callbackBase + "/api/channel/action")
                        + " -Method Post -Body $payload -ContentType 'application/json' | Out-Null } catch { }"
        );
        launch(script);
    }

    private void launch(String ps) {
        try {
            if (!runEncoded(ps)) {
                runFileWithBom(ps);
            }
        } catch (Exception e) {
            System.err.println("[Notify] dialog launch failed: " + e.getMessage());
        }
    }

    /**
     * Preferred: -EncodedCommand with UTF-16LE (Windows PowerShell native).
     * Avoids temp files and all ANSI/UTF-8 mis-decode of Chinese.
     */
    private boolean runEncoded(String ps) throws Exception {
        // PowerShell -EncodedCommand expects UTF-16LE bytes, Base64
        byte[] utf16 = ps.getBytes(StandardCharsets.UTF_16LE);
        String b64 = Base64.getEncoder().encodeToString(utf16);
        // Command line length limit ~32k; stay well under
        if (b64.length() > 24000) {
            return false;
        }
        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden",
                "-EncodedCommand", b64
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean finished = p.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
        }
        return finished && p.exitValue() == 0;
    }

    /** Fallback: UTF-8 with BOM so PS 5.1 -File decodes Chinese correctly. */
    private void runFileWithBom(String ps) throws Exception {
        Path script = Files.createTempFile("ekina-toast-", ".ps1");
        try {
            byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] body = ps.getBytes(StandardCharsets.UTF_8);
            byte[] all = new byte[bom.length + body.length];
            System.arraycopy(bom, 0, all, 0, bom.length);
            System.arraycopy(body, 0, all, bom.length, body.length);
            Files.write(script, all);

            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden",
                    "-File", script.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } finally {
            try {
                Files.deleteIfExists(script);
            } catch (Exception ignored) {
            }
        }
    }

    private static String psQuote(String s) {
        // Single-quoted PS string: only ' needs doubling
        return "'" + s.replace("'", "''") + "'";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** Strip chars that break balloon or look like binary garbage. */
    private static String sanitize(String s) {
        if (s == null) return "";
        // Normalize newlines to spaces for tray balloon
        String out = s.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        // Remove unpaired surrogates / control chars except tab
        StringBuilder sb = new StringBuilder(out.length());
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (c == '\t' || c >= 0x20) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }
}
