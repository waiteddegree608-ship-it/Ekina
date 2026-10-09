package com.ekina.infrastructure.agent.video;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Small helper to run external CLI tools (yt-dlp, ffmpeg) with timeout and captured output.
 */
public final class ProcessRunner {

    private ProcessRunner() {}

    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean timedOut;

        public Result(int exitCode, String stdout, String stderr, boolean timedOut) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.timedOut = timedOut;
        }

        public boolean ok() {
            return !timedOut && exitCode == 0;
        }

        public String combinedError() {
            String s = (stderr == null ? "" : stderr.trim());
            if (s.isEmpty()) {
                s = stdout == null ? "" : stdout.trim();
            }
            if (timedOut) {
                s = "process timed out. " + s;
            }
            return s;
        }
    }

    public static Result run(List<String> command, File workDir, int timeoutSeconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) {
            pb.directory(workDir);
        }
        pb.redirectErrorStream(false);
        Process process = pb.start();

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Charset cs = StandardCharsets.UTF_8;

        Thread tOut = new Thread(() -> drain(process.getInputStream(), out, cs));
        Thread tErr = new Thread(() -> drain(process.getErrorStream(), err, cs));
        tOut.start();
        tErr.start();

        boolean finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            tOut.join(2000);
            tErr.join(2000);
            return new Result(-1, out.toString(), err.toString(), true);
        }
        tOut.join(5000);
        tErr.join(5000);
        return new Result(process.exitValue(), out.toString(), err.toString(), false);
    }

    private static void drain(java.io.InputStream in, StringBuilder target, Charset cs) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, cs))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (target.length() < 200_000) {
                    target.append(line).append('\n');
                }
            }
        } catch (Exception ignored) {
            // stream closed
        }
    }

    public static List<String> cmd(String... parts) {
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            if (p != null) {
                list.add(p);
            }
        }
        return list;
    }
}
