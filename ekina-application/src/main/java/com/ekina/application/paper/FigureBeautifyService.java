package com.ekina.application.paper;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Re-runs the paper's own {@code scripts/make_figures.py}. Never invents a number.
 */
@Service
public class FigureBeautifyService {

    public Map<String, Object> beautify(Path paperDir) {
        Map<String, Object> out = new LinkedHashMap<>();
        Path root = paperDir;
        if (Files.isRegularFile(root.resolve("paper").resolve("scripts").resolve("make_figures.py"))) {
            root = root.resolve("paper");
        }
        Path script = root.resolve("scripts").resolve("make_figures.py");
        out.put("paperDir", root.toAbsolutePath().toString());
        if (!Files.isRegularFile(script)) {
            out.put("ok", false);
            out.put("error", "没有 scripts/make_figures.py。禁止手 extra 一个对数。先让 SCRIBE 按 CSV 写脚本。");
            return out;
        }
        List<String> py = resolvePython();
        if (py.isEmpty()) {
            out.put("ok", false);
            out.put("error", "本机找不到 python。");
            return out;
        }
        List<String> cmd = new ArrayList<>(py);
        cmd.add(script.toAbsolutePath().toString());
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(root.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = p.waitFor(90, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                out.put("ok", false);
                out.put("error", "make_figures.py timed out");
                out.put("log", trim(log));
                return out;
            }
            int code = p.exitValue();
            Path figures = root.resolve("figures");
            List<String> files = new ArrayList<>();
            if (Files.isDirectory(figures)) {
                try (var s = Files.list(figures)) {
                    s.filter(f -> {
                        String n = f.getFileName().toString().toLowerCase();
                        return n.startsWith("fig_") && (n.endsWith(".png") || n.endsWith(".pdf"));
                    }).forEach(f -> files.add(f.getFileName().toString()));
                }
            }
            out.put("ok", code == 0 && !files.isEmpty());
            out.put("exit", code);
            out.put("log", trim(log));
            out.put("figures", files);
            if (code != 0) {
                out.put("error", "make_figures.py exit " + code);
            }
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return out;
        }
    }

    private static List<String> resolvePython() {
        for (List<String> cand : List.of(
                List.of("python"),
                List.of("py", "-3"),
                List.of("python3"))) {
            try {
                List<String> probe = new ArrayList<>(cand);
                probe.add("-c");
                probe.add("print(1)");
                Process p = new ProcessBuilder(probe).redirectErrorStream(true).start();
                if (p.waitFor(8, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return cand;
                }
            } catch (Exception ignored) {
            }
        }
        return List.of();
    }

    private static String trim(String s) {
        if (s == null) return "";
        s = s.strip();
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }
}
