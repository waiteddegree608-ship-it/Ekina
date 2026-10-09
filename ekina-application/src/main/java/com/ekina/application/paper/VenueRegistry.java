package com.ekina.application.paper;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Curated journal registry for step 1 ("select a suitable venue").
 *
 * <p>Maps a user's target tier (CCF-C / 中科院3区 / 中科院4区) to real journals that plausibly fit
 * a machine-learning / diffusion-model paper, each with the LaTeX document class used for
 * submission formatting (so step 8 can render the target journal's template).
 *
 * <p>Static curated data + scope-keyword matching; never invents a journal.
 */
@Component
public class VenueRegistry {

    /** id, name, tierLabel, scopeKeywords, latexClass, kitId. */
    private static final String[][] JOURNALS = {
            {"neurips", "NeurIPS", "CCF-A · 顶会", "learning,diffusion,generative,deep,ai,neural,representation", "article", "neurips"},
            {"icml", "ICML", "CCF-A · 顶会", "learning,diffusion,generative,deep,ai,neural", "article", "icml"},
            {"iclr", "ICLR", "CCF-A · 顶会", "learning,representation,generative,deep,ai,neural", "article", "iclr"},
            {"aaai", "AAAI", "CCF-A · 顶会", "learning,ai,vision,planning,agent", "article", "aaai"},
            {"acl", "ACL", "CCF-A · 顶会", "language,nlp,text,table,in-context,tabicl", "article", "acl"},
            {"neurocomputing", "Neurocomputing", "CCF-C · SCI 2区", "neural,network,learning,vision,image,classification,diffusion,generative,deep,ai", "elsarticle", "elsevier"},
            {"kbs", "Knowledge-Based Systems", "CCF-C · SCI 2区", "knowledge,learning,vision,image,classification,diffusion,generative,deep,ai,expert", "elsarticle", "elsevier"},
            {"expert-systems-appl", "Expert Systems with Applications", "CCF-C · SCI 2区", "expert,learning,vision,image,classification,diffusion,generative,deep,ai,decision", "elsarticle", "elsevier"},
            {"pattern-recog-lett", "Pattern Recognition Letters", "CCF-C · SCI 2-3区", "pattern,recognition,vision,image,classification,diffusion,generative,segmentation", "elsarticle", "elsevier"},
            {"applied-intell", "Applied Intelligence", "CCF-C · SCI 2-3区", "intelligence,learning,vision,image,classification,diffusion,generative,deep,ai,neural", "sn-jnl", "springer-sn"},
            {"multimed-tools-appl", "Multimedia Tools and Applications", "中科院4区 · SCI", "multimedia,image,video,vision,learning,diffusion,generative,deep,ai", "sn-jnl", "springer-sn"},
            {"ieee-access", "IEEE Access", "中科院3区 · SCI", "access,learning,vision,image,classification,diffusion,generative,deep,ai,neural", "IEEEtran", "ieee-access"},
            {"ieee-conf", "IEEE Conference (IEEEtran)", "IEEE 会议模板", "conference,ieee,signal,image", "IEEEtran", "ieee-conf"},
    };

    public List<Map<String, Object>> candidates(String domain, String venueTier) {
        List<Map<String, Object>> out = new ArrayList<>();
        String d = (domain == null ? "" : domain).toLowerCase(Locale.ROOT);
        String tier = (venueTier == null ? "" : venueTier).toLowerCase(Locale.ROOT);
        boolean wantCcf = tier.contains("ccf");
        boolean wantZone = tier.contains("区");
        for (String[] j : JOURNALS) {
            boolean tierOk;
            if (tier.isBlank()) {
                tierOk = true;
            } else if (wantCcf && j[2].toLowerCase(Locale.ROOT).contains("ccf")) {
                if (tier.contains("ccf-a") || tier.contains("ccfa")) {
                    tierOk = j[2].toLowerCase(Locale.ROOT).contains("ccf-a");
                } else if (tier.contains("ccf-b")) {
                    tierOk = j[2].toLowerCase(Locale.ROOT).contains("ccf-b");
                } else {
                    tierOk = j[2].toLowerCase(Locale.ROOT).contains("ccf-c");
                }
            } else if (wantZone) {
                String z = j[2].toLowerCase(Locale.ROOT);
                tierOk = z.contains(tier); // e.g. "中科院3区" in venueTier matches journal z
            } else {
                // generic CCF / unspecified → keep CCF-C + zones
                tierOk = j[2].toLowerCase(Locale.ROOT).contains("ccf-c") || j[2].contains("区");
            }
            if (!tierOk) continue;
            boolean keywordHit = keywordHit(d, j[3]);
            // Prefer journals whose scope overlaps the domain; keep all tier-matched as fallback.
            if (out.size() < 4 && (keywordHit || out.isEmpty())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", j[0]);
                m.put("name", j[1]);
                m.put("tier", j[2]);
                m.put("latexClass", j[4]);
                m.put("kitId", j.length > 5 ? j[5] : j[4]);
                m.put("match", keywordHit ? "keyword" : "tier");
                out.add(m);
            }
        }
        return out;
    }

    private static boolean keywordHit(String domain, String keywords) {
        if (domain == null || domain.isBlank()) return false;
        for (String k : keywords.split(",")) {
            String kw = k.trim().toLowerCase(Locale.ROOT);
            if (!kw.isEmpty() && domain.contains(kw)) return true;
        }
        return false;
    }
}
