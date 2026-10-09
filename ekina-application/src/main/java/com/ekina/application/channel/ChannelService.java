package com.ekina.application.channel;

import com.ekina.application.project.ChatSessionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * File-backed companion channel (JSONL per user + thread).
 */
@Service
public class ChannelService {

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private ChannelEventHub eventHub;

    @Value("${ekina.sandbox-dir:./sandbox}")
    private String sandboxDir;

    private Path channelDir(String userId) {
        String safe = safeUser(userId);
        return Path.of(sandboxDir, "channel", safe);
    }

    private Path channelFile(String userId, String threadId) {
        String tid = ChatSessionContext.normalizeThread(threadId);
        return channelDir(userId).resolve(tid + ".jsonl");
    }

    /** Legacy flat file (pre-thread). */
    private Path legacyChannelFile(String userId) {
        return Path.of(sandboxDir, "channel", safeUser(userId) + ".jsonl");
    }

    public ChannelMessage append(String userId, String sender, String type, String text, Map<String, Object> payload, String status) {
        String threadId = resolveThreadId(payload);
        return appendToThread(userId, threadId, sender, type, text, payload, status);
    }

    public ChannelMessage appendToThread(String userId, String threadId, String sender, String type, String text,
                                         Map<String, Object> payload, String status) {
        String tid = ChatSessionContext.normalizeThread(threadId);
        ChannelMessage msg = new ChannelMessage();
        msg.setId("msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        msg.setUserId(userId == null ? "default" : userId);
        msg.setTs(Instant.now().toString());
        msg.setSender(sender);
        msg.setType(type == null ? "chat" : type);
        msg.setText(text);
        msg.setThreadId(tid);
        Map<String, Object> pl = payload == null ? new java.util.HashMap<>() : new java.util.HashMap<>(payload);
        pl.put("threadId", tid);
        if (pl.get("projectId") == null) {
            pl.put("projectId", tid);
        }
        msg.setPayload(pl);
        msg.setStatus(status == null ? "done" : status);
        try {
            Path file = channelFile(userId, tid);
            Files.createDirectories(file.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(mapper.writeValueAsString(msg));
                w.newLine();
            }
            if (eventHub != null) {
                eventHub.publish(msg);
            }
        } catch (Exception e) {
            System.err.println("[ChannelService] write failed: " + e.getMessage());
        }
        return msg;
    }

    public List<ChannelMessage> list(String userId, int limit) {
        return list(userId, ChatSessionContext.threadId(), limit);
    }

    public List<ChannelMessage> list(String userId, String threadId, int limit) {
        List<ChannelMessage> all = readAll(userId, threadId);
        int n = Math.max(1, limit);
        return all.stream()
                .sorted(Comparator.comparing(ChannelMessage::getTs, Comparator.nullsLast(String::compareTo)).reversed())
                .limit(n)
                .collect(Collectors.toList());
    }

    public List<ChannelMessage> listSince(String userId, String afterTs, int limit) {
        return listSince(userId, ChatSessionContext.threadId(), afterTs, limit);
    }

    public List<ChannelMessage> listSince(String userId, String threadId, String afterTs, int limit) {
        List<ChannelMessage> all = readAll(userId, threadId);
        String after = afterTs == null ? "" : afterTs.trim();
        int n = Math.max(1, Math.min(200, limit));
        List<ChannelMessage> filtered = all.stream()
                .filter(m -> m.getTs() != null && (after.isBlank() || m.getTs().compareTo(after) > 0))
                .sorted(Comparator.comparing(ChannelMessage::getTs, Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toList());
        if (after.isBlank() && filtered.size() > n) {
            return filtered.subList(filtered.size() - n, filtered.size());
        }
        if (filtered.size() > n) {
            return filtered.subList(Math.max(0, filtered.size() - n), filtered.size());
        }
        return filtered;
    }

    public ChannelMessage sendCompanionChat(String userId, String text, Map<String, Object> payload) {
        String body = text == null ? "" : text.trim();
        if (body.isBlank()) {
            body = "（想说点什么，但脑子空了一下）";
        }
        if (body.length() > 2000) {
            body = body.substring(0, 1997) + "…";
        }
        Map<String, Object> pl = payload == null ? new java.util.HashMap<>() : new java.util.HashMap<>(payload);
        pl.putIfAbsent("companion", true);
        // Hard route by IM contact — never let a sticky projectId/thread from the other contact leak
        String contact = pl.get("contactId") == null ? "" : String.valueOf(pl.get("contactId")).trim().toLowerCase();
        if (contact.isBlank()) {
            contact = ChatSessionContext.contactId();
        }
        if ("agent".equals(contact)) {
            pl.put("contactId", "agent");
            pl.put("threadId", com.ekina.application.contact.ContactRegistry.THREAD_AGENT);
            // Prevent projectId=daily (or vice versa) from hijacking resolveThreadId
            pl.put("projectId", com.ekina.application.contact.ContactRegistry.THREAD_AGENT);
        } else {
            pl.put("contactId", "ekina");
            pl.put("threadId", com.ekina.application.contact.ContactRegistry.THREAD_EKINA);
            Object proj = pl.get("projectId");
            if (proj == null || String.valueOf(proj).isBlank()
                    || "contact_agent".equals(String.valueOf(proj))
                    || "agent".equalsIgnoreCase(String.valueOf(proj))) {
                pl.put("projectId", com.ekina.application.contact.ContactRegistry.THREAD_EKINA);
            }
        }
        return appendToThread(userId, String.valueOf(pl.get("threadId")), "agent", "chat", body, pl, "done");
    }

    private String resolveThreadId(Map<String, Object> payload) {
        if (payload != null) {
            Object t = payload.get("threadId");
            if (t != null && !String.valueOf(t).isBlank() && !"null".equalsIgnoreCase(String.valueOf(t))) {
                return String.valueOf(t);
            }
            Object p = payload.get("projectId");
            if (p != null && !String.valueOf(p).isBlank() && !"null".equalsIgnoreCase(String.valueOf(p))) {
                return String.valueOf(p);
            }
            // Route task/mission/paper system lines into matching project id if present later
            Object taskId = payload.get("taskId");
            if (taskId != null) {
                return "task_" + String.valueOf(taskId).replaceAll("[^a-zA-Z0-9_\\-]", "_");
            }
        }
        return ChatSessionContext.threadId();
    }

    private List<ChannelMessage> readAll(String userId, String threadId) {
        String tid = ChatSessionContext.normalizeThread(threadId);
        List<ChannelMessage> all = new ArrayList<>();
        Path file = channelFile(userId, tid);
        readFileInto(file, all, tid);
        // Legacy flat channel only for daily
        if (ChatSessionContext.DAILY.equals(tid)) {
            Path legacy = legacyChannelFile(userId);
            if (Files.isRegularFile(legacy)) {
                readFileInto(legacy, all, tid);
            }
        }
        return all;
    }

    private void readFileInto(Path file, List<ChannelMessage> all, String defaultThread) {
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    ChannelMessage m = mapper.readValue(line, ChannelMessage.class);
                    if (m.getThreadId() == null || m.getThreadId().isBlank()) {
                        m.setThreadId(defaultThread);
                    }
                    all.add(m);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            System.err.println("[ChannelService] read failed: " + e.getMessage());
        }
    }

    /** All pending messages (no dedupe) — used when dismissing / superseding reminder stacks. */
    public List<ChannelMessage> listAllPending(String userId) {
        return readAllThreads(userId).stream()
                .filter(m -> "pending".equalsIgnoreCase(m.getStatus()))
                .sorted(Comparator.comparing(ChannelMessage::getTs, Comparator.nullsLast(String::compareTo)).reversed())
                .collect(Collectors.toList());
    }

    /** Pending across all threads for this user (cards must stay visible). */
    public List<ChannelMessage> listPending(String userId) {
        List<ChannelMessage> raw = listAllPending(userId);
        List<ChannelMessage> out = new ArrayList<>();
        Set<String> seenReminderRules = new HashSet<>();
        for (ChannelMessage m : raw) {
            if ("reminder".equals(m.getType()) && m.getPayload() != null && m.getPayload().get("ruleId") != null) {
                String rid = String.valueOf(m.getPayload().get("ruleId"));
                if (seenReminderRules.contains(rid)) continue;
                seenReminderRules.add(rid);
            }
            out.add(m);
        }
        return out;
    }

    private List<ChannelMessage> readAllThreads(String userId) {
        List<ChannelMessage> all = new ArrayList<>();
        Path dir = channelDir(userId);
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jsonl")) {
                for (Path f : ds) {
                    String name = f.getFileName().toString();
                    String tid = name.endsWith(".jsonl") ? name.substring(0, name.length() - 6) : ChatSessionContext.DAILY;
                    readFileInto(f, all, tid);
                }
            } catch (Exception e) {
                System.err.println("[ChannelService] list threads failed: " + e.getMessage());
            }
        }
        Path legacy = legacyChannelFile(userId);
        if (Files.isRegularFile(legacy)) {
            readFileInto(legacy, all, ChatSessionContext.DAILY);
        }
        // Collapse duplicate ids (legacy + threaded). Prefer non-pending / later status edits.
        Map<String, ChannelMessage> byId = new java.util.LinkedHashMap<>();
        for (ChannelMessage m : all) {
            if (m.getId() == null) continue;
            ChannelMessage prev = byId.get(m.getId());
            if (prev == null) {
                byId.put(m.getId(), m);
                continue;
            }
            byId.put(m.getId(), preferMessageCopy(prev, m));
        }
        return new ArrayList<>(byId.values());
    }

    /** When the same id exists in legacy + thread files, keep the more advanced status. */
    private static ChannelMessage preferMessageCopy(ChannelMessage a, ChannelMessage b) {
        int sa = statusRank(a.getStatus());
        int sb = statusRank(b.getStatus());
        if (sb != sa) return sb > sa ? b : a;
        String ta = a.getTs() == null ? "" : a.getTs();
        String tb = b.getTs() == null ? "" : b.getTs();
        return tb.compareTo(ta) >= 0 ? b : a;
    }

    private static int statusRank(String status) {
        if (status == null) return 0;
        String s = status.toLowerCase();
        if ("pending".equals(s)) return 1;
        if ("snoozed".equals(s)) return 2;
        if ("superseded".equals(s)) return 3;
        if ("acked".equals(s) || "done".equals(s) || "cancelled".equals(s)) return 4;
        return 2;
    }

    /** Find a message by id across all threads (pending cards may be older than list() window). */
    public ChannelMessage findById(String userId, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (ChannelMessage m : readAllThreads(userId)) {
            if (id.equals(m.getId())) {
                return m;
            }
        }
        return null;
    }

    public ChannelMessage updateStatus(String userId, String id, String status, Map<String, Object> payloadPatch) {
        return updateContent(userId, id, null, status, payloadPatch);
    }

    public ChannelMessage updateContent(String userId, String id, String text, String status, Map<String, Object> payloadPatch) {
        // Search all thread files + legacy. Update EVERY copy — legacy user-1.jsonl and
        // threaded daily.jsonl may still share the same message ids from migration.
        List<Path> files = new ArrayList<>();
        Path dir = channelDir(userId);
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jsonl")) {
                for (Path f : ds) files.add(f);
            } catch (Exception ignored) {
            }
        }
        Path legacy = legacyChannelFile(userId);
        if (Files.isRegularFile(legacy)) files.add(legacy);

        ChannelMessage updated = null;
        for (Path file : files) {
            ChannelMessage one = updateInFile(file, id, text, status, payloadPatch);
            if (one != null) updated = one;
        }
        return updated;
    }

    private ChannelMessage updateInFile(Path file, String id, String text, String status, Map<String, Object> payloadPatch) {
        if (!Files.isRegularFile(file)) return null;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            ChannelMessage updated = null;
            for (String line : lines) {
                if (line.isBlank()) continue;
                ChannelMessage m = mapper.readValue(line, ChannelMessage.class);
                if (id != null && id.equals(m.getId())) {
                    if (status != null && !status.isBlank()) m.setStatus(status);
                    if (text != null) m.setText(text);
                    if (payloadPatch != null) {
                        if (m.getPayload() == null) m.setPayload(new java.util.HashMap<>(payloadPatch));
                        else m.getPayload().putAll(payloadPatch);
                    }
                    updated = m;
                    out.add(mapper.writeValueAsString(m));
                } else {
                    out.add(line);
                }
            }
            if (updated != null) {
                Files.write(file, out, StandardCharsets.UTF_8);
            }
            return updated;
        } catch (Exception e) {
            System.err.println("[ChannelService] update failed: " + e.getMessage());
            return null;
        }
    }

    private static String safeUser(String userId) {
        return (userId == null || userId.isBlank()) ? "default" : userId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
