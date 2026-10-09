package com.ekina.application.agentreg;

import com.ekina.application.channel.ChannelMessage;
import com.ekina.application.channel.ChannelService;
import com.ekina.application.contact.AgentEmotionService;
import com.ekina.application.home.HomeQuotaService;
import com.ekina.application.project.ChatSessionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ekina persistent agent registry (manifest + persona.md under AgentHome/agents/).
 */
@Service
public class AgentRegistry {

    public static final String EKINA = "ekina";
    public static final String AGENT = "agent";
    public static final String THREAD_EKINA = ChatSessionContext.DAILY;
    public static final String THREAD_AGENT = "contact_agent";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, AgentManifest> agents = new ConcurrentHashMap<>();

    @Autowired
    private HomeQuotaService homeQuotaService;

    @Autowired
    private ChannelService channelService;

    @Autowired
    private AgentEmotionService agentEmotionService;

    @Value("${ekina.workspace-dir:.}")
    private String workspaceDir;

    @PostConstruct
    public void init() {
        loadAll();
        if (agents.isEmpty()) {
            seedPresets();
        } else {
            ensurePreset(EKINA);
            ensurePreset(AGENT);
        }
        ensureWorkspaces();
        System.out.println("[AgentRegistry] loaded " + agents.size() + " agents");
    }

    public Path agentsRoot() {
        return homeQuotaService.homePath().resolve("agents");
    }

    public Path agentDir(String id) {
        return agentsRoot().resolve(normalizeId(id));
    }

    public synchronized List<AgentManifest> listAll() {
        return new ArrayList<>(agents.values());
    }

