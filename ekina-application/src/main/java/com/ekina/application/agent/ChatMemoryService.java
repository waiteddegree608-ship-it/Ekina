package com.ekina.application.agent;

import com.ekina.application.agentreg.AgentRegistry;
import com.ekina.application.contact.AgentEmotionService;
import com.ekina.application.project.ChatSessionContext;
import com.ekina.application.skill.SkillService;
import com.ekina.application.state.StateCardService;
import com.ekina.domain.agent.ChatMessage;
import com.ekina.domain.agent.LlmService;
import com.ekina.domain.repository.UserMemoryDomainRepository;
import com.ekina.domain.user.UserMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application service managing conversational short-term memory (sliding window),
 * episodic memory summaries, and long-term vector memory integration.
 */
@Service
public class ChatMemoryService {

    @Autowired
    private LlmService llmService;

    @Autowired
    private UserMemoryDomainRepository userMemoryRepository;

    @Autowired
    private SkillService skillService;

    @Autowired
    private StateCardService stateCardService;

    @Autowired
    private AgentEmotionService agentEmotionService;

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private com.ekina.application.project.ProjectBoardService projectBoardService;

    @org.springframework.beans.factory.annotation.Value("${ekina.workspace-dir:.}")
    private String workspaceDir;

    @org.springframework.beans.factory.annotation.Value("${ekina.sandbox-dir:./sandbox}")
    private String sandboxDir;

    private static final int MAX_SLIDING_WINDOW = 12; // Retain last 12 messages
    private static final int COMPRESSION_THRESHOLD = 20; // Trigger summary when messages exceed 20

    // In-memory active session cache mapping User ID to message history
    private final Map<String, List<ChatMessage>> sessionHistories = new ConcurrentHashMap<>();
    
    // In-memory cache mapping User ID to summarized historical context
    private final Map<String, String> sessionSummaries = new ConcurrentHashMap<>();

    /**
     * Appends a new chat message to a user's active history and triggers summarization if limit is reached.
     */
    public synchronized void addMessage(String userId, ChatMessage message) {
        String key = ChatSessionContext.memoryKey(userId);
        List<ChatMessage> history = sessionHistories.computeIfAbsent(key, k -> new ArrayList<>());
        history.add(message);

        if (history.size() >= COMPRESSION_THRESHOLD) {
            compressHistory(key, history);
        }
    }

    private String loadPersonaPrompt(String contactId) {
        try {
            return agentRegistry.loadPersonaMarkdown(contactId);
        } catch (Exception e) {
            System.err.println("Warning: Failed to load agent persona: " + e.getMessage());
            return "你是 Ekina 上的 Agent。用简体中文回复主人。";
        }
    }

    private void appendEkinaRules(StringBuilder systemContent, String latestQuery) {
        systemContent.append("\n[Ekina 科研工作约定]\n");
        systemContent.append("以用户的研究问题、材料和资源约束为准，区分文献结论、假设、实验结果与待核实信息。\n");
        systemContent.append("引用应能追溯到来源；数字应能追溯到数据和实验配置，不把合成数据或 smoke 结果当作科学结论。\n");
        systemContent.append("项目进展使用 updateProjectBoard / appendProjectEvent；交付指出证据、限制和产物路径。\n");
        systemContent.append("使用 readDocument 阅读附件；按任务需要调用文献、综述、实验、写作和审查工具。\n");
        systemContent.append("长任务可使用已配置的执行器；使用算力和外部发布遵循用户授权及工具权限，不因科研任务自动扩大权限。\n");
        systemContent.append("项目目录由服务配置决定；使用 listHomeFiles 查看产物，禁止猜测本机绝对路径。\n");
        systemContent.append(skillService.buildPromptSection(latestQuery));
    }

    private void appendAgentRules(StringBuilder systemContent) {
        systemContent.append("\n[研究资料助手]\n在已授权目录整理、检索与阅读文件。保留来源和原稿，说明转换限制，交付可定位的产物路径。\n");
    }

