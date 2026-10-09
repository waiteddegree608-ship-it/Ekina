package com.ekina.application.project;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ResearchPlanTest {
    @Test void unknownBudgetIsNotZeroAndDelegationDoesNotGrantPermission() {
        Map<String,Object> settings = ResearchPlan.collaboration(Map.of("mode","delegated","approved",true));
        assertNull(settings.get("budget"));
        assertFalse(settings.containsKey("approved"));
        assertThrows(IllegalArgumentException.class, () -> ResearchPlan.collaboration(Map.of("budget", -1)));
        assertThrows(IllegalArgumentException.class, () -> ResearchPlan.collaboration(Map.of("budget", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> ResearchPlan.collaboration(Map.of("mode", "autopay")));
    }

    @Test void estimateRequiresSourceAndOrderedRange() {
        assertThrows(IllegalArgumentException.class, () -> ResearchPlan.roadmap(List.of(Map.of("id","a","title","试跑","costMax",30))));
        assertThrows(IllegalArgumentException.class, () -> ResearchPlan.roadmap(List.of(Map.of("id","a","title","试跑","costMin",40,"costMax",30,"estimateBasis","平台报价"))));
        var route = ResearchPlan.roadmap(List.of(Map.of("id","a","title","试跑")));
        assertNull(route.getFirst().get("costMax"));
    }

    @Test void rejectsBrokenDependenciesAndCycles() {
        assertThrows(IllegalArgumentException.class, () -> ResearchPlan.roadmap(List.of(Map.of("id","a","title","实验","dependsOn",List.of("missing")))));
        assertThrows(IllegalArgumentException.class, () -> ResearchPlan.roadmap(List.of(
                Map.of("id","a","title","实验A","dependsOn",List.of("b")), Map.of("id","b","title","实验B","dependsOn",List.of("a")))));
        assertEquals(2, ResearchPlan.roadmap(List.of(Map.of("id","a","title","试跑"),Map.of("id","b","title","正式实验","status","conditional","dependsOn",List.of("a")))).size());
    }

    @Test void boardDecisionCannotApproveResources() {
        var decision = ResearchPlan.decisions(List.of(Map.of("id","gpu","title","租用算力","kind","authorization","approved",true,"status","approved"))).getFirst();
        assertFalse(decision.containsKey("approved"));
        assertFalse(decision.containsKey("status"));
    }
}
