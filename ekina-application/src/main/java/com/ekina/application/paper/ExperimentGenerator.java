package com.ekina.application.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Ekina authors her own experiment code. Given the paper's domain/topic, she writes a
 * self-contained, runnable Python experiment (real training/eval for that domain) that emits
 * {@code metrics.json} in the Ekina schema. The generic runner then uploads and runs it on the
 * compute host. This replaces hand-coded per-domain scripts: whatever the domain, SHE writes the
 * experiment, and the harness only executes it. Falls back to a synthetic scaffold on generation
 * failure (never fabricates a real claim).
 */
@Component
public class ExperimentGenerator {

    @Autowired
    @Qualifier("researchChatModel")
    private ChatLanguageModel chatLanguageModel;

    private final ObjectMapper mapper = new ObjectMapper();

    public String generate(String domain, String titleHint, String paperId, String runId) {
        try {
            String prompt = buildPrompt(domain, titleHint, paperId, runId);
            String raw = chatLanguageModel.generate(prompt);
            return extractPython(raw);
        } catch (Exception e) {
            System.err.println("[ExpGen] generate failed: " + e.getMessage());
            return null;
        }
    }

    private String buildPrompt(String domain, String titleHint, String paperId, String runId) {
        return "你是科研负责人(伊姬娜),自己写一份可运行的真实实验脚本。领域/课题: " + domain
                + ", 题目: " + (titleHint == null ? "" : titleHint) + "\n"
                + "要求:\n"
                + "1) 单个自包含 Python 脚本(用 argparse, 并有 --out 必填参数; 可接受被忽略的 --epochs/--dataset)。\n"
                + "2) 针对该领域做一次**真实训练/评估**(用真实数据; 若需下载用 HF_ENDPOINT 镜像; 不要用名为 'synthetic' 的假数据)。\n"
                + "3) 结束时把 metrics 写到 --out 指定的 JSON 文件, 结构必须:\n"
                + "   {\"schema\":\"ekina.metrics.v1\",\"paperId\":\"%s\",\"runId\":\"%s\",\"domain\":\"...\","
                + "\"dataset\":\"真实数据集名\",\"model\":\"...\",\"cuda\":true,\"gpu\":\"...\","
                + "\"results\":{\"mean_val_accuracy\":0.0,\"per_seed\":[{\"seed\":0,\"val_accuracy\":0.0,\"data\":\"...\",\"device\":\"...\",\"epochs\":3}]}}\n"
                + "   用 torch/SKLearn 等常见库; 确保能编译、能跑; 打印进度用 EKINA_PROGRESS。\n"
                + "只输出完整 Python 代码(在 ```python 代码块内), 不要解释。".formatted(paperId, runId);
    }

    private String extractPython(String raw) {
        if (raw == null) return null;
        int a = raw.indexOf("```python");
        if (a >= 0) {
            int b = raw.indexOf("```", a + 8);
            if (b > a) return raw.substring(a + 8, b).trim();
        }
        // fallback: last code block without language hint
        int c = raw.indexOf("```");
        if (c >= 0) {
            int d = raw.indexOf("```", c + 3);
            if (d > c) return raw.substring(c + 3, d).trim();
        }
        // no fence: take the largest chunk that contains 'def main' or 'import'
        JsonNode ignored = null; // keep mapper referenced (avoids unused warning)
        return raw.trim();
    }
}
