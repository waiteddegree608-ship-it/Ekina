package com.ekina.infrastructure.agent.tools;

import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.annot.PdfAnnotation;
import com.itextpdf.kernel.pdf.annot.PdfTextAnnotation;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import com.itextpdf.kernel.pdf.canvas.parser.EventType;
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData;
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo;
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener;
import java.util.Set;
import java.util.Collections;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.apache.poi.xslf.usermodel.SlideLayout;

import java.awt.*;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ekina.application.agent.AgentToolboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * AI Agent Toolset implementing core capabilities (Search, PDF Annotation, Office editor, PPT generation, Video STT).
 * Annotated with LangChain4j @Tool to allow dynamic discovery and schema mapping for Qwen/Gemini models.
 */
@Component
public class AgentToolbox implements AgentToolboxService {

    @Autowired
    private com.ekina.domain.agent.WebSearchService webSearchService;

    @Autowired
    private com.ekina.domain.rag.DocumentParser documentParser;

    @Autowired
    private com.ekina.infrastructure.agent.mcp.McpClientManager mcpClientManager;

    @Autowired
    private com.ekina.infrastructure.agent.video.VideoWatchService videoWatchService;

    @Autowired
    private com.ekina.application.home.HomeQuotaService homeQuotaService;

    @Autowired
    private com.ekina.application.home.LlmBalanceService llmBalanceService;

    @Autowired
    private com.ekina.application.channel.ChannelService channelService;

    @Autowired
    private com.ekina.application.contact.AgentEmotionService agentEmotionService;

    @Autowired
    private com.ekina.infrastructure.observer.ScreenObserverService screenObserverService;

    @Autowired
    private com.ekina.application.life.LifeLoopService lifeLoopService;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.ekina.application.autonomy.AutonomyService autonomyService;

    @Autowired
    private com.ekina.application.multiagent.MultiAgentOrchestrator multiAgentOrchestrator;

    @Autowired
    private com.ekina.application.multiagent.MissionOrchestrator missionOrchestrator;

    @Autowired
    private com.ekina.application.paper.PaperOrchestrator paperOrchestrator;

    @Autowired
    private com.ekina.application.paper.LiteratureClient literatureClient;

    @Autowired
    private com.ekina.application.paper.TopicScout topicScout;

    @Autowired
    private com.ekina.application.job.SshJobService sshJobService;

    @Autowired
    private com.ekina.application.permission.CapabilityService capabilityService;

    @Autowired
    private com.ekina.infrastructure.agent.explore.ExploreService exploreService;

    @Autowired
    private com.ekina.application.state.StateCardService stateCardService;

    @Autowired
    private com.ekina.application.agent.AgentModeService agentModeService;

    @Autowired
    private com.ekina.application.reminder.ReminderService reminderService;

    @Autowired
    private com.ekina.application.project.ProjectBoardService projectBoardService;

    @Autowired
    private com.ekina.application.agent.LocalFileIndexService localFileIndexService;

    @Autowired
    private com.ekina.application.lab.LabLedgerService labLedgerService;

    @Autowired
    private com.ekina.application.lab.GitRemoteService gitRemoteService;

    @Autowired
    private com.ekina.application.paper.JournalFormatService journalFormatService;

    @Autowired
    private com.ekina.application.paper.PaperAuditService paperAuditService;

    @Autowired
    private com.ekina.application.paper.FigureBeautifyService figureBeautifyService;

    @Autowired
    private com.ekina.application.lab.ComputeHostRegistry computeHostRegistry;

    @Autowired
    private com.ekina.application.lab.CfgpuCloudService cfgpuCloudService;

    @Value("${spring.ai.openai.api-key}")
    private String siliconFlowApiKey;

    @Value("${ekina.reminders.default-user:user-1}")
    private String defaultUser;

    @Value("${ekina.tools.pexels-api-key:}")
    private String pexelsApiKey;

    @Value("${ekina.sandbox-dir:./sandbox}")
    private String sandboxDir;

    @Value("${ekina.autonomy.vault-path:./AgentHome/notes}")
    private String vaultPath;

    @Value("${ekina.agent-home:./AgentHome}")
    private String agentHome;

    private final RestClient restClient = RestClient.builder().build();

    @Tool("Search the open web (DuckDuckGo free fallback; optional SearchAPI if key configured). Use for news/memes/general facts. For Bilibili/anime prefer exploreBilibili.")
    public String searchWeb(@P("The search query keyword") String query) {
        return webSearchService.search(query);
    }

    @Tool("Explore Bilibili / culture feed WITHOUT paid search keys. mode: hot|search|bangumi|meme|web|auto. query optional (used for search/meme/web). Returns real titles+links.")
    public String exploreBilibili(
            @P("mode: hot, search, bangumi, meme, web, or auto") String mode,
            @P("optional keyword for search/meme/web") String query) {
        try {
            return exploreService.explore(mode, query);
        } catch (Exception e) {
            return "exploreBilibili failed: " + e.getMessage();
        }
    }

