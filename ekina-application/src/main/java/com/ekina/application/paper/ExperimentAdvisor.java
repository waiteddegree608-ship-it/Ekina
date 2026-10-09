package com.ekina.application.paper;

import dev.langchain4j.model.chat.ChatLanguageModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ekina decides her own next experiment. Given the real (measured) results so far, she reasons —
 * with her own language model — about what to try next (EMA, CFG, more epochs, LR, model width...)
 * rather than following a hard-coded ladder. Returns {@code null} when she judges she should stop.
 *
 * <p>This is the "run -> analyze -> optimize/innovate" loop where the agent, not the scaffold,
 * owns the research decision.
 */
@Component
public class ExperimentAdvisor {

    @Autowired
    @Qualifier("researchChatModel")
    private ChatLanguageModel chatLanguageModel;

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private ExperimentOptimizer fallback;

    /** Propose the next experiment config; null = stop. Falls back to the ladder if the LLM errors. */
    public Map<String, Object> propose(Double prevFid, Map<String, Object> prevHyper,
                                       int iteration, int maxIterations) {
        if (iteration >= maxIterations) return null;
        boolean prevPoor = prevFid == null || prevFid > 40.0;
        if (!prevPoor && iteration > 0) return null;   // already good enough
        try {
            String prompt = buildPrompt(prevFid, prevHyper, iteration);
            String raw = chatLanguageModel.generate(prompt);
            JsonNode n = parseJsonObject(raw);
            if (n == null) return fallback.hyperForIteration(iteration, 20);
            if (n.path("stop").asBoolean(false)) return null;
            JsonNode cfg = n.path("config");
            if (!cfg.isObject()) return fallback.hyperForIteration(iteration, 20);
            Map<String, Object> hyper = new LinkedHashMap<>();
            if (cfg.has("ch")) hyper.put("ch", cfg.path("ch").asInt(96));
            if (cfg.has("epochs")) hyper.put("epochs", cfg.path("epochs").asInt(20));
            if (cfg.has("ema")) hyper.put("ema", cfg.path("ema").asBoolean(false));
            if (cfg.has("cfg")) hyper.put("cfg", cfg.path("cfg").asBoolean(false));
            if (cfg.has("lr")) hyper.put("lr", cfg.path("lr").asDouble(2e-4));
            hyper.put("rationale", n.path("rationale").asText(""));
            return hyper;
        } catch (Exception e) {
            System.err.println("[Advisor] LLM propose failed, fallback ladder: " + e.getMessage());
            Map<String, Object> h = fallback.hyperForIteration(iteration, 20);
            h.put("rationale", "fallback ladder");
            return h;
        }
    }

    private String buildPrompt(Double prevFid, Map<String, Object> prevHyper, int iteration) {
        return "你是科研负责人(伊姬娜),在自主改进扩散模型。之前真实实验(你自己跑的,数字真实):\n"
                + "- 当前 FID=" + (prevFid == null ? "?" : prevFid) + " (越低越好)"
                + ", 上一配置=" + (prevHyper == null ? "{}" : prevHyper) + "\n"
                + "目标: 降低 FID。请给出**下一次实验的具体改动**(如 EMA、classifier-free guidance、更多 epoch、调整学习率、加/减模型宽度等),要具体、可执行、是真实可辩护的改动。\n"
                + "只输出一个 JSON 对象,格式: {\"stop\": false, \"rationale\": \"一两句\", \"config\": {\"ch\":96,\"epochs\":30,\"ema\":true,\"cfg\":false,\"lr\":2e-4}}\n"
                + "若你认为已经改进不动或已达到目标,令 \"stop\": true。";
    }

    private JsonNode parseJsonObject(String raw) {
        if (raw == null) return null;
        int a = raw.indexOf('{');
        int b = raw.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            return mapper.readTree(raw.substring(a, b + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
