package com.ekina.application.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Step 5 — turns innovation menu variants into concrete, reviewable code additions rather than
 * abstract marketing. For supported kinds it emits a real Python module (e.g. an SE block) under
 * {@code repro/patched/}; for the rest it records an honest "pending wiring" note. Never claims a
 * number was produced by the patch — the actual metric still comes from a real run.
 */
@Component
public class PatchApplier {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SE_BLOCK_PY = """
            # Squeeze-and-Excitation block (injected module from innovation menu: module_se_block)
            import torch
            import torch.nn as nn

            class SEBlock(nn.Module):
                \"\"\"Squeeze-and-Excitation: global-average pooling -> squeeze -> excite.\"\"\"
                def __init__(self, channels, reduction=16):
                    super(SEBlock, self).__init__()
                    r = max(1, channels // reduction)
                    self.fc = nn.Sequential(
                        nn.AdaptiveAvgPool2d(1),
                        nn.Conv2d(channels, r, 1),
                        nn.ReLU(inplace=True),
                        nn.Conv2d(r, channels, 1),
                        nn.Sigmoid(),
                    )
                def forward(self, x):
                    return x * self.fc(x)

            def wrap_block(block, reduction=16):
                \"\"\"Wrap a residual block's forward with SE after the residual path.\"\"\"
                import functools
                orig_forward = block.forward
                se = SEBlock(block.bn2.num_features, reduction=reduction)
                @functools.wraps(orig_forward)
                def new_forward(self, x):
                    import torch.nn.functional as F
                    out = F.relu(self.bn1(self.conv1(x)))
                    out = self.bn2(self.conv2(out))
                    out = se(out)
                    out += self.shortcut(x)
                    out = F.relu(out)
                    return out
                block.forward = new_forward.__get__(block, type(block))
                return block
            """;

    public Map<String, Object> apply(PaperProject p, Path root, Map<String, Object> innovPlan) {
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        Path patchedDir = root.resolve("repro").resolve("patched");
        try {
            Files.createDirectories(patchedDir);
        } catch (Exception e) {
            System.err.println("[Patch] mkdir failed: " + e.getMessage());
        }

        Object variants = innovPlan == null ? null : innovPlan.get("variants");
        if (variants instanceof List<?> list) {
            for (Object v : list) {
                if (!(v instanceof Map<?, ?> vm)) continue;
                String id = String.valueOf(vm.get("id"));
                Object kindO = vm.get("kind");
                String kind = kindO == null ? "" : String.valueOf(kindO);
                Object summaryO = vm.get("summary");
                String summary = summaryO == null ? "" : String.valueOf(summaryO);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", id);
                e.put("kind", kind);
                e.put("summary", summary);
                e.put("applied", false);

                if ("module_se_block".equals(id)) {
                    try {
                        Files.writeString(patchedDir.resolve("se_block.py"), SE_BLOCK_PY, StandardCharsets.UTF_8);
                        e.put("applied", true);
                        e.put("file", "repro/patched/se_block.py");
                        e.put("note", "Generated SEBlock + wrap_block; wire into the backbone residual block, then run a real train to obtain metrics.");
                    } catch (Exception ex) {
                        e.put("note", "write failed: " + ex.getMessage());
                    }
                } else {
                    e.put("note", "Pending wiring for '" + kind + "': intent recorded; requires a concrete upstream model to patch and a real train run.");
                }
                entries.add(e);
            }
        }
        report.put("paperId", p.getId());
        report.put("variants", entries);
        report.put("generated", entries.stream().filter(x -> Boolean.TRUE.equals(x.get("applied"))).count());

        // Best-effort syntax check of any generated python (honest; no metric claimed).
        Path se = patchedDir.resolve("se_block.py");
        if (Files.isRegularFile(se)) {
            report.put("pyCompile", pyCompile(se));
        }
        try {
            Files.writeString(patchedDir.resolve("patch_report.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Patch] write report failed: " + e.getMessage());
        }
        report.put("patchedDir", patchedDir.toAbsolutePath().toString());
        return report;
    }

    private Map<String, Object> pyCompile(Path file) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skipped", true);
        try {
            ProcessBuilder pb = new ProcessBuilder("python", "-m", "py_compile", file.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(30, TimeUnit.SECONDS);
            if (!ok) p.destroyForcibly();
            m.put("skipped", false);
            m.put("ok", ok && p.exitValue() == 0);
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", e.getMessage());
        }
        return m;
    }
}