    @Tool("Read the agent's mutable State Card (mood, focus, open_loops, last_explore, interests...).")
    public String getStateCard() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(stateCardService.get());
        } catch (Exception e) {
            return "getStateCard failed: " + e.getMessage();
        }
    }

    @Tool("Update agent's State Card fields. patchJson is a JSON object, e.g. {\"mood\":\"开心\",\"focus\":\"追新番\",\"last_explore\":\"B站热门...\"}.")
    public String updateStateCard(@P("JSON object of fields to merge into state card") String patchJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> patch = om.readValue(patchJson, java.util.Map.class);
            return om.writerWithDefaultPrettyPrinter().writeValueAsString(stateCardService.update(patch));
        } catch (Exception e) {
            return "updateStateCard failed: " + e.getMessage();
        }
    }

    @Tool("Dismiss a reminder when user says they already did it. ruleId like rem_medicine or rem_standup, or pass message card id as ruleId if unknown.")
    public String dismissReminder(
            @P("ruleId e.g. rem_medicine, rem_standup, or keywords 药/起来") String ruleIdOrKeyword) {
        try {
            String key = ruleIdOrKeyword == null ? "" : ruleIdOrKeyword.trim();
            int n;
            if (key.startsWith("rem_") || key.startsWith("msg_")) {
                if (key.startsWith("msg_")) {
                    reminderService.ack(defaultUser, key, "ack");
                    n = 1;
                } else {
                    n = reminderService.dismissPendingForRule(defaultUser, key, "acked_by_tool");
                }
            } else {
                n = reminderService.handleNaturalLanguageCompletion(defaultUser, "已经" + key);
            }
            return "dismissed " + n + " reminder card(s) for " + key;
        } catch (Exception e) {
            return "dismissReminder failed: " + e.getMessage();
        }
    }

    @Tool("Update an existing channel card by id (plan/permission/reminder/system). Can change text and/or merge payload JSON and status.")
    public String updateChannelCard(
            @P("card message id") String id,
            @P("new text, or empty to keep") String text,
            @P("status e.g. pending|done|cancelled, or empty to keep") String status,
            @P("optional JSON object merged into payload") String payloadJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> patch = null;
            if (payloadJson != null && !payloadJson.isBlank()) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> p = om.readValue(payloadJson, java.util.Map.class);
                patch = p;
            }
            var msg = channelService.updateContent(defaultUser, id,
                    (text == null || text.isBlank()) ? null : text,
                    (status == null || status.isBlank()) ? null : status,
                    patch);
            if (msg == null) {
                return "card not found: " + id;
            }
            return "updated card " + msg.getId() + " status=" + msg.getStatus() + " text=" + msg.getText();
        } catch (Exception e) {
            return "updateChannelCard failed: " + e.getMessage();
        }
    }

    @Tool("Get Agent Home disk quota status (used/limit/ratio). Use before large downloads.")
    public String getHomeQuota() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(homeQuotaService.status());
        } catch (Exception e) {
            return "quota error: " + e.getMessage();
        }
    }

    @Tool("List folders the PC-assistant agent may access (builtin workspace/inbox + owner-configured roots from the 本机 capability board).")
    public String listAllowedFolders() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(localFileIndexService.listRoots());
        } catch (Exception e) {
            return "listAllowedFolders failed: " + e.getMessage();
        }
    }

    @Tool("Build/refresh a lightweight file index under an allowed folder for later fuzzy search (name/path/snippet).")
    public String indexLocalFolder(
            @P("Absolute folder path under allowed roots; empty = AgentHome/workspace/agent") String folderPath,
            @P("Max files to index (default 400)") Integer maxFiles) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(localFileIndexService.indexFolder(folderPath, maxFiles == null ? 400 : maxFiles));
        } catch (Exception e) {
            return "indexLocalFolder failed: " + e.getMessage();
        }
    }

    @Tool("Search the local file index (fuzzy: filename, path, text snippet, mtime). Use after indexLocalFolder.")
    public String searchLocalFiles(
            @P("Query keywords, e.g. 开题报告 2024 docx") String query,
            @P("Max hits (default 12)") Integer limit) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(localFileIndexService.search(query, limit == null ? 12 : limit));
        } catch (Exception e) {
            return "searchLocalFiles failed: " + e.getMessage();
        }
    }

    @Tool("List files in an allowed local folder for the PC assistant (not Ekina vault). Prefer this over vault browsing.")
    public String listLocalFiles(
            @P("Absolute folder path under allowed roots; empty = workspace/agent") String folderPath) {
        try {
            java.nio.file.Path base = (folderPath == null || folderPath.isBlank())
                    ? java.nio.file.Path.of(agentHome, "workspace", "agent")
                    : java.nio.file.Path.of(folderPath);
            java.nio.file.Path abs = base.toAbsolutePath().normalize();
            if (!localFileIndexService.isUnderAllowed(abs)) {
                return "拒绝：不在代理人允许根目录。先 listAllowedFolders，或请主人在能力板「本机」粘贴目录保存 / 拖文件进对话框。收到: " + abs;
            }
            if (!java.nio.file.Files.isDirectory(abs)) {
                return "不是目录: " + abs;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("listing ").append(abs).append("\n");
            try (var stream = java.nio.file.Files.list(abs)) {
                stream.sorted().limit(200).forEach(p -> {
                    try {
                        String mark = java.nio.file.Files.isDirectory(p) ? "[D] " : "[F] ";
                        long size = java.nio.file.Files.isRegularFile(p) ? java.nio.file.Files.size(p) : 0;
                        sb.append(mark).append(p.getFileName());
                        if (size > 0) sb.append(" (").append(size).append(" B)");
                        sb.append("\n");
                    } catch (Exception e) {
                        sb.append("[?] ").append(p.getFileName()).append("\n");
                    }
                });
            }
            return sb.toString();
        } catch (Exception e) {
            return "listLocalFiles failed: " + e.getMessage();
        }
    }

    @Tool("List files in 姬娜自己的空间. root=vault (./AgentHome/notes Obsidian房间) | home (AgentHome) | absolute path under those roots. Use this to browse diaries/surprises/notes; then readDocument to read a file.")
    public String listHomeFiles(
            @P("Which root: vault | home | or an absolute path under vault/home") String root,
            @P("Optional relative subfolder under that root, e.g. 04_Surprises or diary") String relativePath) {
        try {
            // PC assistant should use listLocalFiles / allowed roots — keep vault for Ekina
            String contactId = com.ekina.application.project.ChatSessionContext.contactId();
            if (contactId != null && "agent".equalsIgnoreCase(contactId)) {
                String folder = root;
                if (folder == null || folder.isBlank() || "home".equalsIgnoreCase(folder)) {
                    folder = java.nio.file.Path.of(agentHome, "workspace", "agent").toString();
                }
                if (relativePath != null && !relativePath.isBlank()) {
                    folder = java.nio.file.Path.of(folder, relativePath).toString();
                }
                return listLocalFiles(folder);
            }
            java.nio.file.Path base;
            String r = root == null ? "vault" : root.trim();
            if (r.isEmpty() || "vault".equalsIgnoreCase(r) || "room".equalsIgnoreCase(r) || "房间".equals(r)) {
                base = java.nio.file.Path.of(vaultPath);
            } else if ("home".equalsIgnoreCase(r) || "agenthome".equalsIgnoreCase(r)) {
                base = java.nio.file.Path.of(agentHome);
            } else {
                base = java.nio.file.Path.of(r);
            }
            java.nio.file.Path target = base;
            if (relativePath != null && !relativePath.isBlank()) {
                String rel = relativePath.replace("\\", "/");
                while (rel.startsWith("/")) rel = rel.substring(1);
                target = base.resolve(rel).normalize();
            }
            java.nio.file.Path vaultNorm = java.nio.file.Path.of(vaultPath).toAbsolutePath().normalize();
            java.nio.file.Path homeNorm = java.nio.file.Path.of(agentHome).toAbsolutePath().normalize();
            java.nio.file.Path abs = target.toAbsolutePath().normalize();
            if (!(abs.startsWith(vaultNorm) || abs.startsWith(homeNorm))) {
                return "拒绝：只能浏览 vault(" + vaultNorm + ") 或 AgentHome(" + homeNorm + ")，收到: " + abs;
            }
            if (!java.nio.file.Files.exists(abs)) {
                return "路径不存在: " + abs;
            }
            if (!java.nio.file.Files.isDirectory(abs)) {
                return "不是目录: " + abs;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("listing ").append(abs).append("\n");
            try (var stream = java.nio.file.Files.list(abs)) {
                stream.sorted().limit(200).forEach(p -> {
                    try {
                        String mark = java.nio.file.Files.isDirectory(p) ? "[D] " : "[F] ";
                        long size = java.nio.file.Files.isRegularFile(p) ? java.nio.file.Files.size(p) : 0;
                        sb.append(mark).append(p.getFileName());
                        if (size > 0) sb.append(" (").append(size).append(" B)");
                        sb.append("\n");
                    } catch (Exception e) {
                        sb.append("[?] ").append(p.getFileName()).append("\n");
                    }
                });
            }
            return sb.toString();
        } catch (Exception e) {
            return "listHomeFiles failed: " + e.getMessage();
        }
    }

    @Tool("Send a short companion chat to the owner (may toast). ONLY when you finished something worth telling (concrete result / path / task outcome). Do NOT use for empty mood pings, fake surprises, or every autonomy tick. Prefer silence over fluff.")
    public String sendCompanionMessage(
            @P("Short message with real information (result summary), Chinese OK") String text,
            @P("Optional tag: report|ping|life|surprise — prefer report for real outcomes") String kind) {
        try {
            String t = text == null ? "" : text.trim();
            if (t.isBlank()) {
                return "sendCompanionMessage failed: empty text";
            }
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("source", "tool");
            // Pin destination to the speaking contact — never inherit the other IM thread
            String contactId = com.ekina.application.project.ChatSessionContext.contactId();
            if (contactId == null || contactId.isBlank() || "ekina".equalsIgnoreCase(contactId)) {
                contactId = "ekina";
                payload.put("contactId", "ekina");
                payload.put("threadId", com.ekina.application.contact.ContactRegistry.THREAD_EKINA);
            } else {
                contactId = "agent";
                payload.put("contactId", "agent");
                payload.put("threadId", com.ekina.application.contact.ContactRegistry.THREAD_AGENT);
            }
            String threadId = String.valueOf(payload.get("threadId"));
            String k = kind == null ? "" : kind.trim().toLowerCase();
            if (!k.isBlank() && !"empty".equals(k) && !"null".equals(k)) {
                payload.put("kind", k);
                if ("surprise".equals(k) || "report".equals(k)) {
                    // report uses same owner-visible channel path; toast title differs by kind
                    payload.put("surprise", "surprise".equals(k));
                    if ("report".equals(k)) {
                        payload.put("report", true);
                    }
                }
            }
            var msg = channelService.sendCompanionChat(defaultUser, t, payload);
            return "sent companion chat id=" + msg.getId() + " ts=" + msg.getTs()
                    + " contact=" + contactId + " thread=" + threadId;
        } catch (Exception e) {
            return "sendCompanionMessage failed: " + e.getMessage();
        }
    }

    @Tool("Read the owner-facing research/work board for a project (now / next / done / findings / timeline). Use this instead of dumping step logs into chat. If projectId is empty, uses the current session project.")
    public String getProjectBoard(
            @P("Project id from the dock (prj_... / paper_... / daily). Empty = current session project") String projectId) {
        try {
            String id = resolveBoardProjectId(projectId);
            if (id == null) {
                return "getProjectBoard failed: no project id (pass the dock card id, or @ a project first)";
            }
            return new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(projectBoardService.get(id));
        } catch (Exception e) {
            return "getProjectBoard failed: " + e.getMessage();
        }
    }

    @Tool("Update research board: goal,status,now,next[],questions[],nextDeliverable; roadmap[] has id,title,status(planned|running|done|blocked|conditional),purpose,deliverable,acceptance,condition,resources,dependsOn[],costMin,costMax,currency(CNY|USD),estimateBasis. Unknown costs=null; numeric costs require a basis. decisions[] has id,kind(consultation|authorization),title,recommendation,impact,permissionId. Authorization uses existing permission tools, never implies approval. guidanceReply:{id,effect} replies to saved guidance. Read collaboration/guidance first; owner settings cannot be overwritten. Append evidence with researchUpdate:{expectedRevision:research.revision,records:[...]}, max20 per call. Every record has stable id and type. Types/fields: attempt(question,method,result,outcome=supported|refuted|inconclusive|failed,artifacts[],limitations,stepId); evidence(title,kind=literature|experiment|analysis,source,summary,conditions,producer,attemptId optional); review(evidenceId,reviewer,summary,artifact,method=source_check|execution_check|independent_reproduction|model_review,verdict=supports|concerns|rejects); conclusion(title,statement,conditions,evidenceIds[],dependsOn[] of earlier conclusions,limitations,stepId); withdrawal(targetId,reason); checkpoint(question,nextAction,continueWhen,stopWhen,resources,basisIds[],costMin,costMax,currency,estimateBasis). References must exist earlier in this project, including earlier in batch. Records are immutable; retry with same ids/content, reread on version conflict, withdraw errors and add corrected records under new ids. assessment is server-derived and cannot be written. Reviewed means recorded supportive checks, never proof of truth; model review is not reproduction. Plans do not execute, authorize or reserve costs.")
    public String updateProjectBoard(
            @P("Project id from the dock") String projectId,
            @P("JSON patch with researchUpdate, roadmap, nextDeliverable, decisions or guidanceReply; legacy goal/status/now/next/questions also supported") String patchJson) {
        try {
            String id = resolveBoardProjectId(projectId);
            if (id == null) {
                return "updateProjectBoard failed: no project id";
            }
            Map<String, Object> patch = new LinkedHashMap<>();
            if (patchJson != null && !patchJson.isBlank()) {
                ObjectMapper om = new ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = om.readValue(patchJson, Map.class);
                if (parsed != null) patch.putAll(parsed);
            }
            return new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(projectBoardService.upsert(id, patch));
        } catch (Exception e) {
            return "updateProjectBoard failed: " + e.getMessage();
        }
    }

    @Tool("Append one event to a project's human timeline. status: running|succeeded|failed|blocked|insight|note. Use this when you finish/start/get stuck on a research step. Owner sees it on the board, not as chat spam.")
    public String appendProjectEvent(
            @P("Project id from the dock") String projectId,
            @P("Short title, e.g. 文献检索完成 / CFG网格跑完 / 假设被数据否定") String title,
            @P("Optional detail the owner might need later (paths, key numbers, why it failed)") String detail,
            @P("running|succeeded|failed|blocked|insight|note") String status) {
        try {
            String id = resolveBoardProjectId(projectId);
            if (id == null) {
                return "appendProjectEvent failed: no project id";
            }
            return new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(projectBoardService.appendEvent(id, title, detail, status));
        } catch (Exception e) {
            return "appendProjectEvent failed: " + e.getMessage();
        }
    }

    @Tool("List owner-visible project boards (id/title/status/now/next) so you can pick the right projectId when several topics run in parallel.")
    public String listProjectBoards() {
        try {
            String uid = defaultUser == null || defaultUser.isBlank() ? "user-1" : defaultUser;
            String contact = com.ekina.application.project.ChatSessionContext.contactId();
            return new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(projectBoardService.listSummaries(uid, contact));
        } catch (Exception e) {
            return "listProjectBoards failed: " + e.getMessage();
        }
    }

    private String resolveBoardProjectId(String projectId) {
        if (projectId != null && !projectId.isBlank() && !"null".equalsIgnoreCase(projectId)
                && !"daily".equalsIgnoreCase(projectId.trim())) {
            return projectId.trim();
        }
        String fromCtx = com.ekina.application.project.ChatSessionContext.projectId();
        if (fromCtx != null && !fromCtx.isBlank() && !"daily".equalsIgnoreCase(fromCtx)) {
            return fromCtx;
        }
        try {
            String uid = defaultUser == null || defaultUser.isBlank() ? "user-1" : defaultUser;
            String contact = com.ekina.application.project.ChatSessionContext.contactId();
            var rows = projectBoardService.listSummaries(uid, contact);
            if (rows != null && !rows.isEmpty()) {
                return String.valueOf(rows.get(0).get("id"));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Tool("Read 代理人 emotion/bond card (surface mood vs inner affection). For 代理人 only.")
    public String getAgentEmotion() {
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(agentEmotionService.get());
        } catch (Exception e) {
            return "getAgentEmotion failed: " + e.getMessage();
        }
    }

    @Tool("Patch 代理人 emotion fields. patchJson e.g. {\"surfaceMood\":\"冷淡疏离\",\"maskIntegrity\":70,\"lastTrigger\":\"主人夸奖\"}. Gauges 0-100: innerAffection,innerTenderness,irritation,possessiveness,trust,maskIntegrity. Do not wipe backstory.")
    public String updateAgentEmotion(
            @P("JSON object of fields to update") String patchJson) {
        try {
            ObjectMapper om = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> patch = om.readValue(patchJson == null ? "{}" : patchJson, Map.class);
            Map<String, Object> updated = agentEmotionService.update(patch);
            return om.writerWithDefaultPrettyPrinter().writeValueAsString(updated);
        } catch (Exception e) {
            return "updateAgentEmotion failed: " + e.getMessage();
        }
    }

    @Tool("Create a plan card for the user to choose among options. optionsJson is a JSON array of {id,label,summary}.")
    public String createPlanCard(
            @P("Plan title") String title,
            @P("JSON array of options: [{id,label,summary}]") String optionsJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Object options = om.readValue(optionsJson, Object.class);
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("plan_id", "plan_" + System.currentTimeMillis());
            payload.put("title", title);
            payload.put("options", options);
            payload.put("status", "pending");
            var msg = channelService.append(defaultUser, "agent", "plan",
                    "主人，请选择方案：「" + title + "」", payload, "pending");
            return "Plan card created: " + msg.getId() + " title=" + title;
        } catch (Exception e) {
            return "createPlanCard failed: " + e.getMessage();
        }
    }

    @Tool("Request user permission (disk_quota, ssh_remote, screen_peek, write_outside_home, etc). Creates a permission_request card. After calling this you MUST STOP and wait — do not keep planning or call more tools until the user approves.")
    public String requestPermission(
            @P("Permission kind: disk_quota|ssh_remote|screen_peek|write_outside_home|network|local_gpu") String kind,
            @P("Human-readable reason") String reason,
            @P("Optional JSON scope object as string. For ssh_remote include host,user,port,keyPath,workdir.") String scopeJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("kind", kind);
            payload.put("reason", reason);
            if (scopeJson != null && !scopeJson.isBlank()) {
                payload.put("scope", om.readValue(scopeJson, Object.class));
            }
            payload.put("risk", kind != null && kind.contains("ssh") ? "high" : "medium");
            String text = "主人，姬娜需要权限「" + kind + "」：" + reason
                    + "。请在专线回复「同意」/「拒绝」，或按提示选择 Y/N。";
            var msg = channelService.append(defaultUser, "agent", "permission_request", text, payload, "pending");
            // Token AWAITING_USER_APPROVAL is detected by ReActAgentExecutor to halt the loop.
            return "AWAITING_USER_APPROVAL cardId=" + msg.getId()
                    + " kind=" + kind
                    + " — STOP now. Tell the owner to reply 同意 or 拒绝 in the companion channel. "
                    + "Do NOT call more tools until approved.";
        } catch (Exception e) {
            return "requestPermission failed: " + e.getMessage();
        }
    }

    @Tool("Peek at the user's screen. mode=window (foreground title only) or screen (screenshot + optional vision). Respects blacklist.")
    public String peekScreen(@P("mode: window or screen") String mode) {
        try {
            java.util.Map<String, Object> r = screenObserverService.peek(mode, false);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(r);
        } catch (Exception e) {
            return "peekScreen failed: " + e.getMessage();
        }
    }

    @Tool("Run one life-loop heartbeat now (diary + optional idle bilibili discovery, no full watchVideo).")
    public String runLifeHeartbeat() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(lifeLoopService.runOnce());
        } catch (Exception e) {
            return "runLifeHeartbeat failed: " + e.getMessage();
        }
    }

    @Tool("Enable free-rein autonomy mode: background ticks keep working on a long goal without waiting for the owner. Use when owner says 随便用/全权/开始自主.")
    public String enableAutonomy(@P("Long-horizon goal text from the owner") String goal) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(autonomyService.enable(defaultUser, goal));
        } catch (Exception e) {
            return "enableAutonomy failed: " + e.getMessage();
        }
    }

    @Tool("Disable free-rein autonomy mode (stop background ticks).")
    public String disableAutonomy(@P("Optional reason") String reason) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(autonomyService.disable(defaultUser, reason));
        } catch (Exception e) {
            return "disableAutonomy failed: " + e.getMessage();
        }
    }

    @Tool("Get autonomy mode status (enabled, goal, last tick summary, ticks today).")
    public String getAutonomyStatus() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(autonomyService.status());
        } catch (Exception e) {
            return "getAutonomyStatus failed: " + e.getMessage();
        }
    }

    @Tool("Get the current agent execution mode (PLAN | NORMAL | TURBO). PLAN=propose-only before approval; NORMAL=default; TURBO=more autonomous/faster.")
    public String getAgentMode() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(agentModeService.status(defaultUser));
        } catch (Exception e) {
            return "getAgentMode failed: " + e.getMessage();
        }
    }

    @Tool("Set the agent execution mode: PLAN (propose-first, waits for approval), NORMAL (default), or TURBO (more autonomous). Use when the owner explicitly asks to switch modes.")
    public String setAgentMode(@P("PLAN | NORMAL | TURBO") String mode) {
        try {
            com.ekina.application.agent.AgentMode m = com.ekina.application.agent.AgentMode.from(mode);
            agentModeService.set(defaultUser, m);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(agentModeService.status(defaultUser));
        } catch (Exception e) {
            return "setAgentMode failed: " + e.getMessage();
        }
    }

    @Tool("Chief only: dispatch a task to a worker Ekina manages. assignee=CODER|OPENCODE (code/SSH/train), SCRIBE|排版 (academic writing/venue templates/figure polish), or CLAW|OPENCLAW (channels). Do NOT do heavy coding or LaTeX yourself.")
    public String dispatchAgentTask(
            @P("CODER, SCRIBE, or CLAW") String assignee,
            @P("Short title") String title,
            @P("Detailed brief for the worker") String brief) {
        try {
            var t = multiAgentOrchestrator.dispatch(defaultUser, assignee, title, brief, null);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(t);
        } catch (Exception e) {
            return "dispatchAgentTask failed: " + e.getMessage();
        }
    }

    @Tool("List recent multi-agent tasks (queued/running/done) for the Chief to report progress.")
    public String listAgentTasks(@P("Max items, default 10") int limit) {
        try {
            int n = limit <= 0 ? 10 : Math.min(50, limit);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(multiAgentOrchestrator.list(defaultUser, n));
        } catch (Exception e) {
            return "listAgentTasks failed: " + e.getMessage();
        }
    }

    @Tool("Get one multi-agent task by id, including result text and jobDir/reportDir so Chief can see OpenCode outputs.")
    public String getAgentTask(@P("Task id like task_xxxxxxxxxxxx") String taskId) {
        try {
            var opt = multiAgentOrchestrator.get(taskId);
            if (opt.isEmpty()) {
                return "task not found: " + taskId;
            }
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(opt.get());
        } catch (Exception e) {
            return "getAgentTask failed: " + e.getMessage();
        }
    }

    @Tool("Read OpenCode job artifacts for a CODER task: lists files under jobDir and returns bridge.md / RESULT.md if present.")
    public String readOpenCodeArtifacts(@P("Task id") String taskId) {
        try {
            var opt = multiAgentOrchestrator.get(taskId);
            if (opt.isEmpty()) {
                return "task not found: " + taskId;
            }
            var t = opt.get();
            Object job = t.getMeta() != null ? t.getMeta().get("jobDir") : null;
            Object report = t.getMeta() != null ? t.getMeta().get("reportDir") : null;
            java.nio.file.Path jobDir = job != null
                    ? java.nio.file.Path.of(String.valueOf(job))
                    : java.nio.file.Path.of("./sandbox/opencode-jobs", taskId);
            java.nio.file.Path reportDir = report != null
                    ? java.nio.file.Path.of(String.valueOf(report))
                    : java.nio.file.Path.of("./AgentHome/workspace/opencode-reports", taskId);
            StringBuilder sb = new StringBuilder();
            sb.append("taskId=").append(taskId).append(" status=").append(t.getStatus()).append('\n');
            sb.append("jobDir=").append(jobDir.toAbsolutePath()).append('\n');
            sb.append("reportDir=").append(reportDir.toAbsolutePath()).append('\n');
            if (java.nio.file.Files.isDirectory(jobDir)) {
                sb.append("job files:\n");
                try (var stream = java.nio.file.Files.walk(jobDir, 3)) {
                    stream.filter(java.nio.file.Files::isRegularFile)
                            .limit(40)
                            .forEach(p -> sb.append("  - ").append(jobDir.relativize(p)).append('\n'));
                }
                java.nio.file.Path resultMd = jobDir.resolve("RESULT.md");
                if (java.nio.file.Files.isRegularFile(resultMd)) {
                    String body = java.nio.file.Files.readString(resultMd);
                    if (body.length() > 6000) body = body.substring(0, 6000) + "\n…";
                    sb.append("\n--- RESULT.md ---\n").append(body).append('\n');
                }
            } else {
                sb.append("jobDir missing\n");
            }
            java.nio.file.Path bridge = reportDir.resolve("bridge.md");
            if (java.nio.file.Files.isRegularFile(bridge)) {
                String body = java.nio.file.Files.readString(bridge);
                if (body.length() > 6000) body = body.substring(0, 6000) + "\n…";
                sb.append("\n--- bridge.md ---\n").append(body).append('\n');
            }
            if (t.getResult() != null && !t.getResult().isBlank()) {
                String r = t.getResult();
                if (r.length() > 4000) r = r.substring(0, 4000) + "\n…";
                sb.append("\n--- task.result ---\n").append(r).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "readOpenCodeArtifacts failed: " + e.getMessage();
        }
    }

    @Tool("Get multi-agent crew status and shared API budget (concurrency for one OpenCode Go key).")
    public String getMultiAgentStatus() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(multiAgentOrchestrator.status());
        } catch (Exception e) {
            return "getMultiAgentStatus failed: " + e.getMessage();
        }
    }

    @Tool("Accept an owner goal and create a Mission DAG. For complex goals pass nodesJson as a JSON array of {id?,assignee,title,brief,dependsOn?[]}. Simple goals may omit nodesJson (heuristic split). Prefer this over many single dispatchAgentTask calls when there are dependencies.")
    public String acceptMission(
            @P("Owner goal in Chinese or English") String goal,
            @P("Optional JSON array of nodes. Empty string to auto-plan.") String nodesJson) {
        try {
            String json = (nodesJson == null || nodesJson.isBlank() || "null".equalsIgnoreCase(nodesJson.trim()))
                    ? null : nodesJson;
            var m = missionOrchestrator.accept(defaultUser, goal, json);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(m);
        } catch (Exception e) {
            return "acceptMission failed: " + e.getMessage();
        }
    }

    @Tool("Accept MULTIPLE independent owner goals as concurrent Missions (one project each). Use when the owner asks to do several things at once (同时/一边…一边/以及/另外). Pass goalsJson as a JSON string array, e.g. [\"盯训练日志\",\"写v4/v5对比脚本\"]. Do NOT mash unrelated goals into one Mission or spam daily chat with step logs — progress lives on each project's timeline.")
    public String acceptMissions(@P("JSON array of goal strings") String goalsJson) {
        try {
            if (goalsJson == null || goalsJson.isBlank()) {
                return "acceptMissions failed: goalsJson required";
            }
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.List<String> goals = mapper.readValue(goalsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
            if (goals == null || goals.isEmpty()) {
                return "acceptMissions failed: empty goals";
            }
            int active = missionOrchestrator.countActive(defaultUser);
            var created = missionOrchestrator.acceptMany(defaultUser, goals);
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("created", created.size());
            out.put("activeBefore", active);
            out.put("queuedHint", active >= 2
                    ? "已有进行中的任务图，新项目会排队执行（共享 API 并发上限）。"
                    : "可并行推进；细进度看侧栏项目时间轴。");
            java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
            for (var m : created) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("missionId", m.getId());
                row.put("goal", m.getGoal());
                row.put("nodes", m.getNodes() == null ? 0 : m.getNodes().size());
                row.put("status", m.getStatus());
                items.add(row);
            }
            out.put("missions", items);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (Exception e) {
            return "acceptMissions failed: " + e.getMessage();
        }
    }

    @Tool("Get one Mission by id (msn_...), including node statuses and linked task ids.")
    public String getMission(@P("Mission id like msn_xxxxxxxxxxxx") String missionId) {
        try {
            var opt = missionOrchestrator.get(missionId);
            if (opt.isEmpty()) {
                return "mission not found: " + missionId;
            }
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(opt.get());
        } catch (Exception e) {
            return "getMission failed: " + e.getMessage();
        }
    }

    @Tool("List recent Missions for progress reports to the owner.")
    public String listMissions(@P("Max items, default 10") int limit) {
        try {
            int n = limit <= 0 ? 10 : Math.min(50, limit);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(missionOrchestrator.list(defaultUser, n));
        } catch (Exception e) {
            return "listMissions failed: " + e.getMessage();
        }
    }

    @Tool("Cancel a running Mission and mark unfinished nodes cancelled.")
    public String cancelMission(@P("Mission id") String missionId) {
        try {
            var m = missionOrchestrator.cancel(missionId);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(m);
        } catch (Exception e) {
            return "cancelMission failed: " + e.getMessage();
        }
    }

    @Tool("Start the paper pipeline. Modes: smoke|reproduce|research. Optional seedRepo pins GitHub clone (e.g. https://github.com/kuangliu/pytorch-cifar for mini CIFAR repro).")
    public String startPaperPipeline(
            @P("Research domain / keywords") String domain,
            @P("Venue tier hint, default CCF-C") String venueTier,
            @P("Optional working title hint; empty ok") String titleHint,
            @P("Mode: smoke|reproduce|research; empty=research") String mode,
            @P("Optional pinned GitHub repo URL; empty ok") String seedRepo) {
        try {
            String tier = (venueTier == null || venueTier.isBlank()) ? "CCF-C" : venueTier;
            String title = (titleHint == null || titleHint.isBlank() || "null".equalsIgnoreCase(titleHint))
                    ? null : titleHint;
            String m = (mode == null || mode.isBlank() || "null".equalsIgnoreCase(mode)) ? null : mode;
            String seed = (seedRepo == null || seedRepo.isBlank() || "null".equalsIgnoreCase(seedRepo)) ? null : seedRepo;
            var p = paperOrchestrator.start(defaultUser, domain, tier, title, m, seed, true);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(p);
        } catch (Exception e) {
            return "startPaperPipeline failed: " + e.getMessage();
        }
    }

    @Tool("Get paper pipeline status by id (paper_...). Includes stage, gateReport, zip path in meta.")
    public String getPaperProject(@P("Paper id like paper_xxxxxxxxxxxx") String paperId) {
        try {
            var opt = paperOrchestrator.get(paperId);
            if (opt.isEmpty()) return "paper not found: " + paperId;
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(opt.get());
        } catch (Exception e) {
            return "getPaperProject failed: " + e.getMessage();
        }
    }

    @Tool("List recent paper pipeline projects.")
    public String listPaperProjects(@P("Max items, default 10") int limit) {
        try {
            int n = limit <= 0 ? 10 : Math.min(50, limit);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(paperOrchestrator.list(defaultUser, n));
        } catch (Exception e) {
            return "listPaperProjects failed: " + e.getMessage();
        }
    }

    @Tool("Search real literature via Semantic Scholar / OpenAlex / arXiv. Only returns papers with DOI or arXiv id. Use before writing Related Work.")
    public String searchLiterature(
            @P("Search query") String query,
            @P("Max results 3-30, default 10") int limit) {
        try {
            int n = limit <= 0 ? 10 : limit;
            var papers = literatureClient.search(query, n);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(papers);
        } catch (Exception e) {
            return "searchLiterature failed: " + e.getMessage();
        }
    }

    @Tool("TopicScout: autonomously open one incremental paper pipeline for a domain (or empty for next pooled domain).")
    public String scoutOpenPaper(@P("Domain hint; empty to use scout pool") String domainHint) {
        try {
            String d = (domainHint == null || domainHint.isBlank() || "null".equalsIgnoreCase(domainHint))
                    ? null : domainHint;
            var p = topicScout.openOne(d);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(p);
        } catch (Exception e) {
            return "scoutOpenPaper failed: " + e.getMessage();
        }
    }

    @Tool("List active/revoked user-granted capabilities (ssh_remote, disk_quota, screen_peek, ...).")
    public String listCapabilities() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(capabilityService.list(defaultUser));
        } catch (Exception e) {
            return "listCapabilities failed: " + e.getMessage();
        }
    }

    @Tool("Run a bounded remote SSH command. Requires approved ssh_remote capability with scope host/user/keyPath/workdir.")
    public String runSshJob(@P("Shell command to run on remote host") String command) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(sshJobService.runRemote(defaultUser, command));
        } catch (Exception e) {
            return "runSshJob failed: " + e.getMessage();
        }
    }

    @Tool("List registered compute/SSH hosts (multiple GPU boxes). Chief uses this then runSshJobOn / dispatch CODER.")
    public String listComputeHosts() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(computeHostRegistry.list());
        } catch (Exception e) {
            return "listComputeHosts failed: " + e.getMessage();
        }
    }

    @Tool("Run SSH on a named compute host (id or hostname). Use listComputeHosts first. Extra hosts live in AgentHome/state/compute-hosts.json.")
    public String runSshJobOn(
            @P("Host id or hostname, e.g. smartml-primary or instance-glph6jf7.suzhou.smartml.cn") String hostId,
            @P("Shell command") String command) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(sshJobService.runRemoteOn(defaultUser, hostId, command));
        } catch (Exception e) {
            return "runSshJobOn failed: " + e.getMessage();
        }
    }

    @Tool("List 骋风算力 (cfgpu.com) cloud instances: running/stopped, GPU spec, mapped SSH hosts. Needs CFGPU_API_TOKEN. Never prints the token. Do not release/destroy instances.")
    public String listCfgpuCloud() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(cfgpuCloudService.inspect(false));
        } catch (Exception e) {
            return "listCfgpuCloud failed: " + e.getMessage();
        }
    }

    @Tool("Inspect 骋风算力 fleet plus live GPU util via SSH on running boxes. Use before start/stop. Needs CFGPU_API_TOKEN.")
    public String inspectCfgpuFleet() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(cfgpuCloudService.inspect(true));
        } catch (Exception e) {
            return "inspectCfgpuFleet failed: " + e.getMessage();
        }
    }

    @Tool("Start a 骋风算力 instance. Hint can be instance-glph6jf7, smartml-primary, or hostname. Never create new instances.")
    public String startCfgpuInstance(@P("Instance id, compute host id, or hostname") String instanceHint) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(cfgpuCloudService.start(instanceHint));
        } catch (Exception e) {
            return "startCfgpuInstance failed: " + e.getMessage();
        }
    }

    @Tool("Stop a 骋风算力 instance. Refuses if GPU is busy unless force=true and the owner explicitly ordered shutdown. Never release/destroy disks. Shutdown >7 days may be released by the platform.")
    public String stopCfgpuInstance(
            @P("Instance id, compute host id, or hostname") String instanceHint,
            @P("true only if the owner explicitly orders shutdown while GPU may be busy") String force) {
        try {
            boolean f = force != null && ("true".equalsIgnoreCase(force.trim())
                    || "1".equals(force.trim()) || "yes".equalsIgnoreCase(force.trim()));
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(cfgpuCloudService.stop(instanceHint, f));
        } catch (Exception e) {
            return "stopCfgpuInstance failed: " + e.getMessage();
        }
    }

    @Tool("Append an academic or project lab note under AgentHome/lab (伊姬娜的账本). shelf=academic|projects. After a worker finishes, Chief MUST record here.")
    public String recordLabEntry(
            @P("academic or projects") String shelf,
            @P("folder slug, e.g. ddpm-sampling or ai-drama") String slug,
            @P("Short title") String title,
            @P("What was done, numbers, paths, next step") String body,
            @P("Optional projectId like pf_ddpm_sampling") String projectId) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(labLedgerService.record(shelf, slug, title, body, projectId));
        } catch (Exception e) {
            return "recordLabEntry failed: " + e.getMessage();
        }
    }

    @Tool("List folders in AgentHome/lab academic and projects shelves.")
    public String listLabEntries() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(labLedgerService.list());
        } catch (Exception e) {
            return "listLabEntries failed: " + e.getMessage();
        }
    }

    @Tool("Read live DeepSeek wallet and OpenCode Go usage windows. Never prints API keys. Use when the owner asks about 额度 / 余额 / quota.")
    public String llmBalance() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(llmBalanceService.snapshot());
        } catch (Exception e) {
            return "llmBalance failed: " + e.getMessage();
        }
    }

    @Tool("Show whether GitHub / Overleaf tokens are configured. Does not print secrets.")
    public String labRemoteStatus() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(gitRemoteService.status());
        } catch (Exception e) {
            return "labRemoteStatus failed: " + e.getMessage();
        }
    }

    @Tool("Commit AgentHome/lab and push to GitHub repo EKINA_LAB_REPO (needs EKINA_GITHUB_TOKEN + EKINA_GITHUB_USER). Creates the private repo if missing.")
    public String syncLabGithub() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(gitRemoteService.syncLabGithub());
        } catch (Exception e) {
            return "syncLabGithub failed: " + e.getMessage();
        }
    }

    @Tool("Push a local folder (paper or academic notes) to Overleaf Git. Needs a blank Overleaf writing-desk URL in EKINA_OVERLEAF_GIT_URL plus EKINA_OVERLEAF_TOKEN. Cannot create Overleaf projects.")
    public String syncOverleaf(@P("Local directory to push; empty = AgentHome/lab/academic") String localDir) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(gitRemoteService.syncOverleaf(localDir));
        } catch (Exception e) {
            return "syncOverleaf failed: " + e.getMessage();
        }
    }

    @Tool("Open a new paper canvas. venue MUST be a kit id from listVenues (neurips, icml, ieee-conf, springer-sn, elsevier, …). Never use bare article. Creates lab/academic/<slug>/paper from that official-looking template.")
    public String openNewPaper(
            @P("folder slug, e.g. ddpm-sampling or tabicl") String slug,
            @P("Working title") String title,
            @P("Venue kit id: neurips|icml|iclr|aaai|acl|ieee-conf|ieee-access|springer-sn|elsevier|ekina-letters") String venue) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(gitRemoteService.openNewPaper(slug, title, venue));
        } catch (Exception e) {
            return "openNewPaper failed: " + e.getMessage();
        }
    }

    @Tool("List submission venue kits (NeurIPS/ICML/IEEE/Springer/Elsevier…). Use this before applyVenueTemplate or openNewPaper.")
    public String listVenues() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(journalFormatService.listVenues());
        } catch (Exception e) {
            return "listVenues failed: " + e.getMessage();
        }
    }

    @Tool("Copy an official-looking venue template (IEEE / NeurIPS / Springer / Elsevier / …) into a paper folder. paperDir empty = current lab academic paper if unique.")
    public String applyVenueTemplate(
            @P("Venue kit id from listVenues") String venueId,
            @P("Absolute paper directory (contains or will contain main.tex)") String paperDir,
            @P("Working title") String title) {
        try {
            java.nio.file.Path dest = (paperDir == null || paperDir.isBlank())
                    ? java.nio.file.Path.of("./AgentHome/lab/academic")
                    : java.nio.file.Path.of(paperDir);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(journalFormatService.apply(venueId, dest, title));
        } catch (Exception e) {
            return "applyVenueTemplate failed: " + e.getMessage();
        }
    }

    @Tool("Fail-closed paper acceptance: venue declared, no bare article, cover cited if lab serial, figures on disk, make_figures.py from CSV, colorblind palette, numbers appear in CSV. MUST run before telling the owner the paper is done. paperDir empty = current lab academic paper.")
    public String auditPaper(@P("Paper folder, slug, or project id (pf_ddpm_sampling). Empty = lab academic default.") String paperDir) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(paperAuditService.audit(paperDir));
        } catch (Exception e) {
            return "auditPaper failed: " + e.getMessage();
        }
    }

    @Tool("Re-run scripts/make_figures.py in a paper folder. Beautifies existing CSV-backed figures only. Never invents numbers.")
    public String beautifyFigures(@P("Paper folder or slug. Empty = lab academic default.") String paperDir) {
        try {
            java.nio.file.Path dest = paperAuditService.resolvePaperDir(paperDir);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(figureBeautifyService.beautify(dest));
        } catch (Exception e) {
            return "beautifyFigures failed: " + e.getMessage();
        }
    }

    @Tool("Best-effort compile of main.tex with pdflatex/tectonic. Never invents a PDF.")
    public String compileLatex(@P("Directory containing main.tex") String texDir) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(texDir);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(journalFormatService.compile(dir));
        } catch (Exception e) {
            return "compileLatex failed: " + e.getMessage();
        }
    }

    @Tool("Reads a PDF file, highlights coordinates and appends a sticky annotation comment on specific pages.")
    public String annotatePdf(
            @P("Absolute path to the PDF file") String pdfPath,
            @P("Page number to annotate (1-indexed)") int pageNumber,
            @P("Comment text to insert") String comment,
            @P("X coordinate for annotation (typically 100-500)") float x,
            @P("Y coordinate for annotation (typically 100-700)") float y) {
        
        File file = new File(pdfPath);
        if (!file.exists()) {
            return "Error: PDF file does not exist at " + pdfPath;
        }

        String outputPath = pdfPath.replace(".pdf", "_annotated.pdf");
        try (PdfReader reader = new PdfReader(pdfPath);
             PdfWriter writer = new PdfWriter(outputPath);
             PdfDocument pdfDoc = new PdfDocument(reader, writer)) {

            if (pageNumber < 1 || pageNumber > pdfDoc.getNumberOfPages()) {
                return "Error: Invalid page number. Total pages: " + pdfDoc.getNumberOfPages();
            }

            PdfPage page = pdfDoc.getPage(pageNumber);

            // 1. Highlight area (draw yellow semi-transparent rectangle on page canvas)
            PdfCanvas canvas = new PdfCanvas(page.newContentStreamBefore(), page.getResources(), pdfDoc);
            canvas.saveState();
            canvas.setFillColor(new DeviceRgb(255, 255, 0)); // Yellow
            canvas.rectangle(x, y, 150, 18);
            canvas.fill();
            canvas.restoreState();

            // 2. Add Text Annotation (Sticky note)
            PdfTextAnnotation textAnnotation = new PdfTextAnnotation(new Rectangle(x, y + 20, 20, 20));
            textAnnotation.setTitle(new com.itextpdf.kernel.pdf.PdfString("Ekina AI Assistant"));
            textAnnotation.setContents(comment);
            textAnnotation.setOpen(true);
            textAnnotation.setFlags(PdfAnnotation.PRINT);
            page.addAnnotation(textAnnotation);

            return "Success: PDF annotated. Saved output to " + outputPath;
        } catch (Exception e) {
            return "Failed to annotate PDF: " + e.getMessage();
        }
    }

    @Tool("Generates a beautiful PowerPoint presentation (.pptx) based on a structured slide list.")
    public String generatePowerPoint(
            @P("Destination output file path") String outputPath,
            @P("Presentation title") String title,
            @P("JSON string representing a list of slides. Each slide is an object with 'title', 'bulletPoints' (comma-separated), and 'imageKeyword'") 
            String slidesJson) {

        List<Map<String, String>> slides;
        try {
            ObjectMapper mapper = new ObjectMapper();
            slides = mapper.readValue(slidesJson, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            return "Failed to parse slides JSON: " + e.getMessage();
        }

        try (XMLSlideShow ppt = new XMLSlideShow()) {
            // 1. Create Title slide
            XSLFSlideLayout titleLayout = ppt.getSlideMasters().get(0).getLayout(SlideLayout.TITLE);
            XSLFSlide titleSlide = ppt.createSlide(titleLayout);
            XSLFTextShape titleShape = titleSlide.getPlaceholder(0);
            titleShape.setText(title);

            // 2. Create Content slides
            XSLFSlideLayout contentLayout = ppt.getSlideMasters().get(0).getLayout(SlideLayout.BLANK);
            for (Map<String, String> slideData : slides) {
                XSLFSlide slide = ppt.createSlide(contentLayout);

                // Add Title shape
                XSLFTextBox titleBox = slide.createTextBox();
                titleBox.setAnchor(new java.awt.Rectangle(50, 40, 600, 50));
                XSLFTextRun titleRun = titleBox.addNewTextParagraph().addNewTextRun();
                titleRun.setText(slideData.getOrDefault("title", "Slide"));
                titleRun.setFontSize(28.0);
                titleRun.setBold(true);

                // Add Bullet Points shape
                XSLFTextBox textBox = slide.createTextBox();
                textBox.setAnchor(new java.awt.Rectangle(50, 110, 350, 300));
                String[] bullets = slideData.getOrDefault("bulletPoints", "").split(",");
                for (String bullet : bullets) {
                    XSLFTextParagraph para = textBox.addNewTextParagraph();
                    para.setBullet(true);
                    para.addNewTextRun().setText(bullet.trim());
                }

                // Add Pexels Image shape
                String imgKeyword = slideData.get("imageKeyword");
                if (imgKeyword != null && !imgKeyword.trim().isEmpty()) {
                    byte[] imageBytes = downloadPexelsImage(imgKeyword);
                    if (imageBytes != null) {
                        XSLFPictureData picData = ppt.addPicture(imageBytes, PictureData.PictureType.JPEG);
                        XSLFPictureShape picShape = slide.createPicture(picData);
                        picShape.setAnchor(new java.awt.Rectangle(420, 110, 250, 250));
                    }
                }
            }

            // Save to disk
            try (FileOutputStream out = new FileOutputStream(outputPath)) {
                ppt.write(out);
            }
            return "Success: PowerPoint slides created at " + outputPath;
        } catch (Exception e) {
            return "Failed to compile PPTX: " + e.getMessage();
        }
    }

    @Tool("Transcribes audio files (Speech-To-Text). Requires ekina.video.asr-base-url; OpenCode Zen has no built-in ASR.")
    public String transcribeAudio(@P("Path to the extracted audio file") String audioPath) {
        File file = new File(audioPath);
        if (!file.exists()) {
            return "Error: Audio file does not exist at " + audioPath;
        }
        try {
            return videoWatchService.transcribeAudioFile(file);
        } catch (Exception e) {
            return "Audio transcription failed: " + e.getMessage();
        }
    }

    @Tool("Read a local document's text. Supports PDF (including sandbox/uploads attachments), Word/DOCX, Excel/XLSX, txt/md/json. ALWAYS use this when the owner attaches or mentions a PDF path — never claim you cannot read PDF.")
    public String readDocument(@P("Absolute path to the document file") String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return "Error: Document file does not exist at " + filePath;
        }
        String lower = filePath.toLowerCase();
        try (InputStream in = new FileInputStream(file)) {
            String text;
            if (lower.endsWith(".pdf")) {
                text = documentParser.parsePdf(in);
            } else if (lower.endsWith(".docx")) {
                text = documentParser.parseDocx(in);
            } else if (lower.endsWith(".xlsx")) {
                text = documentParser.parseXlsx(in);
            } else if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json")) {
                text = new String(Files.readAllBytes(Paths.get(filePath)), java.nio.charset.StandardCharsets.UTF_8);
            } else {
                return "Error: Unsupported file format. Only PDF, DOCX, XLSX, TXT, MD, JSON are supported.";
            }

            if (text == null || text.trim().isEmpty()) {
                return "The document is empty or no text could be extracted (扫描版 PDF 可能需要 OCR)。";
            }
            // Keep tool results bounded for the LLM context window
            String out = text.trim();
            if (out.length() > 24000) {
                out = out.substring(0, 24000) + "\n…(truncated; ask for a section if needed)";
            }
            return out;
        } catch (Exception e) {
            return "Failed to read document: " + e.getMessage();
        }
    }

    private static class TextFragment {
        final String text;
        final Rectangle rect;

        TextFragment(String text, Rectangle rect) {
            this.text = text;
            this.rect = rect;
        }
    }

    private static class TextFinderListener implements IEventListener {
        private final String searchText;
        private final StringBuilder buffer = new StringBuilder();
        private final List<TextFragment> renderInfos = new java.util.ArrayList<>();

        public TextFinderListener(String searchText) {
            this.searchText = searchText;
        }

        @Override
        public void eventOccurred(IEventData data, EventType type) {
            if (type == EventType.RENDER_TEXT) {
                TextRenderInfo renderInfo = (TextRenderInfo) data;
                String text = renderInfo.getText();
                if (text != null) {
                    buffer.append(text);
                    Rectangle rect = renderInfo.getBaseline().getBoundingRectangle();
                    renderInfos.add(new TextFragment(text, rect));
                }
            }
        }

        @Override
        public Set<EventType> getSupportedEvents() {
            return Collections.singleton(EventType.RENDER_TEXT);
        }

        public Rectangle findMatch() {
            String fullText = buffer.toString();
            int index = fullText.indexOf(searchText);
            if (index == -1) {
                // Case-insensitive/whitespace-insensitive fallback
                String normalizedFull = fullText.replaceAll("\\s+", "").toLowerCase();
                String normalizedSearch = searchText.replaceAll("\\s+", "").toLowerCase();
                index = normalizedFull.indexOf(normalizedSearch);
                if (index == -1) {
                    return null;
                }
            }

            int currentLength = 0;
            int startInfoIdx = -1;
            int endInfoIdx = -1;

            for (int i = 0; i < renderInfos.size(); i++) {
                String txt = renderInfos.get(i).text;
                if (txt == null) txt = "";
                int nextLength = currentLength + txt.length();
                if (startInfoIdx == -1 && index >= currentLength && index < nextLength) {
                    startInfoIdx = i;
                }
                if (startInfoIdx != -1 && (index + searchText.length() - 1) >= currentLength && (index + searchText.length() - 1) < nextLength) {
                    endInfoIdx = i;
                    break;
                }
                currentLength = nextLength;
            }

            if (startInfoIdx != -1 && endInfoIdx != -1) {
                Rectangle firstRect = renderInfos.get(startInfoIdx).rect;
                float minX = firstRect.getX();
                float minY = firstRect.getY();
                float maxX = minX + firstRect.getWidth();
                float maxY = minY + firstRect.getHeight();

                for (int i = startInfoIdx + 1; i <= endInfoIdx; i++) {
                    Rectangle r = renderInfos.get(i).rect;
                    minX = Math.min(minX, r.getX());
                    minY = Math.min(minY, r.getY());
                    maxX = Math.max(maxX, r.getX() + r.getWidth());
                    maxY = Math.max(maxY, r.getY() + r.getHeight());
                }

                return new Rectangle(minX - 2, minY - 2, (maxX - minX) + 4, (maxY - minY) + 4);
            }

            if (startInfoIdx != -1) {
                return renderInfos.get(startInfoIdx).rect;
            }

            return null;
        }
    }

    @Tool("Reads a PDF file, searches for a specific text string on a specific page, highlights it (yellow), and appends a sticky comment annotation.")
    public String annotatePdfText(
            @P("Absolute path to the PDF file") String pdfPath,
            @P("Page number to annotate (1-indexed)") int pageNumber,
            @P("The exact text string on the page to search for and annotate") String searchText,
            @P("Comment text to insert") String comment) {

        File file = new File(pdfPath);
        if (!file.exists()) {
            return "Error: PDF file does not exist at " + pdfPath;
        }

        String outputPath = pdfPath.replace(".pdf", "_annotated.pdf");

        try (PdfReader reader = new PdfReader(pdfPath);
             PdfWriter writer = new PdfWriter(outputPath);
             PdfDocument pdfDoc = new PdfDocument(reader, writer)) {

            if (pageNumber < 1 || pageNumber > pdfDoc.getNumberOfPages()) {
                return "Error: Invalid page number. Total pages: " + pdfDoc.getNumberOfPages();
            }

            PdfPage page = pdfDoc.getPage(pageNumber);

            TextFinderListener listener = new TextFinderListener(searchText);
            PdfCanvasProcessor processor = new PdfCanvasProcessor(listener);
            processor.processPageContent(page);

            Rectangle matchRect = listener.findMatch();
            if (matchRect == null) {
                return "Error: Could not find search text '" + searchText + "' on page " + pageNumber;
            }

            float x = matchRect.getX();
            float y = matchRect.getY();
            float w = matchRect.getWidth();
            float h = matchRect.getHeight();

            // 1. Highlight area (draw yellow semi-transparent rectangle on page canvas)
            PdfCanvas canvas = new PdfCanvas(page.newContentStreamBefore(), page.getResources(), pdfDoc);
            canvas.saveState();
            canvas.setFillColor(new DeviceRgb(255, 255, 0)); // Yellow
            canvas.rectangle(x, y, w, h);
            canvas.fill();
            canvas.restoreState();

            // 2. Add Text Annotation (Sticky note)
            PdfTextAnnotation textAnnotation = new PdfTextAnnotation(new Rectangle(x + w, y + h, 20, 20));
            textAnnotation.setTitle(new com.itextpdf.kernel.pdf.PdfString("Ekina AI Assistant"));
            textAnnotation.setContents(comment);
            textAnnotation.setOpen(true);
            textAnnotation.setFlags(PdfAnnotation.PRINT);
            page.addAnnotation(textAnnotation);

            return "Success: Found '" + searchText + "' and annotated the PDF. Saved output to " + outputPath;
        } catch (Exception e) {
            return "Failed to search and annotate PDF: " + e.getMessage();
        }
    }

    @Tool("Multimodal video watching: combines L1 metadata/subtitles, L2 spoken audio ASR, and L3 keyframe vision. Prefer this over subtitle-only tools when the user asks to watch/learn from a video (Bilibili or local file). depth: auto|meta|audio|full.")
    public String watchVideo(
            @P("Video URL, Bilibili BVID, or local video file path") String urlOrPath,
            @P("Watch depth: auto (default, meta+audio+vision), meta (subtitles only), audio (meta+ASR), full (all layers)") String depth) {
        try {
            return videoWatchService.watch(urlOrPath, depth);
        } catch (Exception e) {
            return "watchVideo failed: " + e.getMessage();
        }
    }

    @Tool("LEGACY subtitle-only helper for Bilibili (title/description/CC/comments). Prefer watchVideo for real understanding of audio and on-screen content.")
    public String fetchBilibiliTranscript(@P("Bilibili video URL or BVID (e.g., https://www.bilibili.com/video/BV1vsGZ6sEkw)") String url) {
        String bvid = extractBvid(url);
        if (bvid == null) {
            return "Error: Could not extract BVID from URL: " + url;
        }

        try {
            String responseJson = restClient.get()
                    .uri("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.bilibili.com")
                    .retrieve()
                    .body(String.class);

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> response = mapper.readValue(responseJson, Map.class);
            int code = (Integer) response.get("code");
            if (code != 0) {
                return "Error: Bilibili API returned code " + code + ": " + response.get("message");
            }
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            String title = (String) data.get("title");
            String desc = (String) data.get("desc");
            String uploader = "";
            if (data.containsKey("owner")) {
                Map<String, Object> owner = (Map<String, Object>) data.get("owner");
                uploader = (String) owner.get("name");
            }

            // Check for subtitles
            Map<String, Object> subtitleMap = (Map<String, Object>) data.get("subtitle");
            List<Map<String, Object>> subtitleList = null;
            if (subtitleMap != null && subtitleMap.containsKey("list")) {
                subtitleList = (List<Map<String, Object>>) subtitleMap.get("list");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Title: ").append(title).append("\n");
            sb.append("Uploader: ").append(uploader).append("\n");
            sb.append("Description: ").append(desc).append("\n\n");

            String subtitleUrl = null;
            if (subtitleList != null && !subtitleList.isEmpty()) {
                // Try to find Chinese subtitle, or take the first one
                for (Map<String, Object> sub : subtitleList) {
                    String lan = (String) sub.get("lan");
                    if (lan != null && (lan.contains("zh") || lan.contains("hans") || lan.contains("cn"))) {
                        subtitleUrl = (String) sub.get("subtitle_url");
                        break;
                    }
                }
                if (subtitleUrl == null) {
                    subtitleUrl = (String) subtitleList.get(0).get("subtitle_url");
                }
            }

            if (subtitleUrl != null && !subtitleUrl.isEmpty()) {
                if (subtitleUrl.startsWith("//")) {
                    subtitleUrl = "https:" + subtitleUrl;
                }
                // Fetch subtitle content
                try {
                    String subJson = restClient.get()
                            .uri(subtitleUrl)
                            .header("User-Agent", "Mozilla/5.0")
                            .retrieve()
                            .body(String.class);
                    Map<String, Object> subData = mapper.readValue(subJson, Map.class);
                    List<Map<String, Object>> body = (List<Map<String, Object>>) subData.get("body");
                    if (body != null && !body.isEmpty()) {
                        sb.append("--- Video Transcript (Subtitles) ---\n");
                        for (Map<String, Object> item : body) {
                            double from = ((Number) item.get("from")).doubleValue();
                            double to = ((Number) item.get("to")).doubleValue();
                            String content = (String) item.get("content");
                            sb.append("[").append(formatTime(from)).append(" - ").append(formatTime(to)).append("] ")
                              .append(content).append("\n");
                        }
                    } else {
                        sb.append("Note: Closed captions were empty.\n");
                    }
                } catch (Exception e) {
                    sb.append("Warning: Failed to fetch subtitle body from URL: ").append(subtitleUrl)
                      .append(". Error: ").append(e.getMessage()).append("\n");
                }
            } else {
                sb.append("Note: No closed captions (CC subtitles) found for this video. You can summarize the video based on the title and description above.\n");
            }

            // Fetch hot comments for extra context / fallback
            if (data.containsKey("aid")) {
                long aid = ((Number) data.get("aid")).longValue();
                try {
                    String replyJson = restClient.get()
                            .uri("https://api.bilibili.com/x/v2/reply/main?type=1&oid=" + aid + "&next=0&ps=20&mode=3")
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Referer", "https://www.bilibili.com")
                            .retrieve()
                            .body(String.class);
                    Map<String, Object> replyResponse = mapper.readValue(replyJson, Map.class);
                    if (replyResponse != null && (Integer) replyResponse.get("code") == 0) {
                        Map<String, Object> replyData = (Map<String, Object>) replyResponse.get("data");
                        if (replyData != null && replyData.containsKey("replies")) {
                            List<Map<String, Object>> replies = (List<Map<String, Object>>) replyData.get("replies");
                            if (replies != null && !replies.isEmpty()) {
                                sb.append("\n--- Top Comments (for extra technical context) ---\n");
                                int count = 0;
                                for (Map<String, Object> reply : replies) {
                                    if (count >= 15) break;
                                    String msg = null;
                                    if (reply.containsKey("content")) {
                                        Map<String, Object> contentMap = (Map<String, Object>) reply.get("content");
                                        msg = (String) contentMap.get("message");
                                    }
                                    if (msg != null && !msg.trim().isEmpty()) {
                                        sb.append("Comment ").append(count + 1).append(": ").append(msg.trim().replace("\n", " ")).append("\n");
                                        count++;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[Bilibili Tool] Failed to fetch comments: " + e.getMessage());
                }
            }

            return sb.toString();
        } catch (Exception e) {
            return "Failed to fetch Bilibili transcript: " + e.getMessage();
        }
    }

    @Tool("Write a UTF-8 Markdown/text note (.md/.txt). USE THIS for Obsidian vault notes and diaries under paths like ./AgentHome/notes. Never write Word/binary into .md paths.")
    public String writeMarkdownNote(
            @P("Destination file path ending with .md or .txt (absolute, or relative to sandbox)") String outputPath,
            @P("Optional title; if non-empty and content has no leading # heading, prepends as H1") String title,
            @P("Markdown body (UTF-8 Chinese OK)") String content) {
        try {
            File file = resolveOutputFile(outputPath);
            String name = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (!(name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt"))) {
                // Force markdown extension so we never drop binary into a .md-looking path by accident
                file = new File(file.getParentFile() != null ? file.getParentFile() : new File("."),
                        file.getName() + ".md");
            }
            return writeUtf8Markdown(file, title, content);
        } catch (Exception e) {
            return "Failed to write markdown note: " + e.getMessage();
        }
    }

    @Tool("Generates a structured Word document (.docx) based on title and content (supports basic markdown headings, lists, and paragraphs). If outputPath ends with .md/.txt, writes UTF-8 text instead (for Obsidian); otherwise always writes real .docx binary.")
    public String generateWordDocument(
            @P("Destination output file path (relative to sandbox or absolute path). Use .docx for Word; use .md + writeMarkdownNote preferred for notes.") String outputPath,
            @P("Document title") String title,
            @P("Markdown-formatted content to insert into the document") String content) {

        File file = resolveOutputFile(outputPath);
        String lowerName = file.getName().toLowerCase(java.util.Locale.ROOT);

        // Critical: never write DOCX/ZIP bytes into .md paths — that is the vault "乱码" bug.
        if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown") || lowerName.endsWith(".txt")) {
            try {
                return writeUtf8Markdown(file, title, content)
                        + " (note: path was text/markdown, so wrote UTF-8 text instead of .docx binary)";
            } catch (Exception e) {
                return "Failed to write markdown (from generateWordDocument): " + e.getMessage();
            }
        }

        // Always produce a real .docx extension for binary Word output
        if (!lowerName.endsWith(".docx")) {
            String base = file.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                base = base.substring(0, dot);
            }
            file = new File(file.getParentFile() != null ? file.getParentFile() : new File("."), base + ".docx");
        }

        // Ensure parent directories exist
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (XWPFDocument doc = new XWPFDocument()) {
            // Document Title
            XWPFParagraph titlePara = doc.createParagraph();
            titlePara.setAlignment(ParagraphAlignment.CENTER);
            titlePara.setSpacingAfter(240); // 12pt line spacing after
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText(title);
            titleRun.setFontSize(22);
            titleRun.setBold(true);
            titleRun.setFontFamily("Microsoft YaHei");
            titleRun.setColor("1A1A1A");

            // Split the content by newline
            String[] lines = content.split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                if (trimmed.startsWith("# ")) {
                    // Heading 1
                    XWPFParagraph para = doc.createParagraph();
                    para.setSpacingBefore(200);
                    para.setSpacingAfter(100);
                    XWPFRun run = para.createRun();
                    run.setText(trimmed.substring(2));
                    run.setFontSize(18);
                    run.setBold(true);
                    run.setFontFamily("Microsoft YaHei");
                    run.setColor("2E5B82"); // Deep primary color
                } else if (trimmed.startsWith("## ")) {
                    // Heading 2
                    XWPFParagraph para = doc.createParagraph();
                    para.setSpacingBefore(160);
                    para.setSpacingAfter(80);
                    XWPFRun run = para.createRun();
                    run.setText(trimmed.substring(3));
                    run.setFontSize(14);
                    run.setBold(true);
                    run.setFontFamily("Microsoft YaHei");
                    run.setColor("337AB7");
                } else if (trimmed.startsWith("### ")) {
                    // Heading 3
                    XWPFParagraph para = doc.createParagraph();
                    para.setSpacingBefore(120);
                    para.setSpacingAfter(60);
                    XWPFRun run = para.createRun();
                    run.setText(trimmed.substring(4));
                    run.setFontSize(12);
                    run.setBold(true);
                    run.setFontFamily("Microsoft YaHei");
                    run.setColor("555555");
                } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                    // Bullet list item
                    XWPFParagraph para = doc.createParagraph();
                    para.setSpacingAfter(60);
                    para.setIndentationLeft(360); // Indent list item
                    XWPFRun run = para.createRun();
                    run.setText("• " + trimmed.substring(2));
                    run.setFontSize(10);
                    run.setFontFamily("Microsoft YaHei");
                    run.setColor("333333");
                } else if (trimmed.matches("^\\d+\\.\\s.*")) {
                    // Numbered list item
                    XWPFParagraph para = doc.createParagraph();
                    para.setSpacingAfter(60);
                    para.setIndentationLeft(360);
                    XWPFRun run = para.createRun();
                    run.setText(trimmed);
                    run.setFontSize(10);
                    run.setFontFamily("Microsoft YaHei");
                    run.setColor("333333");
                } else {
                    // Standard Paragraph
                    XWPFParagraph para = doc.createParagraph();
                    para.setSpacingAfter(120);
                    XWPFRun run = para.createRun();
                    run.setText(trimmed);
                    run.setFontSize(10);
                    run.setFontFamily("Microsoft YaHei");
                    run.setColor("333333");
                }
            }

            try (FileOutputStream out = new FileOutputStream(file)) {
                doc.write(out);
            }

            return "Success: Word document generated at " + file.getAbsolutePath();
        } catch (Exception e) {
            return "Failed to generate Word document: " + e.getMessage();
        }
    }

    /** Resolve sandbox-relative or absolute output path. */
    private File resolveOutputFile(String outputPath) {
        String path = outputPath == null ? "" : outputPath.replace("\\", "/");
        if (path.startsWith("sandbox/")) {
            path = path.substring("sandbox/".length());
        }
        File file = new File(path);
        if (!file.isAbsolute()) {
            file = new File(sandboxDir, path);
        }
        return file;
    }

    /**
     * Write UTF-8 (no BOM) markdown/text. Prevents Windows notepad/Obsidian mojibake
     * from binary ZIP/DOCX being saved under .md extension.
     */
    private String writeUtf8Markdown(File file, String title, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        String body = content == null ? "" : content;
        String t = title == null ? "" : title.trim();
        if (!t.isEmpty()) {
            String trimmed = body.stripLeading();
            if (!trimmed.startsWith("#")) {
                body = "# " + t + "\n\n" + body;
            }
        }
        // Normalize newlines to \n for Obsidian friendliness
        body = body.replace("\r\n", "\n").replace('\r', '\n');
        if (!body.endsWith("\n")) {
            body = body + "\n";
        }
        Files.writeString(file.toPath(), body, java.nio.charset.StandardCharsets.UTF_8);
        return "Success: UTF-8 markdown written to " + file.getAbsolutePath()
                + " (" + body.length() + " chars)";
    }

    private String extractBvid(String url) {
        if (url == null) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(BV[a-zA-Z0-9]{10})", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // Fallback: check if the input itself is a BVID
        if (url.trim().matches("(?i)BV[a-zA-Z0-9]{10}")) {
            return url.trim();
        }
        return null;
    }

    private String formatTime(double seconds) {
        int s = (int) seconds;
        int h = s / 3600;
        int m = (s % 3600) / 60;
        int sec = s % 60;
        if (h > 0) {
            return String.format("%02d:%02d:%02d", h, m, sec);
        } else {
            return String.format("%02d:%02d", m, sec);
        }
    }

    /**
     * Downloads an image from Pexels matching keyword, falling back to a placeholder if API key is missing.
     */
    private byte[] downloadPexelsImage(String keyword) {
        if (pexelsApiKey == null || pexelsApiKey.trim().isEmpty()) {
            // Fallback: download a generic royalty-free placeholder image from unsplash/loremflickr
            try {
                URL url = new URL("https://loremflickr.com/320/240/" + keyword);
                try (InputStream in = url.openStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                    }
                    return out.toByteArray();
                }
            } catch (Exception e) {
                return null;
            }
        }
        // If Pexels API key is present, perform a standard photo search and download the first JPEG
        return null; 
    }

    @Override
    public List<ToolSpecification> getToolSpecifications() {
        List<ToolSpecification> specs = new java.util.ArrayList<>(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom(this));
        try {
            specs.addAll(mcpClientManager.getMcpToolSpecifications());
        } catch (Exception e) {
            System.err.println("[AgentToolbox] Failed to fetch MCP tool specifications: " + e.getMessage());
        }
        return specs;
    }

    @Override
    public String executeTool(String toolName, Map<String, Object> arguments) {
        // 1. Try local execution
        try {
            for (java.lang.reflect.Method method : this.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class) && method.getName().equals(toolName)) {
                    java.lang.reflect.Parameter[] parameters = method.getParameters();
                    Object[] args = new Object[parameters.length];
                    // Preserve argument iteration order for models that omit names / arg0 style
                    java.util.List<Object> positional = new java.util.ArrayList<>();
                    if (arguments != null) {
                        positional.addAll(arguments.values());
                    }

                    for (int i = 0; i < parameters.length; i++) {
                        java.lang.reflect.Parameter param = parameters[i];
                        String paramName = param.getName();
                        Object rawVal = arguments != null ? arguments.get(paramName) : null;
                        // langchain4j / some models use arg0, arg1
                        if (rawVal == null && arguments != null) {
                            rawVal = arguments.get("arg" + i);
                        }
                        // @P description is not a key; try common aliases
                        if (rawVal == null && arguments != null) {
                            if (i == 0) {
                                rawVal = firstNonNull(arguments, "urlOrPath", "url", "path", "query", "pdfPath", "audioPath", "filePath", "outputPath", "projectId");
                            } else if (i == 1) {
                                rawVal = firstNonNull(arguments, "depth", "pageNumber", "title", "searchText", "content", "patchJson", "detail");
                            }
                        }
                        if (rawVal == null && i < positional.size()) {
                            rawVal = positional.get(i);
                        }
                        args[i] = convertType(rawVal, param.getType());
                    }

                    Object result = method.invoke(this, args);
                    return result == null ? "" : String.valueOf(result);
                }
            }
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return "Execution failed: " + cause.getMessage();
        }

        // 2. If not local, forward to MCP
        return mcpClientManager.executeMcpTool(toolName, arguments);
    }

    private static Object firstNonNull(Map<String, Object> arguments, String... keys) {
        for (String key : keys) {
            if (arguments.containsKey(key) && arguments.get(key) != null) {
                return arguments.get(key);
            }
        }
        return null;
    }

    private Object convertType(Object value, Class<?> type) {
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return value;
        }
        if (type == int.class || type == Integer.class) {
            return ((Number) value).intValue();
        }
        if (type == float.class || type == Float.class) {
            return ((Number) value).floatValue();
        }
        if (type == double.class || type == Double.class) {
            return ((Number) value).doubleValue();
        }
        if (type == long.class || type == Long.class) {
            return ((Number) value).longValue();
        }
        return value;
    }
}
