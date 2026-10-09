package com.ekina.application.project;

import java.time.Instant;
import java.util.*;

/** Append-only research reports. Review status describes recorded checks, never truth. */
public final class ResearchLedger {
    private ResearchLedger() {}
    private static final int MAX_RECORDS = 1000;

    public static List<Map<String, Object>> append(Object current, Object update) {
        List<Map<String, Object>> records = records(current);
        Map<?, ?> request = ResearchPlan.object(update);
        Object revision = request.get("expectedRevision");
        if (!(revision instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 0)
            throw new IllegalArgumentException("研究记录需要有效的 expectedRevision；请先读取课题板");
        List<?> incoming = array(request.get("records"), 20);
        if (incoming.isEmpty()) throw new IllegalArgumentException("研究记录不能为空");
        Map<String, Map<String, Object>> index = index(records);
        int before = records.size();
        Set<String> batchIds = new HashSet<>();
        for (Object raw : incoming) {
            Map<String, Object> entry = normalize(raw);
            String id = entry.get("id").toString();
            if (!batchIds.add(id)) throw new IllegalArgumentException("同一批记录 id 重复");
            Map<String, Object> existing = index.get(id);
            if (existing != null) {
                Map<String, Object> content = new LinkedHashMap<>(existing);
                content.remove("at");
                if (!content.equals(entry)) throw new IllegalArgumentException("记录不可改写；请用新 id 补充或撤回原记录");
                continue;
            }
            validateReferences(entry, index);
            entry.put("at", Instant.now().toString());
            records.add(entry);
            index.put(id, entry);
        }
        // An exact retry succeeds even if later records have since been appended.
        if (records.size() != before && ((Number) revision).intValue() != before)
            throw new Conflict("研究记录已更新，请重新读取课题板后合并提交");
        if (records.size() > MAX_RECORDS) throw new IllegalArgumentException("研究记录已达容量上限，原记录已保留；请另建后续课题");
        return records;
    }

    public static final class Conflict extends IllegalArgumentException {
        public Conflict(String message) { super(message); }
    }

    private static Map<String, Object> normalize(Object raw) {
        Map<?, ?> m = ResearchPlan.object(raw);
        Map<String, Object> out = new LinkedHashMap<>();
        String id = ResearchPlan.required(m.get("id"), 80);
        if (!id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("记录 id 仅支持字母、数字、下划线和连字符");
        out.put("id", id);
        String type = choice(m.get("type"), "attempt", "evidence", "review", "conclusion", "withdrawal", "checkpoint");
        out.put("type", type);
        switch (type) {
            case "attempt" -> {
                required(m, out, "question", "method", "result");
                out.put("outcome", choice(m.get("outcome"), "supported", "refuted", "inconclusive", "failed"));
                out.put("artifacts", strings(m.get("artifacts"), 12, 1500));
                optional(m, out, "limitations", "stepId");
            }
            case "evidence" -> {
                required(m, out, "title", "source", "summary", "conditions", "producer");
                out.put("kind", choice(m.get("kind"), "literature", "experiment", "analysis"));
                optional(m, out, "attemptId");
            }
            case "review" -> {
                required(m, out, "evidenceId", "reviewer", "summary", "artifact");
                out.put("method", choice(m.get("method"), "source_check", "execution_check", "independent_reproduction", "model_review"));
                out.put("verdict", choice(m.get("verdict"), "supports", "concerns", "rejects"));
            }
            case "conclusion" -> {
                required(m, out, "title", "statement", "conditions");
                out.put("evidenceIds", strings(m.get("evidenceIds"), 24, 80));
                if (((List<?>) out.get("evidenceIds")).isEmpty()) throw new IllegalArgumentException("结论必须引用证据");
                out.put("dependsOn", strings(m.get("dependsOn"), 24, 80));
                optional(m, out, "limitations", "stepId");
            }
            case "withdrawal" -> required(m, out, "targetId", "reason");
            case "checkpoint" -> {
                required(m, out, "question", "nextAction", "continueWhen", "stopWhen", "resources");
                out.put("basisIds", strings(m.get("basisIds"), 24, 80));
                // Share the roadmap's bounds and estimate-basis validation.
                Map<String, Object> estimate = new LinkedHashMap<>();
                estimate.put("id", id); estimate.put("title", "下一轮验证");
                for (String key : List.of("costMin", "costMax", "currency", "estimateBasis"))
                    if (m.containsKey(key)) estimate.put(key, m.get(key));
                Map<String, Object> checked = ResearchPlan.roadmap(List.of(estimate)).getFirst();
                for (String key : List.of("costMin", "costMax", "currency", "estimateBasis")) out.put(key, checked.get(key));
            }
            default -> throw new IllegalArgumentException("不支持的记录类型");
        }
        return out;
    }

    private static void validateReferences(Map<String, Object> entry, Map<String, Map<String, Object>> index) {
        switch (entry.get("type").toString()) {
            case "evidence" -> {
                if (!entry.get("attemptId").toString().isBlank()) reference(index, entry.get("attemptId"), "attempt");
            }
            case "review" -> reference(index, entry.get("evidenceId"), "evidence");
            case "conclusion" -> {
                for (Object id : (List<?>) entry.get("evidenceIds")) reference(index, id, "evidence");
                // Only earlier records are referencable, so dependency cycles cannot be written.
                for (Object id : (List<?>) entry.get("dependsOn")) reference(index, id, "conclusion");
            }
            case "withdrawal" -> reference(index, entry.get("targetId"), "evidence", "review", "conclusion", "checkpoint");
            case "checkpoint" -> {
                for (Object id : (List<?>) entry.get("basisIds")) reference(index, id, "attempt", "evidence", "review", "conclusion");
            }
        }
    }

    private static void reference(Map<String, Map<String, Object>> index, Object id, String... types) {
        Map<String, Object> found = index.get(String.valueOf(id));
        if (found == null || !List.of(types).contains(found.get("type")))
            throw new IllegalArgumentException("引用不存在或类型不匹配: " + id);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> view(Object raw) {
        List<Map<String, Object>> records = records(raw);
        Set<String> withdrawn = new HashSet<>();
        Map<String, String> reasons = new HashMap<>();
        for (Map<String, Object> r : records) if ("withdrawal".equals(r.get("type"))) {
            withdrawn.add(r.get("targetId").toString());
            reasons.put(r.get("targetId").toString(), r.get("reason").toString());
        }
        Map<String, List<Map<String, Object>>> reviews = new HashMap<>();
        for (Map<String, Object> r : records)
            if ("review".equals(r.get("type")) && !withdrawn.contains(r.get("id").toString()))
                reviews.computeIfAbsent(r.get("evidenceId").toString(), x -> new ArrayList<>()).add(r);
        Map<String, String> statuses = new HashMap<>();
        // Reviews may be recorded later than the conclusions they affect.
        for (Map<String, Object> r : records) if ("evidence".equals(r.get("type"))) {
            String id = r.get("id").toString();
            List<Map<String, Object>> checks = reviews.getOrDefault(id, List.of());
            String status = withdrawn.contains(id) ? "withdrawn" : checks.isEmpty() ? "unreviewed"
                    : checks.stream().anyMatch(c -> !"supports".equals(c.get("verdict"))) ? "contested" : "reviewed";
            statuses.put(id, status);
        }
        for (Map<String, Object> r : records) if ("review".equals(r.get("type"))) {
            String id = r.get("id").toString();
            String evidenceState = statuses.getOrDefault(r.get("evidenceId").toString(), "unreviewed");
            statuses.put(id, withdrawn.contains(id) ? "withdrawn" : "withdrawn".equals(evidenceState)
                    ? "needs_review" : "contested".equals(evidenceState) ? "contested" : "reviewed");
        }
        for (Map<String, Object> r : records) if ("conclusion".equals(r.get("type"))) {
            String id = r.get("id").toString();
            List<String> refs = new ArrayList<>((List<String>) r.get("evidenceIds"));
            refs.addAll((List<String>) r.get("dependsOn"));
            List<String> states = refs.stream().map(x -> statuses.getOrDefault(x, "unreviewed")).toList();
            String status = withdrawn.contains(id) ? "withdrawn"
                    : states.stream().anyMatch(s -> s.equals("withdrawn") || s.equals("needs_review")) ? "needs_review"
                    : states.contains("contested") ? "contested"
                    : states.contains("unreviewed") ? "unreviewed" : "reviewed";
            statuses.put(id, status);
        }
        List<Map<String, Object>> rendered = new ArrayList<>();
        Map<String, Object> checkpoint = null;
        for (Map<String, Object> r : records) {
            Map<String, Object> copy = new LinkedHashMap<>(r);
            String id = r.get("id").toString();
            if (statuses.containsKey(id)) copy.put("assessment", statuses.get(id));
            if (withdrawn.contains(id)) { copy.put("assessment", "withdrawn"); copy.put("withdrawalReason", reasons.get(id)); }
            if ("evidence".equals(r.get("type"))) copy.put("reviewIds", reviews.getOrDefault(id, List.of()).stream().map(c -> c.get("id")).toList());
            if ("checkpoint".equals(r.get("type"))) {
                boolean stale = ((List<?>) r.get("basisIds")).stream().anyMatch(x -> withdrawn.contains(x.toString())
                        || List.of("contested", "needs_review").contains(statuses.getOrDefault(x.toString(), "")));
                copy.put("assessment", withdrawn.contains(id) ? "withdrawn" : stale ? "needs_review" : "planned");
                checkpoint = copy;
            }
            rendered.add(copy);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revision", records.size());
        out.put("records", rendered);
        out.put("checkpoint", checkpoint);
        out.put("attentionCount", rendered.stream().filter(r -> "conclusion".equals(r.get("type")))
                .filter(r -> List.of("needs_review", "contested", "unreviewed").contains(r.get("assessment"))).count());
        return out;
    }

    public static Map<String, Object> summary(Object raw) {
        Map<String, Object> view = view(raw);
        List<Map<String, Object>> entries = records(view.get("records"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revision", view.get("revision"));
        out.put("attentionCount", view.get("attentionCount"));
        Map<?, ?> next = view.get("checkpoint") instanceof Map<?, ?> m ? m : Map.of();
        out.put("nextQuestion", shortText(next.get("question")));
        out.put("nextAction", shortText(next.get("nextAction")));
        out.put("checkpointAssessment", next.getOrDefault("assessment", null));
        out.put("conclusions", entries.stream().filter(r -> "conclusion".equals(r.get("type")))
                .sorted(Comparator.comparingInt(r -> "reviewed".equals(r.get("assessment")) ? 1 : 0)).limit(5)
                .map(r -> Map.of("id", r.get("id"), "title", shortText(r.get("title")), "assessment", r.get("assessment"))).toList());
        return out;
    }

    private static String shortText(Object value) {
        String s = value == null ? "" : value.toString();
        return s.substring(0, Math.min(400, s.length()));
    }

    private static List<Map<String, Object>> records(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : array(raw, MAX_RECORDS)) {
            Map<String, Object> copy = new LinkedHashMap<>();
            ResearchPlan.object(item).forEach((k, v) -> copy.put(String.valueOf(k), v));
            out.add(copy);
        }
        return out;
    }

    private static Map<String, Map<String, Object>> index(List<Map<String, Object>> records) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map<String, Object> record : records) index.put(record.get("id").toString(), record);
        return index;
    }

    private static List<?> array(Object raw, int limit) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list) || list.size() > limit) throw new IllegalArgumentException("研究记录列表格式或长度无效");
        return list;
    }

    private static List<String> strings(Object raw, int limit, int length) {
        List<String> values = array(raw, limit).stream().map(v -> ResearchPlan.required(v, length)).toList();
        if (new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException("引用不能重复");
        return values;
    }

    private static String choice(Object raw, String... choices) {
        String value = ResearchPlan.required(raw, 40);
        if (!List.of(choices).contains(value)) throw new IllegalArgumentException("无效的研究记录选项: " + value);
        return value;
    }

    private static void required(Map<?, ?> in, Map<String, Object> out, String... keys) {
        for (String key : keys) out.put(key, ResearchPlan.required(in.get(key), key.endsWith("Id") ? 80 : 3000));
    }

    private static void optional(Map<?, ?> in, Map<String, Object> out, String... keys) {
        for (String key : keys) out.put(key, ResearchPlan.text(in.get(key), key.endsWith("Id") ? 80 : 3000));
    }
}
