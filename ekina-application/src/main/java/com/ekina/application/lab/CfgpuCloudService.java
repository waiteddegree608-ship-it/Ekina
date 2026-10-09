package com.ekina.application.lab;

import com.ekina.application.job.SshJobService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 骋风算力（cfgpu.com）控制台：用官方 API Token 列实例、看状态、开机/关机。
 * 不实现释放/新建（会删盘或产生新费用）。Token 只从环境变量读取。
 */
@Service
public class CfgpuCloudService {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final String GPU_QUERY =
            "nvidia-smi --query-gpu=index,name,utilization.gpu,memory.used,memory.total --format=csv,noheader,nounits; "
                    + "echo '---APPS---'; "
                    + "nvidia-smi --query-compute-apps=pid,process_name,used_gpu_memory --format=csv,noheader,nounits 2>/dev/null || true";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private ComputeHostRegistry computeHostRegistry;

    @Autowired
    @Lazy
    private SshJobService sshJobService;

    @Value("${ekina.cfgpu.api-token:}")
    private String apiToken;

    @Value("${ekina.cfgpu.api-base:https://api.cfgpu.com}")
    private String apiBase;

    @Value("${ekina.reminders.default-user:user-1}")
    private String defaultUser;

    public boolean configured() {
        String t = token();
        return !t.isBlank() && !t.startsWith("your-") && !t.contains("xxx");
    }

    public Map<String, Object> snapshot() {
        return inspect(false);
    }

