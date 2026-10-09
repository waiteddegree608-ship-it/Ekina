package com.ekina.application.multiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Heuristic + JSON-assisted planner that turns an owner goal into a Mission DAG.
 * Chief can also pass an explicit nodes JSON for full control.
 */
@Component
public class TaskGraphPlanner {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param goal owner goal text
     * @param nodesJson optional JSON array of nodes:
     *   [{id?, assignee, title, brief, dependsOn?[]}]
     */
    public Mission plan(String userId, String goal, String nodesJson) {
        Mission m = new Mission();
        m.setId("msn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        m.setUserId(userId);
        m.setGoal(goal == null ? "" : goal.trim());
        m.setStatus("planning");
        m.setCreatedAt(java.time.Instant.now().toString());

        List<MissionNode> nodes;
        if (nodesJson != null && !nodesJson.isBlank()) {
            nodes = parseNodesJson(nodesJson);
        } else {
            nodes = heuristicDecompose(goal);
        }
        validateDag(nodes);
        m.setNodes(nodes);
        m.setStatus("running");
        m.setStartedAt(java.time.Instant.now().toString());
        return m;
    }

    private List<MissionNode> parseNodesJson(String nodesJson) {
        try {
            JsonNode root = mapper.readTree(nodesJson);
            if (!root.isArray()) {
                throw new IllegalArgumentException("nodesJson must be a JSON array");
            }
            List<MissionNode> nodes = new ArrayList<>();
            int i = 0;
            for (JsonNode n : root) {
                MissionNode node = new MissionNode();
                String id = text(n, "id");
                if (id == null || id.isBlank()) {
                    id = "n" + (++i);
                }
                node.setId(id);
                node.setAssignee(parseAssignee(text(n, "assignee")));
                node.setTitle(nullTo(text(n, "title"), id));
                node.setBrief(nullTo(text(n, "brief"), node.getTitle()));
                List<String> deps = new ArrayList<>();
                JsonNode d = n.get("dependsOn");
                if (d != null && d.isArray()) {
                    for (JsonNode x : d) {
                        if (x.isTextual() && !x.asText().isBlank()) deps.add(x.asText().trim());
                    }
                }
                node.setDependsOn(deps);
                node.setStatus("pending");
                nodes.add(node);
            }
            if (nodes.isEmpty()) {
                throw new IllegalArgumentException("nodesJson array is empty");
            }
            return nodes;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid nodesJson: " + e.getMessage(), e);
        }
    }

    /**
     * Lightweight keyword split so Chief can accept a goal without hand-writing JSON.
     * Complex goals should prefer explicit nodesJson from the LLM.
     */
    private List<MissionNode> heuristicDecompose(String goal) {
        String g = goal == null ? "" : goal.trim();
        List<MissionNode> nodes = new ArrayList<>();
        boolean needsCode = looksLikeCode(g);
        boolean needsClaw = looksLikeClaw(g);

        if (needsCode && needsClaw) {
            MissionNode code = node("n1", AgentRole.CODER,
                    "实现/改代码部分",
                    "针对目标完成编程相关工作：\n" + g
                            + "\n完成后写 RESULT.md 与 RESULT.json。");
            MissionNode claw = node("n2", AgentRole.CLAW,
                    "通道/自动化收尾",
                    "在编程产物就绪后，做通道或本机自动化收尾：\n" + g
                            + "\n依赖节点 n1。完成后回复含 RESULT_OK。");
            claw.getDependsOn().add("n1");
            nodes.add(code);
            nodes.add(claw);
        } else if (needsClaw && !needsCode) {
            nodes.add(node("n1", AgentRole.CLAW,
                    "执行自动化/通道任务",
                    g + "\n完成后回复含 RESULT_OK。"));
        } else {
            // default CODER — most owner "do X" goals are implementation
            nodes.add(node("n1", AgentRole.CODER,
                    "执行编程任务",
                    g + "\n完成后写 RESULT.md 与 RESULT.json（status=succeeded）。"));
        }
        return nodes;
    }

    private static MissionNode node(String id, AgentRole role, String title, String brief) {
        MissionNode n = new MissionNode();
        n.setId(id);
        n.setAssignee(role);
        n.setTitle(title);
        n.setBrief(brief);
        n.setStatus("pending");
        return n;
    }

    private static boolean looksLikeCode(String g) {
        String s = g.toLowerCase(Locale.ROOT);
        return s.contains("代码") || s.contains("编程") || s.contains("写") || s.contains("改")
                || s.contains("修") || s.contains("bug") || s.contains("ssh") || s.contains("训练")
                || s.contains("python") || s.contains("java") || s.contains("脚本")
                || s.contains("实现") || s.contains("重构") || s.contains("编译")
                || s.contains("code") || s.contains("build") || s.contains("test");
    }

    private static boolean looksLikeClaw(String g) {
        String s = g.toLowerCase(Locale.ROOT);
        return s.contains("通道") || s.contains("微信") || s.contains("telegram") || s.contains("discord")
                || s.contains("自动化") || s.contains("通知") || s.contains("消息") || s.contains("openclaw")
                || s.contains("龙虾") || s.contains("发送") || s.contains("广播");
    }

    private static AgentRole parseAssignee(String raw) {
        if (raw == null || raw.isBlank()) return AgentRole.CODER;
        String r = raw.trim().toUpperCase(Locale.ROOT);
        if (r.contains("CLAW") || r.contains("OPENCLAW") || r.contains("龙虾")) return AgentRole.CLAW;
        if (r.contains("SCRIBE") || r.contains("排版") || r.contains("TYPESET") || r.contains("美化")) {
            return AgentRole.SCRIBE;
        }
        return AgentRole.CODER;
    }

    private void validateDag(List<MissionNode> nodes) {
        Map<String, MissionNode> byId = new LinkedHashMap<>();
        for (MissionNode n : nodes) {
            if (n.getId() == null || n.getId().isBlank()) {
                throw new IllegalArgumentException("node id required");
            }
            if (byId.containsKey(n.getId())) {
                throw new IllegalArgumentException("duplicate node id: " + n.getId());
            }
            byId.put(n.getId(), n);
        }
        for (MissionNode n : nodes) {
            for (String dep : n.getDependsOn()) {
                if (!byId.containsKey(dep)) {
                    throw new IllegalArgumentException("node " + n.getId() + " depends on missing " + dep);
                }
            }
        }
        // cycle check
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (MissionNode n : nodes) {
            if (hasCycle(n.getId(), byId, visiting, visited)) {
                throw new IllegalArgumentException("cycle detected in task graph");
            }
        }
    }

    private boolean hasCycle(String id, Map<String, MissionNode> byId, Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return false;
        if (visiting.contains(id)) return true;
        visiting.add(id);
        MissionNode n = byId.get(id);
        if (n != null) {
            for (String dep : n.getDependsOn()) {
                if (hasCycle(dep, byId, visiting, visited)) return true;
            }
        }
        visiting.remove(id);
        visited.add(id);
        return false;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String nullTo(String s, String d) {
        return s == null || s.isBlank() ? d : s;
    }
}