    public synchronized List<Map<String, Object>> listForApi(String userId, boolean enabledOnly) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentManifest base : agents.values()) {
            if (enabledOnly && !base.isEnabled()) continue;
            AgentManifest m = copy(base);
            enrichRuntime(userId, m);
            out.add(m.toMap());
        }
        return out;
    }

    public Optional<AgentManifest> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return Optional.ofNullable(agents.get(normalizeId(id)));
    }

    public AgentManifest require(String id) {
        return find(id).orElseGet(() -> agents.get(EKINA));
    }

    public AgentManifest resolve(String agentId, String threadId) {
        Optional<AgentManifest> byId = find(agentId);
        if (byId.isPresent()) return byId.get();
        if (threadId != null) {
            String tid = ChatSessionContext.normalizeThread(threadId);
            for (AgentManifest m : agents.values()) {
                if (tid.equals(m.getThreadId())) return m;
            }
            if (THREAD_AGENT.equals(tid) || tid.startsWith("contact_agent")) {
                return require(AGENT);
            }
        }
        return require(EKINA);
    }

    public boolean isEnabled(String agentId) {
        return find(agentId).map(AgentManifest::isEnabled).orElse(false);
    }

    public boolean isLife(String agentId) {
        AgentManifest m = require(agentId);
        return CapabilityPackCatalog.LIFE.equals(m.getPersonaProfile())
                || (m.getCapabilityPacks() != null && m.getCapabilityPacks().contains(CapabilityPackCatalog.LIFE));
    }

    public boolean isScribe(String agentId) {
        AgentManifest m = require(agentId);
        return "scribe".equalsIgnoreCase(m.getId())
                || "scribe".equalsIgnoreCase(m.getPersonaProfile())
                || (m.getCapabilityPacks() != null && m.getCapabilityPacks().contains(CapabilityPackCatalog.TYPESETTING));
    }

    public boolean isExecutorFlavor(String agentId) {
        AgentManifest m = require(agentId);
        return CapabilityPackCatalog.EXECUTOR.equals(m.getPersonaProfile())
                || AGENT.equals(m.getId())
                || "agent".equalsIgnoreCase(m.getEmotionProfile());
    }

    /** null = unrestricted */
    public Set<String> toolWhitelist(String agentId) {
        return CapabilityPackCatalog.resolveTools(require(agentId).getCapabilityPacks());
    }

    public String loadPersonaMarkdown(String agentId) {
        AgentManifest m = require(agentId);
        Path persona = agentDir(m.getId()).resolve("persona.md");
        try {
            if (Files.isRegularFile(persona)) {
                return Files.readString(persona, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.err.println("[AgentRegistry] read persona: " + e.getMessage());
        }
        // fallback workspace role cards
        try {
            if (isLife(m.getId())) {
                Path card = resolveWorkspacePersona("researcher.md");
                if (!Files.isRegularFile(card)) card = resolveWorkspacePersona("persona_template.md");
                if (Files.isRegularFile(card)) return Files.readString(card, StandardCharsets.UTF_8);
            }
            if (isExecutorFlavor(m.getId())) {
                Path card = resolveWorkspacePersona("persona_agent.md");
                if (Files.isRegularFile(card)) return Files.readString(card, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        return "你是 Ekina 上的 Agent「" + m.getDisplayName() + "」。用简体中文回复主人。";
    }

    public synchronized AgentManifest create(Map<String, Object> body) {
        String template = str(body.get("template"), "executor_blank");
        String id = normalizeId(str(body.get("id"), "agent_" + Integer.toHexString((int) (Math.random() * 0xfffffff))));
        if (agents.containsKey(id)) {
            throw new IllegalArgumentException("agent id already exists: " + id);
        }
        AgentManifest m = new AgentManifest();
        m.setId(id);
        m.setDisplayName(str(body.get("displayName"), "新 Agent"));
        m.setSignature(str(body.get("signature"), "请吩咐"));
        m.setAvatarUrl(str(body.get("avatarUrl"), "/assets/ekina-logo.png"));
        m.setEnabled(body.get("enabled") == null || Boolean.TRUE.equals(body.get("enabled")));
        m.setThreadId(str(body.get("threadId"), "contact_" + id));
        m.setPreset(false);
        if ("life".equalsIgnoreCase(template)) {
            m.setPersonaProfile(CapabilityPackCatalog.LIFE);
            m.setCapabilityPacks(List.of(CapabilityPackCatalog.RESEARCH));
        } else {
            m.setPersonaProfile(CapabilityPackCatalog.EXECUTOR);
            m.setCapabilityPacks(List.of(
                    CapabilityPackCatalog.EXECUTOR,
                    CapabilityPackCatalog.FILES,
                    CapabilityPackCatalog.CREW_READONLY,
                    CapabilityPackCatalog.COMPANION
            ));
            m.setEmotionProfile("");
        }
        applyPacksFromBody(m, body);
        agents.put(id, m);
        String persona = body.containsKey("personaMarkdown")
                ? String.valueOf(body.get("personaMarkdown"))
                : defaultBlankPersona(m);
        saveAgent(m, persona);
        ensureWorkspace(m.getId());
        return copy(m);
    }

    public synchronized AgentManifest patch(String id, Map<String, Object> body) {
        AgentManifest m = find(id).orElseThrow(() -> new IllegalArgumentException("agent not found: " + id));
        if (body.containsKey("displayName")) m.setDisplayName(str(body.get("displayName"), m.getDisplayName()));
        if (body.containsKey("signature")) m.setSignature(str(body.get("signature"), m.getSignature()));
        if (body.containsKey("avatarUrl")) m.setAvatarUrl(str(body.get("avatarUrl"), m.getAvatarUrl()));
        if (body.containsKey("enabled")) m.setEnabled(Boolean.TRUE.equals(body.get("enabled"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("enabled"))));
        if (body.containsKey("personaProfile")) m.setPersonaProfile(str(body.get("personaProfile"), m.getPersonaProfile()));
        if (body.containsKey("emotionProfile")) m.setEmotionProfile(str(body.get("emotionProfile"), ""));
        applyPacksFromBody(m, body);
        String persona = null;
        if (body.containsKey("personaMarkdown")) {
            persona = String.valueOf(body.get("personaMarkdown"));
        }
        saveAgent(m, persona);
        return copy(m);
    }

    public void bindSession(String agentId) {
        AgentManifest m = require(agentId);
        ChatSessionContext.set(m.getThreadId(), m.getThreadId());
        ChatSessionContext.setContactId(m.getId());
    }

    private void applyPacksFromBody(AgentManifest m, Map<String, Object> body) {
        if (body == null || !body.containsKey("capabilityPacks")) return;
        Object raw = body.get("capabilityPacks");
        List<String> packs = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) packs.add(String.valueOf(o).trim().toLowerCase());
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            for (String p : s.split("[,\\s]+")) {
                if (!p.isBlank()) packs.add(p.trim().toLowerCase());
            }
        }
        if (!packs.isEmpty()) m.setCapabilityPacks(packs);
    }

    private void seedPresets() {
        AgentManifest ekina = new AgentManifest();
        ekina.setId(EKINA);
        ekina.setDisplayName("Ekina");
        ekina.setSignature("AI 科研 · 证据驱动 · 可追溯交付");
        ekina.setAvatarUrl("/shell/assets/ekina-mark.svg");
        ekina.setThreadId(THREAD_EKINA);
        ekina.setEnabled(true);
        ekina.setPreset(true);
        ekina.setPersonaProfile(CapabilityPackCatalog.LIFE);
        ekina.setCapabilityPacks(List.of(CapabilityPackCatalog.RESEARCH));
        agents.put(EKINA, ekina);
        saveAgent(ekina, null);
        copyWorkspacePersonaIfMissing(EKINA, "researcher.md", "persona_template.md");

        AgentManifest agent = new AgentManifest();
        agent.setId(AGENT);
        agent.setDisplayName("代理人");
        agent.setSignature("请吩咐");
        agent.setAvatarUrl("/shell/assets/ekina-mark.svg"); // from 头像/代理人.jpg
        agent.setThreadId(THREAD_AGENT);
        agent.setEnabled(true);
        agent.setPreset(true);
        agent.setPersonaProfile(CapabilityPackCatalog.EXECUTOR);
        agent.setEmotionProfile("agent");
        agent.setCapabilityPacks(List.of(CapabilityPackCatalog.EXECUTOR));
        agents.put(AGENT, agent);
        saveAgent(agent, null);
        copyWorkspacePersonaIfMissing(AGENT, "persona_agent.md");
    }

    private void ensurePreset(String id) {
        if (agents.containsKey(id)) return;
        if (EKINA.equals(id)) {
            AgentManifest ekina = new AgentManifest();
            ekina.setId(EKINA);
            ekina.setDisplayName("Ekina");
            ekina.setSignature("AI 科研 · 证据驱动 · 可追溯交付");
            ekina.setAvatarUrl("/shell/assets/ekina-mark.svg");
            ekina.setThreadId(THREAD_EKINA);
            ekina.setEnabled(true);
            ekina.setPreset(true);
            ekina.setPersonaProfile(CapabilityPackCatalog.LIFE);
            ekina.setCapabilityPacks(List.of(CapabilityPackCatalog.RESEARCH));
            agents.put(EKINA, ekina);
            saveAgent(ekina, null);
            copyWorkspacePersonaIfMissing(EKINA, "researcher.md", "persona_template.md");
        } else if (AGENT.equals(id)) {
            AgentManifest agent = new AgentManifest();
            agent.setId(AGENT);
            agent.setDisplayName("代理人");
            agent.setSignature("请吩咐");
            agent.setAvatarUrl("/shell/assets/ekina-mark.svg"); // from 头像/代理人.jpg
            agent.setThreadId(THREAD_AGENT);
            agent.setEnabled(true);
            agent.setPreset(true);
            agent.setPersonaProfile(CapabilityPackCatalog.EXECUTOR);
            agent.setEmotionProfile("agent");
            agent.setCapabilityPacks(List.of(CapabilityPackCatalog.EXECUTOR));
            agents.put(AGENT, agent);
            saveAgent(agent, null);
            copyWorkspacePersonaIfMissing(AGENT, "persona_agent.md");
        }
    }

    private Path resolveWorkspacePersona(String name) {
        Path inPersonas = Path.of(workspaceDir, "personas", name);
        if (Files.isRegularFile(inPersonas)) return inPersonas;
        return Path.of(workspaceDir, name);
    }

    private void copyWorkspacePersonaIfMissing(String agentId, String... candidates) {
        Path dest = agentDir(agentId).resolve("persona.md");
        try {
            if (Files.isRegularFile(dest) && Files.size(dest) > 0) return;
            for (String name : candidates) {
                Path src = resolveWorkspacePersona(name);
                if (Files.isRegularFile(src)) {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest);
                    return;
                }
            }
            Files.createDirectories(dest.getParent());
            Files.writeString(dest, defaultBlankPersona(require(agentId)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[AgentRegistry] persona seed: " + e.getMessage());
        }
    }

    private void ensureWorkspaces() {
        for (AgentManifest m : agents.values()) {
            ensureWorkspace(m.getId());
        }
    }

    private void ensureWorkspace(String id) {
        try {
            Path dir = homeQuotaService.homePath().resolve("workspace").resolve(normalizeId(id));
            Files.createDirectories(dir);
            Path readme = dir.resolve("README.md");
            if (!Files.exists(readme)) {
                Files.writeString(readme, "# " + id + " 工作区\n", StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.err.println("[AgentRegistry] workspace: " + e.getMessage());
        }
    }

    private void loadAll() {
        agents.clear();
        Path root = agentsRoot();
        try {
            if (!Files.isDirectory(root)) return;
            try (var stream = Files.list(root)) {
                stream.filter(Files::isDirectory).forEach(dir -> {
                    Path mf = dir.resolve("manifest.json");
                    if (!Files.isRegularFile(mf)) return;
                    try {
                        AgentManifest m = mapper.readValue(Files.readString(mf, StandardCharsets.UTF_8), AgentManifest.class);
                        if (m.getId() == null || m.getId().isBlank()) m.setId(dir.getFileName().toString());
                        agents.put(normalizeId(m.getId()), m);
                    } catch (Exception e) {
                        System.err.println("[AgentRegistry] load " + mf + ": " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            System.err.println("[AgentRegistry] loadAll: " + e.getMessage());
        }
    }

    private void saveAgent(AgentManifest m, String personaMarkdownOrNull) {
        try {
            Path dir = agentDir(m.getId());
            Files.createDirectories(dir);
            Path mf = dir.resolve("manifest.json");
            Files.writeString(mf, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(m), StandardCharsets.UTF_8);
            // Only write persona when explicitly provided. Seeds call copyWorkspacePersonaIfMissing after save.
            if (personaMarkdownOrNull != null) {
                Files.writeString(dir.resolve("persona.md"), personaMarkdownOrNull, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.err.println("[AgentRegistry] save: " + e.getMessage());
        }
    }

    private void enrichRuntime(String userId, AgentManifest m) {
        if (AGENT.equals(m.getId()) || "agent".equalsIgnoreCase(m.getEmotionProfile())) {
            try {
                m.setSignature(agentEmotionService.contactSignature());
            } catch (Exception ignored) {
            }
        }
        try {
            // list() returns newest-first; pick the newest owner-visible chat line
            List<ChannelMessage> recent = channelService.list(userId, m.getThreadId(), 20);
            for (ChannelMessage msg : recent) {
                if (msg == null || msg.getText() == null || msg.getText().isBlank()) continue;
                String typ = msg.getType() == null ? "" : msg.getType();
                if (!"chat".equals(typ)) continue;
                Map<?, ?> pl = msg.getPayload();
                if (pl != null && Boolean.TRUE.equals(pl.get("silent")) && !"chat".equals(typ)) continue;
                String t = msg.getText().trim().replaceAll("\\s+", " ");
                // Prefer human dialogue over autonomy tick slogans in the rail preview
                if (t.matches("(?i)^#*\\s*tick\\s*#?\\d+.*")) continue;
                if (t.contains("【附件】") && t.length() > 60) {
                    t = t.replaceFirst("(?s)【附件】.*", "").trim();
                    if (t.isBlank()) t = "发送了附件";
                }
                if (t.length() > 48) t = t.substring(0, 45) + "…";
                m.setLastPreview(t);
                m.setLastTs(msg.getTs());
                break;
            }
        } catch (Exception ignored) {
        }
    }

    private static AgentManifest copy(AgentManifest src) {
        try {
            ObjectMapper om = new ObjectMapper();
            return om.readValue(om.writeValueAsString(src), AgentManifest.class);
        } catch (Exception e) {
            AgentManifest m = new AgentManifest();
            m.setId(src.getId());
            m.setDisplayName(src.getDisplayName());
            m.setSignature(src.getSignature());
            m.setAvatarUrl(src.getAvatarUrl());
            m.setEnabled(src.isEnabled());
            m.setThreadId(src.getThreadId());
            m.setCapabilityPacks(src.getCapabilityPacks());
            m.setPersonaProfile(src.getPersonaProfile());
            m.setPreset(src.isPreset());
            m.setEmotionProfile(src.getEmotionProfile());
            return m;
        }
    }

    private static String defaultBlankPersona(AgentManifest m) {
        return "# " + m.getDisplayName() + "\n\n你是 Ekina 上的自定义 Agent。用简体中文、听令办事。\n";
    }

    private static String normalizeId(String id) {
        if (id == null || id.isBlank()) return "agent";
        return id.trim().toLowerCase().replaceAll("[^a-z0-9_\\-\\u4e00-\\u9fff]", "_");
    }

    private static String str(Object v, String def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? def : s;
    }
}
