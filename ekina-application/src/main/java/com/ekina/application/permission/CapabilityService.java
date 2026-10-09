package com.ekina.application.permission;

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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CapabilityService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<CapabilityGrant> grants = new ArrayList<>();

    @Autowired
    private HomeQuotaService homeQuotaService;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.ekina.application.lab.ComputeHostRegistry computeHostRegistry;

    /** Default compute host (SSH) that is trusted out of the box — auto-granted as ssh_remote. */
    @Value("${ekina.compute.ssh.host:}")
    private String computeHost;

    @Value("${ekina.compute.ssh.port:0}")
    private int computePort;

    @Value("${ekina.compute.ssh.user:}")
    private String computeUser;

    @Value("${ekina.reminders.default-user:user-1}")
    private String defaultUser;

    @PostConstruct
    public void init() {
        load();
        ensureDefaultSshGrant();
        ensureListedComputeHosts();
    }

    public synchronized List<CapabilityGrant> list(String userId) {
        purgeExpired();
        if (userId == null || userId.isBlank()) {
            return new ArrayList<>(grants);
        }
        return grants.stream().filter(g -> userId.equals(g.getUserId())).toList();
    }

    public synchronized Optional<CapabilityGrant> findActive(String userId, String kind) {
        purgeExpired();
        List<CapabilityGrant> matches = grants.stream()
                .filter(g -> "approved".equals(g.getStatus()))
                .filter(g -> userId == null || userId.equals(g.getUserId()))
                .filter(g -> kind != null && kind.equalsIgnoreCase(g.getKind()))
                .toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        // ssh_remote: only grants with host are usable. Empty-scope "approved" must not block re-request.
        if ("ssh_remote".equalsIgnoreCase(kind)) {
            List<CapabilityGrant> withHost = matches.stream().filter(this::hasSshHost).toList();
            if (withHost.isEmpty()) {
                return Optional.empty();
            }
            if (computeHost != null && !computeHost.isBlank()) {
                String want = computeHost.trim();
                Optional<CapabilityGrant> configured = withHost.stream()
                        .filter(g -> want.equalsIgnoreCase(String.valueOf(g.getScope().get("host"))))
                        .reduce((a, b) -> b);
                if (configured.isPresent()) {
                    return configured;
                }
            }
            return Optional.of(withHost.get(withHost.size() - 1));
        }
        // Prefer newest for other kinds
        return Optional.of(matches.get(matches.size() - 1));
    }

    private boolean hasSshHost(CapabilityGrant g) {
        if (g.getScope() == null) return false;
        Object host = g.getScope().get("host");
        return host != null && !String.valueOf(host).isBlank();
    }

    public synchronized CapabilityGrant grant(String userId, String kind, Map<String, Object> scope, Integer ttlHours, String note) {
        String uid = userId == null ? "user-1" : userId;
        // When granting a usable ssh scope, retire older empty/stale ssh grants so findActive hits the right one
        if ("ssh_remote".equalsIgnoreCase(kind) && scope != null && scope.get("host") != null
                && !String.valueOf(scope.get("host")).isBlank()) {
            for (CapabilityGrant old : grants) {
                if (uid.equals(old.getUserId()) && "ssh_remote".equalsIgnoreCase(old.getKind())
                        && "approved".equals(old.getStatus()) && !hasSshHost(old)) {
                    old.setStatus("revoked");
                    old.setNote((old.getNote() == null ? "" : old.getNote() + " | ") + "superseded by scoped grant");
                }
            }
        }
        CapabilityGrant g = new CapabilityGrant();
        g.setId("cap_" + UUID.randomUUID().toString().substring(0, 8));
        g.setUserId(uid);
        g.setKind(kind);
        g.setStatus("approved");
        g.setGrantedAt(Instant.now().toString());
        int hours = ttlHours == null ? 24 : Math.max(1, ttlHours);
        g.setExpiresAt(Instant.now().plus(hours, ChronoUnit.HOURS).toString());
        g.setScope(scope);
        g.setNote(note);
        grants.add(g);
        save();
        return g;
    }

    /**
     * If a compute SSH host is configured ({@code ekina.compute.ssh.*}) but no usable scoped grant
     * exists for it, auto-provision one so the pipeline uses SSH as the compute backend out of the
     * box. Password is NOT stored in the scope — it is resolved from {@code EKINA_SSH_PASSWORD}.
     */
    private synchronized void ensureDefaultSshGrant() {
        if (computeHost == null || computeHost.isBlank()) return;
        String host = computeHost.trim();
        String uid = defaultUser == null || defaultUser.isBlank() ? "user-1" : defaultUser;
        boolean haveCurrent = false;
        for (CapabilityGrant g : grants) {
            if (uid.equals(g.getUserId()) && "approved".equals(g.getStatus())
                    && "ssh_remote".equalsIgnoreCase(g.getKind()) && hasSshHost(g)
                    && host.equalsIgnoreCase(String.valueOf(g.getScope().get("host")))) {
                haveCurrent = true;
                break;
            }
        }
        if (haveCurrent) return;
        Map<String, Object> scope = new java.util.LinkedHashMap<>();
        scope.put("host", host);
        if (computeUser != null && !computeUser.isBlank()) scope.put("user", computeUser.trim());
        if (computePort > 0) scope.put("port", computePort);
        scope.put("workdir", "/root");
        scope.put("usePassword", true);
        scope.put("auth", "password");
        scope.put("passwordEnv", "EKINA_SSH_PASSWORD");
        // Compute box should not silently expire after a day.
        grant(uid, "ssh_remote", scope, 24 * 365, "configured compute host (auto)");
        System.out.println("[Capability] auto-granted ssh_remote for configured compute host " + host);
    }

    /** Grant every host listed in ComputeHostRegistry without revoking the others. */
    private synchronized void ensureListedComputeHosts() {
        if (computeHostRegistry == null) return;
        String uid = defaultUser == null || defaultUser.isBlank() ? "user-1" : defaultUser;
        for (java.util.Map<String, Object> h : computeHostRegistry.list()) {
            String host = h.get("host") == null ? "" : String.valueOf(h.get("host")).trim();
            if (host.isBlank()) continue;
            boolean have = false;
            for (CapabilityGrant g : grants) {
                if (uid.equals(g.getUserId()) && "approved".equals(g.getStatus())
                        && "ssh_remote".equalsIgnoreCase(g.getKind()) && hasSshHost(g)
                        && host.equalsIgnoreCase(String.valueOf(g.getScope().get("host")))) {
                    have = true;
                    break;
                }
            }
            if (have) continue;
            java.util.Map<String, Object> scope = new java.util.LinkedHashMap<>();
            scope.put("host", host);
            scope.put("user", h.getOrDefault("user", "root"));
            scope.put("port", h.getOrDefault("port", 22));
            scope.put("id", h.get("id"));
            scope.put("workdir", "/root");
            scope.put("usePassword", true);
            scope.put("auth", "password");
            scope.put("passwordEnv", h.getOrDefault("passwordEnv", "EKINA_SSH_PASSWORD"));
            grant(uid, "ssh_remote", scope, 24 * 365, "compute host " + h.get("id"));
        }
    }

    public synchronized Optional<CapabilityGrant> findActiveSsh(String userId, String hostHint) {
        purgeExpired();
        String uid = userId == null ? defaultUser : userId;
        if (hostHint != null && !hostHint.isBlank() && computeHostRegistry != null) {
            java.util.Map<String, Object> wanted = computeHostRegistry.find(hostHint);
            if (wanted != null) {
                String host = String.valueOf(wanted.get("host"));
                Optional<CapabilityGrant> hit = grants.stream()
                        .filter(g -> "approved".equals(g.getStatus()))
                        .filter(g -> uid.equals(g.getUserId()))
                        .filter(g -> "ssh_remote".equalsIgnoreCase(g.getKind()))
                        .filter(this::hasSshHost)
                        .filter(g -> host.equalsIgnoreCase(String.valueOf(g.getScope().get("host"))))
                        .reduce((a, b) -> b);
                if (hit.isPresent()) return hit;
            }
            Optional<CapabilityGrant> loose = grants.stream()
                    .filter(g -> "approved".equals(g.getStatus()))
                    .filter(g -> uid.equals(g.getUserId()))
                    .filter(g -> "ssh_remote".equalsIgnoreCase(g.getKind()))
                    .filter(this::hasSshHost)
                    .filter(g -> String.valueOf(g.getScope().get("host")).toLowerCase()
                            .contains(hostHint.trim().toLowerCase())
                            || String.valueOf(g.getScope().getOrDefault("id", "")).equalsIgnoreCase(hostHint.trim()))
                    .reduce((a, b) -> b);
            if (loose.isPresent()) return loose;
        }
        return findActive(uid, "ssh_remote");
    }

    public synchronized boolean revoke(String id) {
        boolean ok = false;
        for (CapabilityGrant g : grants) {
            if (g.getId().equals(id)) {
                g.setStatus("revoked");
                ok = true;
            }
        }
        if (ok) save();
        return ok;
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        for (CapabilityGrant g : grants) {
            if ("approved".equals(g.getStatus()) && g.getExpiresAt() != null) {
                try {
                    if (Instant.parse(g.getExpiresAt()).isBefore(now)) {
                        g.setStatus("expired");
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Path file() {
        return homeQuotaService.homePath().resolve("state").resolve("capabilities.json");
    }

    private synchronized void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try {
            List<CapabilityGrant> loaded = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8),
                    new TypeReference<>() {});
            grants.clear();
            if (loaded != null) grants.addAll(loaded);
        } catch (Exception e) {
            System.err.println("[Capability] load failed: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(grants), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Capability] save failed: " + e.getMessage());
        }
    }
}
