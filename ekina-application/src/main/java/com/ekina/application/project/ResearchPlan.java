package com.ekina.application.project;

import java.util.*;

/** Bounded, validated public research plan. Unknown amounts remain null, never zero. */
public final class ResearchPlan {
    private ResearchPlan() {}

    public static Map<String, Object> collaboration(Object raw) {
        Map<?, ?> m = object(raw);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", choice(m.getOrDefault("mode", null), "checkpoints", "guided", "checkpoints", "delegated"));
        out.put("budget", amount(m.get("budget")));
        out.put("currency", choice(m.get("currency"), "CNY", "CNY", "USD"));
        out.put("priorities", text(m.get("priorities"), 2000));
        return out;
    }

    public static List<Map<String, Object>> roadmap(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object value : array(raw, 24)) {
            Map<?, ?> m = object(value);
            Map<String, Object> row = new LinkedHashMap<>();
            String id = required(m.get("id"), 80);
            if (!ids.add(id)) throw new IllegalArgumentException("路线步骤 id 重复");
            row.put("id", id);
            row.put("title", required(m.get("title"), 160));
            row.put("status", choice(m.get("status"), "planned", "planned", "running", "done", "blocked", "conditional"));
            for (String key : List.of("purpose", "deliverable", "acceptance", "condition", "resources", "estimateBasis"))
                row.put(key, text(m.get(key), 1500));
            row.put("dependsOn", array(m.getOrDefault("dependsOn", null), 24).stream().map(x -> required(x, 80)).toList());
            row.put("costMin", amount(m.get("costMin")));
            row.put("costMax", amount(m.get("costMax")));
            row.put("currency", choice(m.get("currency"), "CNY", "CNY", "USD"));
            if (row.get("costMin") instanceof Double min && row.get("costMax") instanceof Double max && min > max)
                throw new IllegalArgumentException("费用下限不能大于上限");
            if ((row.get("costMin") != null || row.get("costMax") != null) && row.get("estimateBasis").toString().isBlank())
                throw new IllegalArgumentException("费用估算必须注明依据；未知请留空");
            out.add(row);
        }
        for (Map<String, Object> row : out) {
            for (Object dep : (List<?>) row.get("dependsOn"))
                if (!ids.contains(dep) || dep.equals(row.get("id"))) throw new IllegalArgumentException("无效的步骤依赖");
        }
        Map<String, List<?>> deps = new HashMap<>();
        for (Map<String, Object> row : out) deps.put(row.get("id").toString(), (List<?>) row.get("dependsOn"));
        for (String id : ids) visit(id, deps, new HashSet<>(), new HashSet<>());
        return out;
    }

    private static void visit(String id, Map<String, List<?>> deps, Set<String> path, Set<String> done) {
        if (done.contains(id)) return;
        if (!path.add(id)) throw new IllegalArgumentException("路线依赖不能成环");
        for (Object dep : deps.get(id)) visit(dep.toString(), deps, path, done);
        path.remove(id);
        done.add(id);
    }

    public static List<Map<String, Object>> decisions(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object value : array(raw, 16)) {
            Map<?, ?> m = object(value);
            Map<String, Object> row = new LinkedHashMap<>();
            String id = required(m.get("id"), 80);
            if (!ids.add(id)) throw new IllegalArgumentException("决策 id 重复");
            row.put("id", id);
            row.put("kind", choice(m.get("kind"), "consultation", "consultation", "authorization"));
            row.put("title", required(m.get("title"), 160));
            row.put("recommendation", text(m.get("recommendation"), 1500));
            row.put("impact", text(m.get("impact"), 1500));
            row.put("permissionId", text(m.get("permissionId"), 160));
            // A board entry can never grant a capability or mark an authorization approved.
            out.add(row);
        }
        return out;
    }

    public static Map<?, ?> object(Object raw) {
        if (raw instanceof Map<?, ?> m) return m;
        throw new IllegalArgumentException("需要对象格式");
    }

    private static List<?> array(Object raw, int max) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list) || list.size() > max) throw new IllegalArgumentException("列表格式或长度无效");
        return list;
    }

    public static String required(Object raw, int max) {
        String value = text(raw, max);
        if (value.isBlank()) throw new IllegalArgumentException("内容不能为空");
        return value;
    }

    public static String text(Object raw, int max) {
        if (raw == null) return "";
        if (!(raw instanceof String s) || s.length() > max) throw new IllegalArgumentException("文本格式或长度无效");
        return s.trim();
    }

    private static String choice(Object raw, String fallback, String... allowed) {
        String value = raw == null ? fallback : text(raw, 40);
        if (!List.of(allowed).contains(value)) throw new IllegalArgumentException("无效选项: " + value);
        return value;
    }

    private static Double amount(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0 || n.doubleValue() > 100_000_000)
            throw new IllegalArgumentException("金额必须是有限的非负数");
        return n.doubleValue();
    }
}
