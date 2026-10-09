package com.ekina.application.lab;

import com.ekina.application.home.HomeQuotaService;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Locale;
import java.util.Map;

/**
 * Multiple GPU / SSH boxes. Default host comes from {@code ekina.compute.ssh.*};
 * extras live in {@code AgentHome/state/compute-hosts.json} so new machines can be added
 * without a rebuild.
 */
@Service
public class ComputeHostRegistry {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Map<String, Object>> hosts = new ArrayList<>();

    @Autowired
    private HomeQuotaService homeQuotaService;

    @Value("${ekina.compute.ssh.host:}")
    private String computeHost;

    @Value("${ekina.compute.ssh.port:0}")
    private int computePort;

    @Value("${ekina.compute.ssh.user:root}")
    private String computeUser;

    @PostConstruct
    public void init() {
        reload();
    }

    public synchronized void reload() {
        hosts.clear();
        if (computeHost != null && !computeHost.isBlank()) {
            hosts.add(host("smartml-primary", computeHost.trim(), computePort > 0 ? computePort : 22,
                    blank(computeUser, "root"), "EKINA_SSH_PASSWORD", "configured default compute host"));
        }
        Path f = homeQuotaService.homePath().resolve("state").resolve("compute-hosts.json");
        if (Files.isRegularFile(f)) {
            try {
                List<Map<String, Object>> extra = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8),
                        new TypeReference<>() {});
                if (extra != null) {
                    for (Map<String, Object> h : extra) {
                        if (h == null || blank(str(h.get("host")), "").isBlank()) continue;
                        if (findByHost(str(h.get("host"))) != null) continue;
                        hosts.add(normalize(h));
                    }
                }
            } catch (Exception e) {
                System.err.println("[ComputeHostRegistry] " + e.getMessage());
            }
        }
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(hosts),
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public synchronized List<Map<String, Object>> list() {
        return new ArrayList<>(hosts);
    }

    public synchronized Map<String, Object> find(String hint) {
        if (hint == null || hint.isBlank()) {
            return hosts.isEmpty() ? null : hosts.get(0);
        }
        String q = hint.trim().toLowerCase(Locale.ROOT);
        for (Map<String, Object> h : hosts) {
            if (q.equalsIgnoreCase(str(h.get("id"))) || q.equalsIgnoreCase(str(h.get("host")))) {
                return h;
            }
        }
        for (Map<String, Object> h : hosts) {
            String host = str(h.get("host")).toLowerCase(Locale.ROOT);
            String id = str(h.get("id")).toLowerCase(Locale.ROOT);
            if (host.contains(q) || id.contains(q)) return h;
        }
        return null;
    }

    private Map<String, Object> findByHost(String host) {
        for (Map<String, Object> h : hosts) {
            if (host.equalsIgnoreCase(str(h.get("host")))) return h;
        }
        return null;
    }

    private static Map<String, Object> host(String id, String host, int port, String user, String passwordEnv, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("host", host);
        m.put("port", port);
        m.put("user", user);
        m.put("passwordEnv", passwordEnv);
        m.put("note", note);
        return m;
    }

    private static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        String host = str(raw.get("host"));
        m.put("id", blank(str(raw.get("id")), host.replace('.', '-')));
        m.put("host", host);
        int port = 22;
        Object p = raw.get("port");
        if (p instanceof Number n) port = n.intValue();
        else if (p != null && !str(p).isBlank()) {
            try { port = Integer.parseInt(str(p)); } catch (NumberFormatException ignored) {}
        }
        m.put("port", port);
        m.put("user", blank(str(raw.get("user")), "root"));
        m.put("passwordEnv", blank(str(raw.get("passwordEnv")), "EKINA_SSH_PASSWORD"));
        m.put("note", str(raw.get("note")));
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String blank(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
