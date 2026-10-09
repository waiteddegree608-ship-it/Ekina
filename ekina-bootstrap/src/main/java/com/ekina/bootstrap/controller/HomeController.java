package com.ekina.bootstrap.controller;

import com.ekina.application.autonomy.AutonomyService;
import com.ekina.application.home.HomeQuotaService;
import com.ekina.application.life.LifeLoopService;
import com.ekina.infrastructure.agent.llm.DeepSeekPeakHours;
import com.ekina.infrastructure.observer.ScreenObserverService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/home")
public class HomeController {

    @Autowired
    private HomeQuotaService homeQuotaService;

    @Autowired
    private LifeLoopService lifeLoopService;

    @Autowired
    private ScreenObserverService screenObserverService;

    @Autowired
    private AutonomyService autonomyService;

    @Autowired
    private com.ekina.application.home.LlmBalanceService llmBalanceService;

    @Autowired
    private com.ekina.application.lab.CfgpuCloudService cfgpuCloudService;

    @Value("${spring.ai.openai.base-url:}")
    private String llmBaseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String llmApiKey;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String llmModel;

    @Value("${ekina.llm.protocol:auto}")
    private String llmProtocol;

    @Value("${ekina.llm.chief-provider:deepseek}")
    private String chiefProvider;

    @Value("${ekina.llm.research-provider:deepseek}")
    private String researchProvider;

    @Value("${ekina.deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${ekina.deepseek.chat-model:deepseek-v4-flash}")
    private String deepseekChatModel;

    @Value("${ekina.deepseek.reasoner-model:deepseek-v4-pro}")
    private String deepseekReasonerModel;

    @Value("${ekina.llm.skip-deepseek-peak:true}")
    private boolean skipDeepseekPeak;

    @Value("${ekina.llm.peak-alt-model:glm-5.3}")
    private String peakAltModel;

    @GetMapping("/quota")
    public Map<String, Object> quota() {
        return homeQuotaService.status();
    }

    /**
     * Masked LLM connection status for the capability board (never returns full API key).
     */
    @GetMapping("/llm")
    public Map<String, Object> llmStatus(@RequestParam(value = "refresh", required = false) Boolean refresh) {
        String key = llmApiKey == null ? "" : llmApiKey.trim();
        boolean hasKey = !key.isBlank();
        String tail = "";
        if (hasKey) {
            tail = key.length() <= 4 ? key : key.substring(key.length() - 4);
        }
        String dsKey = deepseekApiKey == null ? "" : deepseekApiKey.trim();
        boolean hasDeepseek = !dsKey.isBlank();
        String dsTail = "";
        if (hasDeepseek) {
            dsTail = dsKey.length() <= 4 ? dsKey : dsKey.substring(dsKey.length() - 4);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseUrl", llmBaseUrl == null ? "" : llmBaseUrl);
        out.put("model", llmModel == null ? "" : llmModel);
        out.put("protocol", llmProtocol == null ? "auto" : llmProtocol);
        out.put("hasApiKey", hasKey);
        out.put("apiKeyTail", tail);
        out.put("chiefProvider", chiefProvider);
        out.put("researchProvider", researchProvider);
        out.put("deepseekChatModel", deepseekChatModel);
        out.put("deepseekReasonerModel", deepseekReasonerModel);
        out.put("hasDeepseekKey", hasDeepseek);
        out.put("deepseekKeyTail", dsTail);
        out.put("ready", (hasDeepseek && "deepseek".equalsIgnoreCase(chiefProvider))
                || (hasKey && llmBaseUrl != null && !llmBaseUrl.isBlank()));
        boolean peak = DeepSeekPeakHours.isPeakNow();
        boolean skipping = skipDeepseekPeak && peak && hasDeepseek;
        out.put("deepseekPeak", peak);
        out.put("skipDeepseekPeak", skipDeepseekPeak);
        out.put("peakAltModel", peakAltModel);
        out.put("deepseekWindow", DeepSeekPeakHours.describeNow());
        out.put("activeProvider", skipping ? "opencode" : chiefProvider);
        out.put("routing", skipping
                ? ("CHIEF=opencode:" + peakAltModel + " (DeepSeek peak skip) RESEARCH=opencode CODER=opencode")
                : ("CHIEF=" + chiefProvider + " RESEARCH=" + researchProvider + " CODER=opencode SCRIBE=opencode VISION=opencode"));
        out.put("balances", Boolean.TRUE.equals(refresh) ? llmBalanceService.refresh() : llmBalanceService.snapshot());
        return out;
    }

    @GetMapping("/llm/balance")
    public Map<String, Object> llmBalance(@RequestParam(value = "refresh", required = false) Boolean refresh) {
        return Boolean.TRUE.equals(refresh) ? llmBalanceService.refresh() : llmBalanceService.snapshot();
    }

    @GetMapping("/cfgpu")
    public Map<String, Object> cfgpuStatus(@RequestParam(value = "probe", required = false) Boolean probe) {
        return cfgpuCloudService.inspect(Boolean.TRUE.equals(probe));
    }

    @PostMapping("/cfgpu")
    public Map<String, Object> cfgpuControl(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) body = Map.of();
        String action = String.valueOf(body.getOrDefault("action", "status")).toLowerCase();
        String id = body.get("instanceId") == null ? "" : String.valueOf(body.get("instanceId"));
        boolean force = Boolean.TRUE.equals(body.get("force"))
                || "true".equalsIgnoreCase(String.valueOf(body.getOrDefault("force", "")));
        return switch (action) {
            case "start" -> cfgpuCloudService.start(id);
            case "stop" -> cfgpuCloudService.stop(id, force);
            case "status", "inspect" -> cfgpuCloudService.instanceStatus(id);
            default -> cfgpuCloudService.inspect(false);
        };
    }

    @GetMapping("/autonomy")
    public Map<String, Object> autonomyStatus() {
        return autonomyService.status();
    }

    @PostMapping("/autonomy")
    public Map<String, Object> autonomyControl(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) body = Map.of();
        String action = String.valueOf(body.getOrDefault("action", "status"));
        String userId = String.valueOf(body.getOrDefault("userId", "user-1"));
        String goal = body.get("goal") == null ? "" : String.valueOf(body.get("goal"));
        return switch (action.toLowerCase()) {
            case "enable", "on", "start" -> autonomyService.enable(userId, goal);
            case "disable", "off", "stop" -> autonomyService.disable(userId, String.valueOf(body.getOrDefault("reason", "api")));
            case "tick", "run" -> autonomyService.runTickNow();
            case "goal" -> autonomyService.setGoal(goal);
            default -> autonomyService.status();
        };
    }

    @PostMapping("/quota")
    public Map<String, Object> setQuota(@RequestBody Map<String, Object> body) {
        Object gb = body.get("quotaGb");
        if (gb instanceof Number n) {
            homeQuotaService.setQuotaGb(n.longValue());
        }
        return homeQuotaService.status();
    }

    @PostMapping("/life/heartbeat")
    public Map<String, Object> heartbeat() {
        return lifeLoopService.runOnce();
    }

    @PostMapping("/peek")
    public Map<String, Object> peek(@RequestBody(required = false) Map<String, Object> body) {
        String mode = body == null ? "window" : String.valueOf(body.getOrDefault("mode", "window"));
        boolean notify = body != null && Boolean.TRUE.equals(body.get("notify"));
        return screenObserverService.peek(mode, notify);
    }
}
