package com.ekina.application.agentreg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static capability packs → tool name whitelists.
 * Pack {@code life} means unrestricted (null set).
 */
public final class CapabilityPackCatalog {

    public static final String RESEARCH = "research";

    public static final String LIFE = "life";
    public static final String EXECUTOR = "executor";
    public static final String FILES = "files";
    public static final String CREW_READONLY = "crew_readonly";
    public static final String COMPANION = "companion";
    public static final String EMOTION_AGENT = "emotion_agent";
    public static final String TYPESETTING = "typesetting";
    public static final String SCRIBE = "scribe";

    private CapabilityPackCatalog() {}

    public static List<Map<String, Object>> listPacks() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(pack(RESEARCH, "科研工作台", "文献、课题、实验、写作与审查", tools(RESEARCH)));
        out.add(pack(EXECUTOR, "听令执行", "代理人默认包：文件+进度+权限+情感", tools(EXECUTOR)));
        out.add(pack(FILES, "文件读写", "浏览/读文档/写笔记/配额", tools(FILES)));
        out.add(pack(CREW_READONLY, "小队只读", "查看任务与进度，不派活", tools(CREW_READONLY)));
        out.add(pack(COMPANION, "会话卡片", "主动消息/方案/权限/提醒消解", tools(COMPANION)));
        out.add(pack(TYPESETTING, "学术排版", "期刊模板、写稿、编译、Overleaf", tools(TYPESETTING)));
        return out;
    }

    /** null = unrestricted */
    public static Set<String> resolveTools(List<String> packs) {
        if (packs == null || packs.isEmpty()) {
            return tools(EXECUTOR);
        }
        for (String p : packs) {
            if (LIFE.equalsIgnoreCase(p)) {
                return null;
            }
        }
        Set<String> union = new LinkedHashSet<>();
        for (String p : packs) {
            Set<String> t = tools(p == null ? "" : p.trim().toLowerCase());
            if (t != null) union.addAll(t);
        }
        if (union.isEmpty()) {
            return tools(EXECUTOR);
        }
        return union;
    }

    public static Set<String> tools(String packId) {
        return switch (packId == null ? "" : packId.toLowerCase()) {
            case RESEARCH -> Set.of(
                    "searchWeb", "searchLiterature", "readDocument", "writeMarkdownNote",
                    "listHomeFiles", "getHomeQuota", "listCapabilities", "requestPermission",
                    "createPlanCard", "updateChannelCard", "sendCompanionMessage",
                    "getProjectBoard", "updateProjectBoard", "appendProjectEvent", "listProjectBoards",
                    "dispatchAgentTask", "listAgentTasks", "getAgentTask", "readOpenCodeArtifacts",
                    "getMultiAgentStatus", "acceptMission", "getMission", "listMissions", "cancelMission",
                    "startPaperPipeline", "getPaperProject", "listPaperProjects",
                    "runSshJob", "runSshJobOn", "listComputeHosts",
                    "recordLabEntry", "listLabEntries", "labRemoteStatus",
                    "listVenues", "applyVenueTemplate", "compileLatex", "auditPaper", "beautifyFigures",
                    "openNewPaper", "syncOverleaf", "syncLabGithub"
            );
            case LIFE -> null;
            case EXECUTOR -> Set.of(
                    // PC assistant: local files / index / docs — not SSH, not paper pipeline, not autonomy
                    "listAllowedFolders", "indexLocalFolder", "searchLocalFiles", "listLocalFiles",
                    "listHomeFiles", "readDocument", "writeMarkdownNote", "generateWordDocument",
                    "generatePowerPoint", "annotatePdf", "sendCompanionMessage",
                    "listCapabilities", "requestPermission", "createPlanCard", "updateChannelCard",
                    "getHomeQuota", "dismissReminder", "getAgentEmotion", "updateAgentEmotion"
            );
            case FILES -> Set.of(
                    "listHomeFiles", "readDocument", "writeMarkdownNote", "getHomeQuota"
            );
            case CREW_READONLY -> Set.of(
                    "listAgentTasks", "getAgentTask", "getMultiAgentStatus", "readOpenCodeArtifacts"
            );
            case COMPANION -> Set.of(
                    "sendCompanionMessage", "createPlanCard", "updateChannelCard",
                    "dismissReminder", "requestPermission", "listCapabilities"
            );
            case EMOTION_AGENT -> Set.of("getAgentEmotion", "updateAgentEmotion");
            case TYPESETTING, SCRIBE -> Set.of(
                    "listVenues", "applyVenueTemplate", "compileLatex",
                    "auditPaper", "beautifyFigures",
                    "listHomeFiles", "readDocument", "writeMarkdownNote",
                    "openNewPaper", "syncOverleaf", "syncLabGithub", "labRemoteStatus",
                    "recordLabEntry", "listLabEntries",
                    "listAgentTasks", "getAgentTask", "readOpenCodeArtifacts",
                    "sendCompanionMessage", "getHomeQuota", "listCapabilities"
            );
            default -> Set.of();
        };
    }

    private static Map<String, Object> pack(String id, String title, String desc, Set<String> tools) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("description", desc);
        m.put("unrestricted", tools == null);
        m.put("tools", tools == null ? List.of() : new ArrayList<>(tools));
        return m;
    }
}
