package com.ekina.application.project;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResearchLedgerTest {
    static Map<String, Object> evidence(String id) {
        return Map.of("id", id, "type", "evidence", "title", "小样本结果", "kind", "experiment",
                "source", "runs/pilot/metrics.json", "summary", "小样本上有提升", "conditions", "仅限固定数据划分和种子", "producer", "runner");
    }
    static Map<String, Object> review(String id, String target, String verdict) {
        return Map.of("id", id, "type", "review", "evidenceId", target, "reviewer", "reviewer-b",
                "summary", "核对协议与数据", "artifact", "reviews/check.md", "method", "model_review", "verdict", verdict);
    }
    static Map<String, Object> conclusion(String id, List<String> evidenceIds, List<String> dependsOn) {
        return Map.of("id", id, "type", "conclusion", "title", "局部发现", "statement", "值得扩大验证",
                "conditions", "当前小样本", "evidenceIds", evidenceIds, "dependsOn", dependsOn);
    }
    static Map<String, Object> checkpoint(String id, List<String> basis) {
        return Map.of("id", id, "type", "checkpoint", "question", "收益能否复现", "nextAction", "新增随机种子",
                "continueWhen", "同协议稳定提升", "stopWhen", "检查发现数据泄漏", "resources", "本地 CPU，费用待估", "basisIds", basis);
    }
    static Map<String, Object> withdrawal(String id, String target) {
        return Map.of("id", id, "type", "withdrawal", "targetId", target, "reason", "发现数据划分错误");
    }
    static List<Map<String, Object>> append(List<Map<String, Object>> current, Map<String, Object>... entries) {
        return ResearchLedger.append(current, Map.of("expectedRevision", current.size(), "records", List.of(entries)));
    }
    static Map<?, ?> record(List<Map<String, Object>> raw, String id) {
        return ((List<?>) ResearchLedger.view(raw).get("records")).stream().map(v -> (Map<?, ?>) v)
                .filter(v -> id.equals(v.get("id"))).findFirst().orElseThrow();
    }
    @Test void reviewsDoNotBecomeTruthAndLateObjectionsPropagate() {
        var raw = append(List.of(), evidence("e1"), conclusion("c1", List.of("e1"), List.of()));
        assertEquals("unreviewed", record(raw,"c1").get("assessment"));
        raw = append(raw, review("r1","e1","supports"));
        assertEquals("reviewed", record(raw,"c1").get("assessment"));
        assertEquals("model_review", record(raw,"r1").get("method"));
        raw = append(raw, evidence("e2"), review("r2","e2","supports"), conclusion("c2",List.of("e2"),List.of("c1")));
        raw = append(raw, review("objection","e1","concerns"), review("more-support","e1","supports"));
        assertEquals("contested", record(raw,"c1").get("assessment"));
        assertEquals("contested", record(raw,"c2").get("assessment"));
    }
    @Test void withdrawalInvalidatesDownstreamConclusionsAndNextRoundWithoutDeletingHistory() {
        var raw = append(List.of(), evidence("e1"), review("r1","e1","supports"), conclusion("c1",List.of("e1"),List.of()),
                evidence("e2"), review("r2","e2","supports"), conclusion("c2",List.of("e2"),List.of("c1")), checkpoint("next",List.of("c2","r1")));
        raw = append(raw, withdrawal("w1","e1"));
        assertEquals(8, raw.size());
        assertEquals("withdrawn", record(raw,"e1").get("assessment"));
        assertEquals("needs_review", record(raw,"c1").get("assessment"));
        assertEquals("needs_review", record(raw,"c2").get("assessment"));
        assertEquals("needs_review", ((Map<?,?>)ResearchLedger.view(raw).get("checkpoint")).get("assessment"));
        assertFalse(raw.getFirst().containsKey("assessment"));
        raw = append(raw, review("r3","e1","supports"));
        assertEquals("withdrawn", record(raw,"e1").get("assessment"));
    }
    @Test void withdrawingLastReviewReturnsToUnreviewedAndWithdrawingLatestPlanDoesNotReviveOldPlan() {
        var raw = append(List.of(), evidence("e"), review("r","e","supports"), conclusion("c",List.of("e"),List.of()),
                checkpoint("old",List.of()), checkpoint("new",List.of("c")));
        raw = append(raw, withdrawal("w","r"), withdrawal("w2","new"));
        assertEquals("unreviewed", record(raw,"c").get("assessment"));
        Map<?,?> latest = (Map<?,?>)ResearchLedger.view(raw).get("checkpoint");
        assertEquals("new",latest.get("id")); assertEquals("withdrawn",latest.get("assessment"));
    }
    @Test void identicalRetriesSurviveRevisionChangesButStaleNewWritesAndRewritesFail() {
        var request = Map.of("expectedRevision",0,"records",List.of(evidence("e1")));
        var raw = ResearchLedger.append(List.of(),request);
        raw = append(raw,evidence("e2"));
        assertEquals(raw,ResearchLedger.append(raw,request));
        var changed = new LinkedHashMap<>(evidence("e1")); changed.put("summary","changed");
        var finalRaw = raw;
        assertThrows(IllegalArgumentException.class,()->append(finalRaw,changed));
        assertThrows(ResearchLedger.Conflict.class,()->ResearchLedger.append(finalRaw,Map.of("expectedRevision",0,"records",List.of(evidence("e3")))));
    }
    @Test void noDanglingCrossProjectOrForwardReferencesAndNoSilentBatchLoss() {
        assertThrows(IllegalArgumentException.class,()->append(List.of(),conclusion("c",List.of("unknown"),List.of())));
        assertThrows(IllegalArgumentException.class,()->append(List.of(),evidence("e"),conclusion("c",List.of("e"),List.of("c"))));
        assertThrows(IllegalArgumentException.class,()->append(List.of(),evidence("e"),evidence("e")));
        assertThrows(IllegalArgumentException.class,()->append(List.of(),evidence("e"),review("r","e","ok")));
    }
    @Test void callerCannotForgeDerivedAssessmentOrTimestampsAndUnknownCostStaysUnknown() {
        var forged = new LinkedHashMap<>(evidence("e")); forged.put("assessment","reviewed"); forged.put("at","1900-01-01");
        var raw = append(List.of(),forged,checkpoint("next",List.of("e")));
        assertEquals("unreviewed",record(raw,"e").get("assessment"));
        assertNotEquals("1900-01-01",record(raw,"e").get("at"));
        assertNull(record(raw,"next").get("costMax"));
        var priced = new LinkedHashMap<>(checkpoint("priced",List.of())); priced.put("costMax",120);
        assertThrows(IllegalArgumentException.class,()->append(raw,priced));
    }
    @Test void failedAttemptCanStillContributeAReviewedFragment() {
        var attempt = Map.<String,Object>of("id","try1","type","attempt","question","能否提升精度","method","本地试跑","result","训练失败，但完成数据校验","outcome","failed");
        var fragment = new LinkedHashMap<>(evidence("fragment")); fragment.put("attemptId","try1");
        var raw = append(List.of(),attempt,fragment,review("r","fragment","supports"));
        assertEquals("failed",record(raw,"try1").get("outcome"));
        assertEquals("reviewed",record(raw,"fragment").get("assessment"));
    }
}