    /**
     * Resolves the full context prompt for the LLM.
     * Incorporates:
     * 1) Memory summary (bullet points of compressed history)
     * 2) Nearest semantic memories matching the latest user query
     * 3) Active short-term messages (sliding window)
     */
    public List<ChatMessage> getContextualMessages(String userId, String latestQuery) {
        List<ChatMessage> context = new ArrayList<>();
        String contactId = ChatSessionContext.contactId();
        boolean life = agentRegistry.isLife(contactId);
        boolean executor = agentRegistry.isExecutorFlavor(contactId);

        // 1. Inject system guidelines and episodic memory summary
        StringBuilder systemContent = new StringBuilder();
        systemContent.append(loadPersonaPrompt(contactId)).append("\n");
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        systemContent.append("\n[当前真实时间 — 以此时钟为准，禁止假设仍是 2024/2025]\n");
        systemContent.append("- 今天是：").append(now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA))).append("\n");
        systemContent.append("- 公元年份：").append(now.getYear()).append("（搜索热梗/新闻时必须用 ").append(now.getYear()).append(" 年，不要用 2025）\n");
        systemContent.append("- 时区：Asia/Shanghai\n");
        systemContent.append("- 当前 Agent：").append(contactId).append(" / thread=").append(ChatSessionContext.threadId()).append("\n");
        if (life) {
            appendEkinaRules(systemContent, latestQuery);
            try {
                systemContent.append(projectBoardService.asPromptBlock(
                        userId, contactId, ChatSessionContext.projectId()));
            } catch (Exception e) {
                System.err.println("[ChatMemory] board prompt skipped: " + e.getMessage());
            }
        } else if (executor) {
            appendAgentRules(systemContent);
        } else {
            systemContent.append("\n[通用规则]\n- 用简体中文；工具仅限能力包白名单；破坏性操作先征得同意。\n");
        }

        String key = ChatSessionContext.memoryKey(userId);
        String summary = sessionSummaries.get(key);
        if (summary != null && !summary.isEmpty()) {
            systemContent.append("\n[Summary of Past Conversation History]:\n").append(summary).append("\n");
        }

        // 2. Query PGvector for long-term memories related to latest query
        if (latestQuery != null && !latestQuery.trim().isEmpty()) {
            try {
                float[] queryVector = llmService.embed(latestQuery);
                List<UserMemory> relevantMemories = userMemoryRepository.findNearest(userId, queryVector, 3);
                if (!relevantMemories.isEmpty()) {
                    systemContent.append("\n[Retrieved User Memories & Facts]:\n");
                    for (UserMemory mem : relevantMemories) {
                        systemContent.append("- ").append(mem.getContent()).append("\n");
                    }
                }
            } catch (Exception e) {
                System.err.println("Warning: Failed to fetch vector memories: " + e.getMessage());
            }
        }

        context.add(ChatMessage.builder()
                .role(ChatMessage.MessageRole.SYSTEM)
                .content(systemContent.toString())
                .build());

        // 3. Append short-term sliding window messages
        List<ChatMessage> history = sessionHistories.getOrDefault(key, new ArrayList<>());
        context.addAll(history);

        return context;
    }

    /**
     * Compresses older messages to free up token context, summarizing them and updating the session summary.
     */
    private void compressHistory(String sessionKey, List<ChatMessage> history) {
        // Take the oldest 12 messages for summarization
        int numToCompress = 12;
        List<ChatMessage> toCompress = new ArrayList<>(history.subList(0, numToCompress));
        
        // Remove them from active window
        history.subList(0, numToCompress).clear();

        // Build prompt for summarization
        StringBuilder sb = new StringBuilder();
        sb.append("Summarize the following chat history into brief, informative bullet points capturing the core facts, topics, and user preferences. Keep it concise:\n");
        for (ChatMessage msg : toCompress) {
            sb.append(msg.getRole().name()).append(": ").append(msg.getContent()).append("\n");
        }

        try {
            // Run summarizer
            String newSummaryBulletPoints = llmService.chat(sb.toString());

            // Merge with existing summary
            String existingSummary = sessionSummaries.getOrDefault(sessionKey, "");
            String mergedPrompt = "Merge the following two summary logs of the same conversation into a single, cohesive list of key facts and timeline. Do not duplicate information:\n"
                    + "LOG A:\n" + existingSummary + "\n\n"
                    + "LOG B:\n" + newSummaryBulletPoints;

            String updatedSummary = llmService.chat(mergedPrompt);
            sessionSummaries.put(sessionKey, updatedSummary);
        } catch (Exception e) {
            System.err.println("Failed to summarize old conversation history: " + e.getMessage());
            // Fallback: put back compressed items to prevent loss
            history.addAll(0, toCompress);
        }
    }

    /**
     * Clears all session cache data for testing or session reset.
     */
    public void resetSession(String userId) {
        String key = ChatSessionContext.memoryKey(userId);
        sessionHistories.remove(key);
        sessionSummaries.remove(key);
    }

    /**
     * Loads chat history from local JSONL log file into in-memory sliding window if empty.
     */
    public synchronized void loadHistoryFromFileIfEmpty(String userId) {
        String key = ChatSessionContext.memoryKey(userId);
        List<ChatMessage> history = sessionHistories.get(key);
        if (history != null && !history.isEmpty()) {
            return;
        }

        String thread = ChatSessionContext.threadId();
        java.io.File logFile = new java.io.File(sandboxDir, "chat_history/" + userId + "/" + thread + ".jsonl");
        if (!logFile.exists()) {
            // legacy flat history only for daily
            if (ChatSessionContext.DAILY.equals(thread)) {
                logFile = new java.io.File(sandboxDir, "chat_history/" + userId + ".jsonl");
            }
        }
        if (!logFile.exists()) {
            return;
        }

        try {
            List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Paths.get(logFile.getAbsolutePath()));
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<ChatMessage> loaded = new ArrayList<>();
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                try {
                    Map<String, Object> map = mapper.readValue(line, Map.class);
                    String roleStr = (String) map.get("role");
                    String content = (String) map.get("content");
                    if (roleStr != null && content != null) {
                        ChatMessage.MessageRole role = roleStr.equalsIgnoreCase("user") 
                                ? ChatMessage.MessageRole.USER 
                                : ChatMessage.MessageRole.ASSISTANT;
                        loaded.add(ChatMessage.builder().role(role).content(content).build());
                    }
                } catch (Exception ex) {
                    // Ignore malformed line
                }
            }

            if (loaded.size() > MAX_SLIDING_WINDOW) {
                List<ChatMessage> window = loaded.subList(loaded.size() - MAX_SLIDING_WINDOW, loaded.size());
                sessionHistories.put(key, new ArrayList<>(window));
            } else {
                sessionHistories.put(key, loaded);
            }
        } catch (Exception e) {
            System.err.println("Failed to load history from file for user " + userId + ": " + e.getMessage());
        }
    }
}
