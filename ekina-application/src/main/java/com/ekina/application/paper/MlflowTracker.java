package com.ekina.application.paper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Minimal MLflow tracking client (APACHE-2.0, self-hosted) step-1 "experiment tracking".
 *
 * <p>When the pipeline produces a real run, this logs the run + params + metrics to a local MLflow
 * tracking server via its REST API ({@code /api/2.0/mlflow/runs/...}). It is entirely
 * best-effort: if {@code ekina.paper.mlflow.tracking-url} is empty or the server is unreachable,
 * it no-ops so a missing tracking server never breaks the paper pipeline.
 */
@Component
public class MlflowTracker {

    @Value("${ekina.paper.mlflow.tracking-url:}")
    private String trackingUrl;

    @Value("${ekina.paper.mlflow.experiment:ekina-paper}")
    private String experiment;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Best-effort: create a run in the experiment, log params + one metric, return runId or null. */
    public String logRun(String paperId, String runId, String dataset,
                         Map<String, Object> params, Double metricVal, String metricName) {
        if (trackingUrl == null || trackingUrl.isBlank()) return null;
        try {
            String base = trackingUrl.replaceAll("/+$", "");
            String experimentId = ensureExperiment(base);
            String runName = paperId + ":" + runId;
            String created = post(base + "/api/2.0/mlflow/runs/create",
                    Map.of("experiment_id", experimentId == null ? "0" : experimentId,
                            "run_name", runName,
                            "start_time", Instant.now().toEpochMilli()));
            String rid = extractRunId(created);
            if (rid == null) return null;
            // params
            for (Map.Entry<String, Object> e : params.entrySet()) {
                post(base + "/api/2.0/mlflow/runs/log-parameter",
                        Map.of("run_id", rid, "key", e.getKey(),
                                "value", e.getValue() == null ? "" : String.valueOf(e.getValue())));
            }
            if (metricVal != null) {
                post(base + "/api/2.0/mlflow/runs/log-metric",
                        Map.of("run_id", rid, "key", metricName,
                                "value", metricVal, "timestamp", Instant.now().toEpochMilli(), "step", 0));
            }
            return rid;
        } catch (Exception e) {
            System.err.println("[Mlflow] logRun skipped: " + e.getMessage());
            return null;
        }
    }

    private String ensureExperiment(String base) {
        try {
            String id = post(base + "/api/2.0/mlflow/experiments/create", Map.of("name", experiment));
            return extractExperimentId(id);
        } catch (Exception e) {
            return null; // may already exist
        }
    }

    private String post(String url, Map<String, Object> body) throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            return resp.body(); // Best-effort: tolerate errors (e.g. experiment exists / server down)
        }
        return resp.body();
    }

    private static String extractRunId(String json) {
        try {
            var n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json == null ? "" : json);
            return n.path("run").path("info").path("run_id").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractExperimentId(String json) {
        try {
            var n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json == null ? "" : json);
            return n.path("experiment_id").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
