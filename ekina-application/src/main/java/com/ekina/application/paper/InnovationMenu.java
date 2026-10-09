package com.ekina.application.paper;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Incremental ("灌水") innovation menu: dataset swap / module stitch / workload stacks.
 * These are executable experiment variants — not fabricated claims.
 */
@Component
public class InnovationMenu {

    public List<Map<String, Object>> catalog() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("dataset_cifar_to_tiny", "dataset",
                "Swap primary dataset to a secondary public set (e.g. CIFAR-10 ↔ Tiny-ImageNet subset)",
                Map.of("dataset", "tiny-imagenet-subset", "epochs_factor", 0.5)));
        items.add(item("dataset_aug_strong", "dataset",
                "Keep model; strengthen augmentation (flip/color jitter/random erase)",
                Map.of("aug", "strong", "random_erase", true)));
        items.add(item("module_se_block", "module",
                "Stitch a Squeeze-Excitation block into the backbone",
                Map.of("module", "se", "reduction", 16)));
        items.add(item("module_lora_rank", "module",
                "Change LoRA rank / alpha (classic incremental ablations)",
                Map.of("lora_rank", 16, "lora_alpha", 32)));
        items.add(item("loss_focal", "loss",
                "Replace CE with focal loss for long-tail / hard examples",
                Map.of("loss", "focal", "gamma", 2.0)));
        items.add(item("opt_cosine_wd", "opt",
                "Cosine LR + weight decay sweep (workload stacking)",
                Map.of("scheduler", "cosine", "weight_decay", 0.05)));
        items.add(item("ablation_seeds", "workload",
                "Multi-seed repeats for mean±std tables",
                Map.of("seeds", List.of(0, 1, 2), "metric", "accuracy")));
        items.add(item("ablation_components", "workload",
                "Leave-one-module-out ablation table",
                Map.of("ablate", List.of("se", "aug", "focal"))));
        return items;
    }

    /**
     * Pick 2–3 complementary variants for a CCF-C style incremental paper.
     */
    public Map<String, Object> planFor(String domain, String titleHint) {
        List<Map<String, Object>> all = catalog();
        String d = (domain == null ? "" : domain).toLowerCase(Locale.ROOT);
        List<Map<String, Object>> chosen = new ArrayList<>();

        // Prefer LoRA-related if domain mentions it
        if (d.contains("lora")) {
            pickById(all, chosen, "module_lora_rank");
        }
        pickById(all, chosen, "dataset_aug_strong");
        pickById(all, chosen, "opt_cosine_wd");
        if (chosen.size() < 3) {
            pickById(all, chosen, "module_se_block");
        }
        // Prefer single-seed by default for faster A100 loops; workload stacking optional
        // (ablation_seeds can be added manually via innov.json later)
        // fill randomly if still short
        while (chosen.size() < 3 && chosen.size() < all.size()) {
            Map<String, Object> x = all.get(ThreadLocalRandom.current().nextInt(all.size()));
            if (chosen.stream().noneMatch(c -> c.get("id").equals(x.get("id")))) {
                chosen.add(x);
            }
        }

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("domain", domain);
        plan.put("titleHint", titleHint);
        plan.put("style", "incremental"); // 换数据/缝模块/堆工作量
        plan.put("variants", chosen);
        plan.put("hypothesis",
                "Combining lightweight module/dataset/aug tweaks and multi-seed reporting "
                        + "yields a reproducible incremental improvement study suitable for CCF-C venues.");
        return plan;
    }

    private static void pickById(List<Map<String, Object>> all, List<Map<String, Object>> out, String id) {
        for (Map<String, Object> m : all) {
            if (id.equals(m.get("id")) && out.stream().noneMatch(c -> id.equals(c.get("id")))) {
                out.add(m);
                return;
            }
        }
    }

    private static Map<String, Object> item(String id, String kind, String summary, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("summary", summary);
        m.put("params", params);
        return m;
    }
}