    public Map<String, Object> inspect(boolean probeGpu) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", "cfgpu");
        out.put("label", "骋风算力");
        out.put("console", "https://www.cfgpu.com/ecs/console/instance");
        out.put("configured", configured());
        out.put("fetchedAt", Instant.now().toString());
        if (!configured()) {
            out.put("ok", false);
            out.put("hint", "在骋风控制台「API Token」建一把钥匙，写入 .env 的 CFGPU_API_TOKEN 后重启后端。不要把网页账密发到聊天里。");
            out.put("instances", List.of());
            return out;
        }
        try {
            JsonNode page = request("POST", "/userapi/v1/instance/page", Map.of(
                    "keyWord", "",
                    "status", "",
                    "currentPage", 1,
                    "pageSize", 50
            ));
            List<Map<String, Object>> instances = flattenInstances(page);
            if (instances.isEmpty()) {
                JsonNode st = requestFlexible("/userapi/v1/instance/status", null);
                instances = flattenInstances(st);
            }
            for (Map<String, Object> inst : instances) {
                attachSshHost(inst);
                if (probeGpu && isRunning(str(inst.get("status")))) {
                    inst.put("gpuLive", probeGpu(str(inst.get("instanceId"))));
                }
            }
            out.put("ok", true);
            out.put("instances", instances);
            out.put("running", instances.stream().filter(i -> isRunning(str(i.get("status")))).count());
            out.put("stopped", instances.stream().filter(i -> !isRunning(str(i.get("status")))).count());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            out.put("instances", List.of());
        }
        return out;
    }

    public Map<String, Object> instanceStatus(String instanceId) {
        String id = resolveId(instanceId);
        if (id == null) return error("missing instanceId");
        try {
            JsonNode n = requestFlexible("/userapi/v1/instance/" + id + "/status", null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("instanceId", id);
            Map<String, Object> one = summarizeOne(unwrapData(n));
            if (one != null) {
                attachSshHost(one);
                out.put("instance", one);
            }
            return out;
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    public Map<String, Object> start(String instanceId) {
        return mutate(instanceId, "start");
    }

    public Map<String, Object> stop(String instanceId, boolean force) {
        String id = resolveId(instanceId);
        if (id == null || id.isBlank()) return error("无法解析实例 id：" + instanceId);
        try {
            Map<String, Object> st = instanceStatus(id);
            @SuppressWarnings("unchecked")
            Map<String, Object> inst = st.get("instance") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : null;
            if (inst != null && !isRunning(str(inst.get("status")))) {
                Map<String, Object> already = new LinkedHashMap<>();
                already.put("ok", true);
                already.put("action", "stop");
                already.put("instanceId", id);
                already.put("message", "already stopped");
                return already;
            }
        } catch (Exception ignored) {
        }
        if (!force) {
            Map<String, Object> gpu = probeGpu(id);
            if (Boolean.TRUE.equals(gpu.get("busy"))) {
                Map<String, Object> refuse = error("GPU 上有计算进程或利用率偏高，拒绝关机以免打断训练。主人明确要求时可 force=true。");
                refuse.put("instanceId", id);
                refuse.put("gpuLive", gpu);
                return refuse;
            }
        }
        return mutate(id, "stop");
    }

    public String resolveId(String hint) {
        if (hint == null || hint.isBlank()) return null;
        String q = hint.trim();
        String fromHost = instanceIdFromHost(q);
        if (fromHost != null) return fromHost;
        if (!configured()) {
            return fallbackKnownId(q);
        }
        try {
            JsonNode page = request("POST", "/userapi/v1/instance/page", Map.of(
                    "keyWord", "",
                    "status", "",
                    "currentPage", 1,
                    "pageSize", 50
            ));
            List<Map<String, Object>> list = flattenInstances(page);
            String ql = q.toLowerCase(Locale.ROOT);
            for (Map<String, Object> i : list) {
                String id = str(i.get("instanceId"));
                String host = str(i.get("sshHost"));
                String name = str(i.get("name"));
                if (id.equalsIgnoreCase(q)
                        || host.toLowerCase(Locale.ROOT).contains(ql)
                        || name.toLowerCase(Locale.ROOT).contains(ql)
                        || id.toLowerCase(Locale.ROOT).contains(ql)) {
                    return id;
                }
            }
            Map<String, Object> ssh = computeHostRegistry.find(q);
            if (ssh != null) {
                String mapped = instanceIdFromHost(str(ssh.get("host")) + " " + str(ssh.get("id")));
                if (mapped != null) return mapped;
            }
        } catch (Exception ignored) {
        }
        return fallbackKnownId(q);
    }

    public Map<String, Object> probeGpu(String instanceHint) {
        Map<String, Object> out = new LinkedHashMap<>();
        String id = resolveId(instanceHint);
        out.put("instanceId", id == null ? instanceHint : id);
        Map<String, Object> host = findSshHost(id == null ? instanceHint : id);
        if (host == null) {
            out.put("ok", false);
            out.put("busy", false);
            out.put("hint", "没有匹配的 SSH 主机，无法探活 GPU。云端关机仍可用。");
            return out;
        }
        String hostHint = str(host.get("id"));
        if (hostHint.isBlank()) hostHint = str(host.get("host"));
        out.put("sshHost", host.get("host"));
        try {
            Map<String, Object> ssh = sshJobService.runRemote(defaultUser, GPU_QUERY, 25, hostHint);
            String text = str(ssh.get("output")) + "\n" + str(ssh.get("error"));
            out.put("sshOk", Boolean.TRUE.equals(ssh.get("ok")));
            if (!Boolean.TRUE.equals(ssh.get("ok"))) {
                String err = str(ssh.get("error")).toLowerCase(Locale.ROOT);
                boolean unreachable = err.contains("connection") || err.contains("timed out")
                        || err.contains("refused") || err.contains("no route")
                        || err.contains("unreachable") || err.contains("auth");
                out.put("ok", false);
                out.put("busy", false);
                out.put("unreachable", unreachable);
                out.put("error", clip(str(ssh.get("error")), 200));
                return out;
            }
            parseNvidiaSmi(text, out);
            out.put("ok", true);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("busy", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    private Map<String, Object> mutate(String instanceId, String action) {
        if (!configured()) return error("CFGPU_API_TOKEN 未配置");
        String id = resolveId(instanceId);
        if (id == null || id.isBlank()) return error("无法解析实例 id：" + instanceId);
        try {
            JsonNode n = request("POST", "/userapi/v1/instance/" + id + "/" + action, Map.of());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", !looksFailed(n));
            out.put("action", action);
            out.put("instanceId", id);
            out.put("message", pickText(n, "message", "msg", "error", "detail"));
            return out;
        } catch (Exception e) {
            return error(action + " failed: " + e.getMessage());
        }
    }

    private void attachSshHost(Map<String, Object> inst) {
        Map<String, Object> host = findSshHost(str(inst.get("instanceId")) + " " + str(inst.get("sshHost")));
        if (host == null) return;
        inst.put("computeHostId", host.get("id"));
        if (str(inst.get("sshHost")).isBlank()) inst.put("sshHost", host.get("host"));
        if (str(inst.get("sshPort")).isBlank() && host.get("port") != null) inst.put("sshPort", host.get("port"));
        if (str(inst.get("sshUser")).isBlank()) inst.put("sshUser", host.get("user"));
    }

    private Map<String, Object> findSshHost(String hint) {
        if (hint == null || hint.isBlank()) return null;
        Map<String, Object> found = computeHostRegistry.find(hint.trim());
        if (found != null) return found;
        String id = instanceIdFromHost(hint);
        if (id != null) {
            found = computeHostRegistry.find(id);
            if (found != null) return found;
            for (Map<String, Object> h : computeHostRegistry.list()) {
                String host = str(h.get("host")).toLowerCase(Locale.ROOT);
                if (host.contains(id.toLowerCase(Locale.ROOT))) return h;
            }
        }
        return null;
    }

    private JsonNode requestFlexible(String path, Object body) throws Exception {
        try {
            return request("POST", path, body == null ? Map.of() : body);
        } catch (Exception postFail) {
            return request("GET", path, null);
        }
    }

    private JsonNode request(String method, String path, Object body) throws Exception {
        try {
            return requestWithAuth(method, path, body, token());
        } catch (Exception first) {
            String msg = first.getMessage() == null ? "" : first.getMessage();
            if (msg.contains("HTTP 401") && !token().toLowerCase(Locale.ROOT).startsWith("bearer ")) {
                return requestWithAuth(method, path, body, "Bearer " + token());
            }
            throw first;
        }
    }

    private JsonNode requestWithAuth(String method, String path, Object body, String auth) throws Exception {
        String base = apiBase == null || apiBase.isBlank() ? "https://api.cfgpu.com" : apiBase.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(25))
                .header("Authorization", auth)
                .header("Accept", "application/json");
        if ("GET".equalsIgnoreCase(method)) {
            b.GET();
        } else {
            String json = body == null ? "{}" : mapper.writeValueAsString(body);
            b.header("Content-Type", "application/json; charset=utf-8");
            b.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        }
        HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String raw = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " " + clip(raw, 240));
        }
        if (raw.isBlank()) return mapper.createObjectNode();
        return mapper.readTree(raw);
    }

    private List<Map<String, Object>> flattenInstances(JsonNode root) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (root == null || root.isMissingNode() || root.isNull()) return out;
        JsonNode arr = firstArray(root,
                "data.records", "data.list", "data.rows", "data.data", "records", "list", "rows", "data");
        if (arr == null || !arr.isArray()) {
            if (root.isArray()) arr = root;
            else if (root.isObject() && (root.has("instanceId") || root.has("id"))) {
                Map<String, Object> one = summarizeOne(root);
                if (one != null) out.add(one);
                return out;
            }
        }
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                Map<String, Object> one = summarizeOne(n);
                if (one != null) out.add(one);
            }
        }
        return out;
    }

    private JsonNode firstArray(JsonNode root, String... dotted) {
        for (String path : dotted) {
            JsonNode n = root;
            for (String p : path.split("\\.")) {
                n = n.path(p);
            }
            if (n.isArray()) return n;
        }
        return null;
    }

    private JsonNode unwrapData(JsonNode n) {
        if (n == null) return mapper.createObjectNode();
        if (n.isArray() && n.size() > 0) return n.get(0);
        JsonNode data = n.path("data");
        if (data.isObject()) return data;
        if (data.isArray() && data.size() > 0) return data.get(0);
        return n;
    }

    private Map<String, Object> summarizeOne(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.has("data") && n.path("data").isObject() && !n.has("instanceId") && !n.has("id")) {
            n = n.path("data");
        }
        String id = pickText(n, "instanceId", "instance_id", "id", "vmId");
        if (id.isBlank()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", id);
        m.put("name", pickText(n, "instanceName", "name", "displayName"));
        m.put("status", pickText(n, "status", "state", "instanceStatus"));
        m.put("gpu", pickText(n, "gpuType", "gpuName", "gpu", "spec"));
        m.put("gpuNum", pickText(n, "gpuNum", "gpuCount"));
        m.put("region", pickText(n, "regionName", "region", "regionCode"));
        m.put("priceType", pickText(n, "priceType", "chargeType", "billing"));
        m.put("sshHost", pickText(n, "sshHost", "sshAddress", "publicIp", "ip", "address"));
        m.put("sshPort", pickText(n, "sshPort", "port"));
        m.put("sshUser", pickText(n, "sshUser", "user"));
        return m;
    }

    private static void parseNvidiaSmi(String text, Map<String, Object> out) {
        List<Map<String, Object>> gpus = new ArrayList<>();
        List<Map<String, Object>> apps = new ArrayList<>();
        int maxUtil = 0;
        boolean appsSection = false;
        for (String line : text.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isBlank()) continue;
            if (s.contains("---APPS---")) {
                appsSection = true;
                continue;
            }
            if (!appsSection) {
                String[] p = splitCsv(s);
                if (p.length >= 3 && p[0].matches("\\d+")) {
                    int util = parseIntSafe(p[2]);
                    maxUtil = Math.max(maxUtil, util);
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("index", p[0]);
                    if (p.length > 1) g.put("name", p[1].trim());
                    g.put("util", util);
                    if (p.length > 3) g.put("memUsedMb", parseIntSafe(p[3]));
                    if (p.length > 4) g.put("memTotalMb", parseIntSafe(p[4]));
                    gpus.add(g);
                }
            } else {
                String[] p = splitCsv(s);
                if (p.length >= 1 && p[0].matches("\\d+")) {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("pid", p[0]);
                    if (p.length > 1) a.put("process", p[1].trim());
                    if (p.length > 2) a.put("memMb", p[2].trim());
                    apps.add(a);
                }
            }
        }
        out.put("gpus", gpus);
        out.put("apps", apps);
        out.put("maxUtil", maxUtil);
        out.put("busy", maxUtil >= 15 || !apps.isEmpty());
    }

    private static String[] splitCsv(String s) {
        return s.split("\\s*,\\s*");
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replace("%", "").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean isRunning(String status) {
        String s = status == null ? "" : status.toUpperCase(Locale.ROOT);
        return s.contains("RUN") || s.contains("运行") || s.contains("START") || s.contains("开机");
    }

    private static boolean looksFailed(JsonNode n) {
        if (n == null) return true;
        if (n.path("success").isBoolean() && !n.path("success").asBoolean()) return true;
        if (n.path("ok").isBoolean() && !n.path("ok").asBoolean()) return true;
        JsonNode codeNode = n.path("code");
        if (codeNode.isNumber()) {
            int code = codeNode.asInt();
            if (code != 0 && code != 200) return true;
        } else if (codeNode.isTextual()) {
            String code = codeNode.asText("");
            if (!code.isBlank() && !"0".equals(code) && !"200".equals(code)
                    && !"OK".equalsIgnoreCase(code) && !"SUCCESS".equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }

    private static String pickText(JsonNode n, String... keys) {
        if (n == null) return "";
        for (String k : keys) {
            JsonNode v = n.path(k);
            if (v.isMissingNode() || v.isNull()) continue;
            String s = v.asText("").trim();
            if (!s.isBlank() && !"null".equalsIgnoreCase(s)) return s;
        }
        Iterator<String> it = n.fieldNames();
        while (it.hasNext()) {
            String f = it.next();
            for (String k : keys) {
                if (f.equalsIgnoreCase(k)) {
                    String s = n.path(f).asText("").trim();
                    if (!s.isBlank()) return s;
                }
            }
        }
        return "";
    }

    private static String instanceIdFromHost(String hint) {
        if (hint == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("instance-[a-z0-9]+", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(hint);
        return m.find() ? m.group().toLowerCase(Locale.ROOT) : null;
    }

    private static String fallbackKnownId(String q) {
        String ql = q.toLowerCase(Locale.ROOT);
        if (ql.contains("glph6jf7") || ql.contains("smartml-primary")) return "instance-glph6jf7";
        if (ql.contains("7adcwgs5") || ql.contains("smartml-dual")) return "instance-7adcwgs5";
        return instanceIdFromHost(q);
    }

    private String token() {
        return apiToken == null ? "" : apiToken.trim();
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", msg);
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }
}
