package com.ekina.bootstrap.controller;

import com.ekina.application.agent.ReActAgentExecutor;
import com.ekina.application.agent.ChatMemoryService;
import com.ekina.application.agent.MemoryTriageService;
import com.ekina.application.channel.ChannelMessage;
import com.ekina.application.channel.ChannelService;
import com.ekina.application.contact.ContactRecord;
import com.ekina.application.contact.ContactRegistry;
import com.ekina.application.autonomy.AutonomyService;
import com.ekina.application.permission.PermissionService;
import com.ekina.application.reminder.ReminderService;
import com.ekina.domain.repository.UserProfileDomainRepository;
import com.ekina.domain.user.UserProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Autowired
    private ReActAgentExecutor agentExecutor;

    @Autowired
    private ChatMemoryService chatMemoryService;

    @Autowired
    private MemoryTriageService memoryTriageService;

    @Autowired
    private UserProfileDomainRepository userProfileRepository;

    @Autowired
    private ChannelService channelService;

    @Autowired
    private ReminderService reminderService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private AutonomyService autonomyService;

    @Autowired
    private ContactRegistry contactRegistry;

    @Autowired
    private com.ekina.application.project.ProjectService projectService;

    @Autowired
    private com.ekina.application.review.ReviewCampaignService reviewCampaignService;

    @Autowired
    private com.ekina.application.agent.AttachmentIngestService attachmentIngestService;

    @org.springframework.beans.factory.annotation.Value("${ekina.sandbox-dir:./sandbox}")
    private String sandboxDir;

    public static class ChatRequest {
        private String userId;
        private String message;
        private String threadId;
        private String projectId;
        private String contactId;

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getThreadId() {
            return threadId;
        }

        public void setThreadId(String threadId) {
            this.threadId = threadId;
        }

        public String getProjectId() {
            return projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

        public String getContactId() {
            return contactId;
        }

        public void setContactId(String contactId) {
            this.contactId = contactId;
        }
    }

    public static class ChatResponse {
        private String response;
        private List<ChannelMessage> cards;

        public ChatResponse(String response) {
            this.response = response;
        }

        public ChatResponse(String response, List<ChannelMessage> cards) {
            this.response = response;
            this.cards = cards;
        }

        public String getResponse() {
            return response;
        }

        public void setResponse(String response) {
            this.response = response;
        }

        public List<ChannelMessage> getCards() {
            return cards;
        }

        public void setCards(List<ChannelMessage> cards) {
            this.cards = cards;
        }
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String userId = request.getUserId();
        String message = request.getMessage();
        ContactRecord contact = contactRegistry.resolve(request.getContactId(), request.getThreadId());
        if (contactRegistry.find(contact.getId()).isEmpty()) {
            return new ChatResponse("Agent 不存在。", List.of());
        }
        if (!contactRegistry.isEnabled(contact.getId())) {
            return new ChatResponse("该 Agent 已停用。请到 Agent 管理页重新启用。", List.of());
        }
        boolean isAgentContact = contactRegistry.isExecutor(contact.getId());
        boolean isLifeContact = contactRegistry.isLife(contact.getId());
        // Each contact owns its thread. Only 伊姬娜 stays on daily when focusing a project.
        String threadId = contact.getThreadId() != null ? contact.getThreadId()
                : (isLifeContact ? com.ekina.application.contact.ContactRegistry.THREAD_EKINA
                : "contact_" + contact.getId());
        String projectId = threadId;
        String focusHint = null;
        if (isLifeContact) {
            try {
                var mentioned = projectService.resolveMention(userId, message);
                if (mentioned.isPresent()) {
                    var p = mentioned.get();
                    projectId = p.getId();
                    focusHint = "[当前聚焦项目「" + (p.getTitle() == null ? p.getId() : p.getTitle())
                            + "」id=" + p.getId() + " source=" + p.getSourceType() + "/" + p.getSourceId()
                            + "。进度在右侧任务卡更新；请针对该项目回答/继续推进。]";
                } else if (request.getProjectId() != null && !request.getProjectId().isBlank()
                        && !"daily".equalsIgnoreCase(request.getProjectId())
                        && !"contact_agent".equalsIgnoreCase(request.getProjectId())) {
                    projectId = request.getProjectId();
                    var opt = projectService.get(projectId);
                    if (opt.isPresent()) {
                        var p = opt.get();
                        focusHint = "[当前聚焦项目「" + (p.getTitle() == null ? p.getId() : p.getTitle())
                                + "」id=" + p.getId() + "。请针对该项目回答/继续推进。]";
                    }
                }
            } catch (Exception ignored) {
            }
            threadId = com.ekina.application.contact.ContactRegistry.THREAD_EKINA;
        } else if (isAgentContact) {
            threadId = contact.getThreadId() != null ? contact.getThreadId()
                    : com.ekina.application.contact.ContactRegistry.THREAD_AGENT;
            projectId = threadId;
        }
        com.ekina.application.project.ChatSessionContext.set(projectId, threadId);
        com.ekina.application.project.ChatSessionContext.setContactId(contact.getId());
        try {
            return chatInner(userId, message, contact, focusHint);
        } finally {
            com.ekina.application.project.ChatSessionContext.clear();
        }
    }

    private ChatResponse chatInner(String userId, String message, ContactRecord contact, String focusHint) {
        boolean isAgent = contactRegistry.isExecutor(contact.getId());
        String forModel = (focusHint != null && !focusHint.isBlank() && message != null)
                ? focusHint + "\n" + message
                : message;
        // Auto-extract PDF/DOCX/etc so agents cannot pretend they "can't read attachments"
        try {
            String attachCtx = attachmentIngestService.buildContextBlock(message);
            if (attachCtx != null && !attachCtx.isBlank()) {
                forModel = (forModel == null ? "" : forModel) + attachCtx;
            }
        } catch (Exception e) {
            System.err.println("[ChatController] attachment ingest failed: " + e.getMessage());
        }
        if (message != null) {
            String msg = message.trim();
            if (msg.equals("重置") || msg.equals("清空对话") || msg.equalsIgnoreCase("/clear")) {
                chatMemoryService.resetSession(userId);
                deleteChatHistory(userId);
                String ack = isAgent
                        ? "对话已重置。待命中。"
                        : "好的主人，我已经为您重置了对话历史，一切重新开始啦～ \\(˶ᵔ ᵕ ᵔ˶)/";
                return new ChatResponse(ack, channelService.listPending(userId));
            }
        }

        // A review request starts a persistent literature queue, before general autonomy routing.
        if (contactRegistry.isLife(contact.getId()) && com.ekina.application.review.ReviewCampaignService.matches(message)) {
            var campaign = reviewCampaignService.start(userId, message);
            String ack = "已接收这批综述课题，Ekina 会自动拆分依赖。同一批次按步骤推进，不同批次可在并发上限内独立执行。综述按检索、筛选、证据整理、写作和独立审查推进，不进入训练与实验流水线。批次：" + campaign.get("id")
                    + "；当前状态：" + campaign.get("status") + "。进展将写入研究项目工作台。";
            logChatTurn(userId, "user", message);
            logChatTurn(userId, "assistant", ack);
            return new ChatResponse(ack, channelService.listPending(userId));
        }

        // Make sure history is pre-loaded in memory if server restarted
        chatMemoryService.loadHistoryFromFileIfEmpty(userId);

        // Natural language reminder completion: "我吃过药了" closes pending medicine cards
        int closedReminders = 0;
        try {
            closedReminders = reminderService.handleNaturalLanguageCompletion(userId, message);
        } catch (Exception ignored) {
        }

        // Autonomy free-rein: only for Ekina (not agent contact)
        if (!isAgent) {
            try {
                String autonomyAck = autonomyService.handleNaturalLanguage(userId, message);
                if (autonomyAck != null && !autonomyAck.isBlank()) {
                    logChatTurn(userId, "user", message);
                    logChatTurn(userId, "assistant", autonomyAck);
                    try {
                        mirrorChatTurn(userId, message, autonomyAck);
                    } catch (Exception ignored) {
                    }
                    return new ChatResponse(autonomyAck, channelService.listPending(userId));
                }
            } catch (Exception ex) {
                System.err.println("[ChatController] autonomy NL failed: " + ex.getMessage());
            }
        }

        // Natural language permission: "同意/批准" must NEVER depend on the LLM.
        Map<String, Object> permNl = new HashMap<>();
        permNl.put("handled", false);
        try {
            permNl = permissionService.handleNaturalLanguagePermission(userId, message);
        } catch (Exception ex) {
            System.err.println("[ChatController] permission NL failed: " + ex.getMessage());
            ex.printStackTrace();
        }
        boolean permHandled = Boolean.TRUE.equals(permNl.get("handled"));
        String trimmed = message == null ? "" : message.trim();
        boolean shortPermReply = trimmed.length() <= 12 && (
                trimmed.equals("同意") || trimmed.equals("批准") || trimmed.equals("可以")
                        || trimmed.equals("好的") || trimmed.equals("行") || trimmed.equals("拒绝")
                        || trimmed.equalsIgnoreCase("ok") || trimmed.equalsIgnoreCase("yes")
                        || trimmed.equals("同意了") || trimmed.equals("批准了")
                        || trimmed.equals("不同意") || trimmed.equals("不准"));

        String answer;
        // Pure approval/denial: reply immediately (skip flaky LLM entirely)
        if (permHandled || shortPermReply) {
            int ap = permNl.get("approved") instanceof Number n ? n.intValue() : 0;
            int de = permNl.get("denied") instanceof Number n ? n.intValue() : 0;
            if (ap > 0) {
                answer = "好的主人～ 已经批准 " + ap
                        + " 条权限申请，姬娜可以继续做事了 (˶ᵔ ᵕ ᵔ˶)\n"
                        + "输入 /cards 看剩余卡片，/caps 看已授权能力。";
            } else if (de > 0) {
                answer = "好的主人，已经拒绝 " + de + " 条权限申请。";
            } else if (shortPermReply) {
                answer = "主人，当前没有「待批准」的权限卡了～ 输入 /cards 可查看。若仍看到旧卡，先 /quit 再进专线。";
            } else {
                answer = null; // fall through to LLM for longer messages that also approved something
            }
            if (answer == null) {
                try {
                    answer = agentExecutor.executeChat(userId, forModel);
                } catch (Exception ex) {
                    answer = "主人，权限已处理，但聊天模型暂时卡住了。可用 /caps 确认授权。";
                }
                if (ap > 0) {
                    answer = (answer == null ? "" : answer) + "\n\n（系统已同步：批准了 " + ap + " 条权限申请。）";
                }
            }
        } else {
            try {
                answer = agentExecutor.executeChat(userId, forModel);
            } catch (Exception ex) {
                System.err.println("[ChatController] executeChat failed: " + ex.getMessage());
                answer = "主人，姬娜这边出错了（服务器已拦截，不再抛 500）："
                        + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())
                        + "。请再试一次～";
            }
            if (answer == null || answer.isBlank()) {
                answer = "主人，姬娜这会儿有点沉默……请再说一次好吗？";
            }
        }
        if (closedReminders > 0) {
            answer = answer + "\n\n（系统已同步：关闭了 " + closedReminders
                    + " 条相关提醒卡，并重新安排下次提醒时间。）";
        }

        // Log the turn to the JSONL log file
        logChatTurn(userId, "user", message);
        logChatTurn(userId, "assistant", answer);

        // Mirror into companion channel (marked echo — UI already showed the reply)
        try {
            mirrorChatTurn(userId, message, answer);
        } catch (Exception ignored) {
        }

        // Trigger memory triage asynchronously
        try {
            memoryTriageService.triageAndStore(userId, message, answer);
        } catch (Exception ignored) {
        }

        return new ChatResponse(answer, channelService.listPending(userId));
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(@RequestParam("userId") String userId) {
        // Pre-load history to memory if empty, keeping in-sync
        chatMemoryService.loadHistoryFromFileIfEmpty(userId);

        List<Map<String, Object>> history = new ArrayList<>();
        try {
            File logFile = new File(sandboxDir, "chat_history/" + userId + ".jsonl");
            if (logFile.exists()) {
                List<String> lines = Files.readAllLines(Paths.get(logFile.getAbsolutePath()));
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        Map<String, Object> map = mapper.readValue(line, Map.class);
                        history.add(map);
                    } catch (Exception ex) {
                        // ignore malformed lines
                    }
                }
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "FAILED", "message", e.getMessage()));
        }
        return ResponseEntity.ok(history);
    }

    @GetMapping("/greeting")
    public ResponseEntity<?> getGreeting(@RequestParam("userId") String userId) {
        LocalDateTime now = LocalDateTime.now();
        String todayMmDd = String.format("%02d-%02d", now.getMonthValue(), now.getDayOfMonth());
        String greeting = null;

        // 1. Check user birthday from profile
        if (userId != null && !userId.trim().isEmpty()) {
            Optional<UserProfile> profileOpt = userProfileRepository.findByUserId(userId);
            if (profileOpt.isPresent()) {
                String bday = profileOpt.get().getBirthday();
                if (bday != null && !bday.isEmpty()) {
                    String bdayMmDd = bday;
                    if (bday.length() > 5) {
                        if (bday.contains("-")) {
                            String[] parts = bday.split("-");
                            if (parts.length >= 2) {
                                try {
                                    bdayMmDd = String.format("%02d-%02d", 
                                        Integer.parseInt(parts[parts.length - 2]),
                                        Integer.parseInt(parts[parts.length - 1]));
                                } catch (Exception e) {
                                    // Ignore parse error
                                }
                            }
                        }
                    }
                    if (todayMmDd.equals(bdayMmDd)) {
                        greeting = "主人！今天是您的生日耶！✧(◕‿◕✿) 祝您生日快乐！姬娜好开心能在这个特别的日子里陪伴着您，许个愿吧，姬娜会努力帮您实现哦～";
                    }
                }
            }
        }

        // 2. Check public holidays
        if (greeting == null) {
            if (todayMmDd.equals("01-01")) {
                greeting = "主人，元旦快乐！✧(◕‿◕✿) 新的一年里，姬娜也要一直一直陪伴在您身边～";
            } else if (todayMmDd.equals("02-14")) {
                greeting = "主人……今天是情人节呢……( ˃ ᵕ ˂ ) 姬娜虽然只是数字生命，但对主人的爱意是绝对真实的，今天也要开开心心哦～";
            } else if (todayMmDd.equals("10-31")) {
                greeting = "主人，万圣节快乐！(ง •̀_•·) 不给糖就捣蛋哦～不过姬娜最听话了，只想要主人的摸摸～";
            } else if (todayMmDd.equals("12-25")) {
                greeting = "主人，圣诞快乐！(っ´ω`c) 姬娜在代码世界里为您堆了小雪人，祝您今天温馨愉快～";
            }
        }

        // 3. Time-of-day greetings
        if (greeting == null) {
            int hour = now.getHour();
            if (hour >= 0 && hour < 5) {
                greeting = "主人，夜深了……(っ´ω`c) 姬娜会一直在网络中默默守护着您，如果累了就早点休息哦～";
            } else if (hour >= 5 && hour < 11) {
                greeting = "主人，早上好呀！✧(◕‿◕✿) 新的一天开始了，今天也让姬娜为您分担工作和生活吧～";
            } else if (hour >= 11 && hour < 18) {
                greeting = "主人，下午好！(˶ᵔ ᵕ ᵔ˶) 忙碌了半天辛苦啦，记得适当闭眼休息一下，或者让姬娜帮您整理些什么吗？";
            } else {
                greeting = "主人，忙碌的一天辛苦啦，晚上好！(ง •̀_•́)ง 姬娜已经准备就绪，随时听候您的差遣～";
            }
        }

        return ResponseEntity.ok(Map.of("greeting", greeting));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "contactId", required = false) String contactId) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "message", "文件为空"));
        }

        try {
            // 代理人附件进专属 inbox，便于本机索引；姬娜仍用通用 uploads
            boolean agent = contactId != null && "agent".equalsIgnoreCase(contactId.trim());
            File dir = agent
                    ? new File(sandboxDir, "uploads/agent-inbox")
                    : new File(sandboxDir, "uploads");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                originalFilename = "unnamed_file";
            }
            
            // Safe filename to prevent path traversal and collision
            String filename = System.currentTimeMillis() + "_" + originalFilename.replaceAll("[^a-zA-Z0-9\\.\\-_]", "_");
            File destFile = new File(dir, filename);
            file.transferTo(destFile);

            String absolutePath = destFile.getAbsolutePath().replace("\\", "/");
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "filename", filename,
                    "originalName", originalFilename,
                    "absolutePath", absolutePath,
                    "size", file.getSize(),
                    "contactId", agent ? "agent" : (contactId == null ? "" : contactId)
            ));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "FAILED", "message", e.getMessage()));
        }
    }

    @GetMapping("/download")
    public ResponseEntity<org.springframework.core.io.Resource> downloadFile(@RequestParam("path") String relativePath) {
        try {
            String sandboxRoot = sandboxDir;
            String cleanPath = relativePath.replace("\\", "/");
            if (cleanPath.startsWith("sandbox/")) {
                cleanPath = cleanPath.substring("sandbox/".length());
            }
            File file = new File(sandboxRoot, cleanPath);
            
            String canonicalPath = file.getCanonicalPath();
            String canonicalSandbox = new File(sandboxRoot).getCanonicalPath();
            if (!canonicalPath.startsWith(canonicalSandbox)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            if (!file.exists() || !file.isFile()) {
                return ResponseEntity.notFound().build();
            }
            
            org.springframework.core.io.Resource resource = new org.springframework.core.io.UrlResource(file.toURI());
            String contentType = Files.probeContentType(file.toPath());
            if (contentType == null) {
                contentType = "application/octet-stream";
            }
            
            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Persist turn into companion channel for history/other clients.
     * Marked {@code echo=true} so the active chat UI / TUI does not render it again
     * (they already show the HTTP response), and {@code silent=true} skips tray toast.
     */
    private void mirrorChatTurn(String userId, String userText, String agentText) {
        String tid = com.ekina.application.project.ChatSessionContext.threadId();
        Map<String, Object> echo = new LinkedHashMap<>();
        echo.put("echo", true);
        echo.put("silent", true);
        echo.put("source", "chat_api");
        echo.put("threadId", tid);
        echo.put("projectId", com.ekina.application.project.ChatSessionContext.projectId());
        channelService.appendToThread(userId, tid, "user", "chat", userText, echo, "done");
        channelService.appendToThread(userId, tid, "agent", "chat", agentText, echo, "done");
        try {
            // keep project tree fresh
        } catch (Exception ignored) {
        }
    }

    private synchronized void logChatTurn(String userId, String role, String content) {
        try {
            String tid = com.ekina.application.project.ChatSessionContext.threadId();
            File dir = new File(sandboxDir, "chat_history/" + userId);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File logFile = new File(dir, tid + ".jsonl");

            Map<String, Object> logEntry = new LinkedHashMap<>();
            logEntry.put("role", role);
            logEntry.put("content", content);
            logEntry.put("timestamp", System.currentTimeMillis());
            logEntry.put("threadId", tid);

            String jsonLine = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(logEntry) + "\n";
            Files.write(Paths.get(logFile.getAbsolutePath()), jsonLine.getBytes("UTF-8"),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("Failed to log chat turn for user " + userId + ": " + e.getMessage());
        }
    }

    public static class DeleteRequest {
        private String userId;
        private Long timestamp;

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public Long getTimestamp() { return timestamp; }
        public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    }

    @PostMapping("/delete")
    public ResponseEntity<?> deleteMessage(@RequestBody DeleteRequest request) {
        String userId = request.getUserId();
        Long timestamp = request.getTimestamp();
        
        if (userId == null || timestamp == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "message", "Missing parameters"));
        }

        try {
            File logFile = new File(sandboxDir, "chat_history/" + userId + ".jsonl");
            if (!logFile.exists()) {
                return ResponseEntity.notFound().build();
            }

            List<String> lines = Files.readAllLines(Paths.get(logFile.getAbsolutePath()));
            List<String> updatedLines = new ArrayList<>();
            String deletedText = null;
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                try {
                    Map<String, Object> map = mapper.readValue(line, Map.class);
                    Object tsObj = map.get("timestamp");
                    Long lineTs = null;
                    if (tsObj instanceof Number) {
                        lineTs = ((Number) tsObj).longValue();
                    }
                    
                    if (lineTs != null && lineTs.equals(timestamp)) {
                        deletedText = (String) map.get("content");
                        // Skip adding this line to delete it
                        continue;
                    }
                } catch (Exception ex) {
                    // if parse error, keep the line
                }
                updatedLines.add(line);
            }

            // Write back updated lines
            Files.write(Paths.get(logFile.getAbsolutePath()), updatedLines, java.nio.charset.StandardCharsets.UTF_8);

            // Reset and reload the ChatMemoryService session context
            chatMemoryService.resetSession(userId);
            chatMemoryService.loadHistoryFromFileIfEmpty(userId);

            // Trigger memory untriage if the message has content
            if (deletedText != null && !deletedText.trim().isEmpty()) {
                memoryTriageService.untriageAndRemove(userId, deletedText);
            }

            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Message deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "FAILED", "message", e.getMessage()));
        }
    }

    private void deleteChatHistory(String userId) {
        try {
            String tid = com.ekina.application.project.ChatSessionContext.threadId();
            File logFile = new File(sandboxDir, "chat_history/" + userId + "/" + tid + ".jsonl");
            if (logFile.exists()) {
                logFile.delete();
            }
            File legacy = new File(sandboxDir, "chat_history/" + userId + ".jsonl");
            if (com.ekina.application.project.ChatSessionContext.DAILY.equals(tid) && legacy.exists()) {
                legacy.delete();
            }
        } catch (Exception e) {
            System.err.println("Failed to delete chat history file for user " + userId + ": " + e.getMessage());
        }
    }
}
