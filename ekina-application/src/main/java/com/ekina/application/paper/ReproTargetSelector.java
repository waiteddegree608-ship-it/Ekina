package com.ekina.application.paper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Picks a reproducible seed paper: prefers top venues + available code repo.
 */
@Service
public class ReproTargetSelector {

    private static final Set<String> TOP = Set.of(
            "neurips", "nips", "icml", "iclr", "cvpr", "iccv", "eccv", "acl", "emnlp", "naacl",
            "aaai", "ijcai", "kdd", "www", "sigir", "sigmod", "vldb", "osdi", "sosp", "nsdi",
            "ccs", "oakland", "usenix security", "ieee s&p");

    @Autowired
    private CodeRepoFinder codeRepoFinder;

    public Map<String, Object> select(List<LitPaper> catalog, String venueTierHint) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> scored = new ArrayList<>();

        for (LitPaper p : catalog) {
            if (p == null || !p.hasStableId()) continue;
            Map<String, Object> code = codeRepoFinder.findFor(p);
            int score = 0;
            boolean hasCode = Boolean.TRUE.equals(code.get("ok"));
            boolean weak = Boolean.TRUE.equals(code.get("weakPrimary"));
            if (hasCode) score += weak ? 20 : 50;
            // Prefer trainable primary from ranked list
            if (code.get("ranked") instanceof List<?> ranked && !ranked.isEmpty()) {
                Object first = ranked.get(0);
                if (first instanceof Map<?, ?> rm) {
                    score += Math.min(40, Math.max(0, toInt(rm.get("totalScore")) / 2));
                    Object meta = rm.get("meta");
                    if (meta instanceof Map<?, ?> mm && Boolean.TRUE.equals(mm.get("likelyTrainable"))) {
                        score += 35;
                    }
                }
            }
            score += venueScore(p.getVenue());
            if (p.getCitationCount() != null) score += Math.min(30, p.getCitationCount() / 20);
            if (p.getArxivId() != null) score += 5;
            if (p.getAbstractText() != null && p.getAbstractText().length() > 200) score += 5;

            // soft prefer tier hint keywords
            if (venueTierHint != null && p.getVenue() != null) {
                String v = p.getVenue().toLowerCase(Locale.ROOT);
                if (venueTierHint.toUpperCase(Locale.ROOT).contains("A") && venueScore(p.getVenue()) >= 20) {
                    score += 10;
                }
                if (v.contains("workshop")) score -= 8;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("paperId", p.getId());
            row.put("title", p.getTitle());
            row.put("venue", p.getVenue());
            row.put("year", p.getYear());
            row.put("arxivId", p.getArxivId());
            row.put("doi", p.getDoi());
            row.put("score", score);
            row.put("hasCode", hasCode);
            row.put("weakCode", weak);
            row.put("code", code);
            scored.add(row);

            if (hasCode && p.getCodeUrl() == null && code.get("primary") != null) {
                p.setCodeUrl(String.valueOf(code.get("primary")));
            }
        }

        scored.sort(Comparator.comparingInt((Map<String, Object> m) -> toInt(m.get("score"))).reversed());
        out.put("candidates", scored.size() > 8 ? scored.subList(0, 8) : scored);

        Map<String, Object> bestWithCode = scored.stream()
                .filter(m -> Boolean.TRUE.equals(m.get("hasCode")) && !Boolean.TRUE.equals(m.get("weakCode")))
                .findFirst().orElse(null);
        if (bestWithCode == null) {
            bestWithCode = scored.stream()
                    .filter(m -> Boolean.TRUE.equals(m.get("hasCode")))
                    .findFirst().orElse(null);
        }
        Map<String, Object> bestAny = scored.isEmpty() ? null : scored.get(0);

        Map<String, Object> chosen = bestWithCode != null ? bestWithCode : bestAny;
        out.put("chosen", chosen);
        out.put("hasReproTarget", bestWithCode != null);
        out.put("note", bestWithCode != null
                ? "Selected open-code seed paper for REPRO"
                : "No code repo found via PapersWithCode/arXiv; REPRO will be inventory-only / blocked in reproduce mode");
        return out;
    }

    private static int venueScore(String venue) {
        if (venue == null || venue.isBlank()) return 0;
        String v = venue.toLowerCase(Locale.ROOT);
        for (String t : TOP) {
            if (v.contains(t)) return 25;
        }
        if (v.contains("arxiv")) return 3;
        return 8;
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
}
