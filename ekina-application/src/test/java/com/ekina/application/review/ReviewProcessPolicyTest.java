package com.ekina.application.review;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ReviewProcessPolicyTest {
    Map<String,Object> audit(){return new LinkedHashMap<>(Map.of("blockingIssues",List.of("overlap"),"repairItems",List.of(Map.of("issue","overlap","stage","figures","target","F2")),"researchVerdict","pass","researchBlockingIssues",List.of(),"checkedSources",List.of("a","b","c"),"sourceChecks",List.of(check("a"),check("b"),check("c")),"verifiedFindings",List.of(Map.of("claim","verified identity","sourceIds",List.of("a"),"locator","official profile"))));}
    Map<String,Object> check(String id){return Map.of("id",id,"url","https://example.org/"+id,"locator","section","assessment","checked");}
    List<Map<String,Object>> sources(){return List.of(check("a"),check("b"),check("c"));}
    @Test void visualRevisionKeepsResearchReusableWithoutPassingFinalDelivery(){var a=audit();a.put("verdict","revise");assertEquals("figures",ReviewProcessPolicy.repairStage(a));assertTrue(ReviewProcessPolicy.reusable(a,sources()));assertFalse(ReviewCampaignService.auditPasses(a,Set.of("a","b","c"),4));}
    @Test void missingClassificationAndScientificErrorsNeverUnlockResearch(){var a=audit();a.remove("repairItems");assertFalse(ReviewProcessPolicy.reusable(a,sources()));assertEquals("draft",ReviewProcessPolicy.repairStage(a));a=audit();a.put("researchBlockingIssues",List.of("wrong identity"));assertFalse(ReviewProcessPolicy.reusable(a,sources()));a=audit();a.put("repairItems",List.of(Map.of("issue","overlap","stage","research","target","F2")));assertFalse(ReviewProcessPolicy.reusable(a,sources()));}
    @Test void findingsMustUseActuallyCheckedSources(){var a=audit();a.put("verifiedFindings",List.of(Map.of("claim","claim","sourceIds",List.of("unread"),"locator","page")));assertFalse(ReviewProcessPolicy.reusable(a,sources()));a=audit();a.put("sourceChecks",List.of(check("a"),check("b"),Map.of("id","c","url","wrong","locator","page","assessment","checked")));assertFalse(ReviewProcessPolicy.reusable(a,sources()));}
    @Test void repeatTargetChangesInstructionsBasedOnHistoryRatherThanAttemptParity(){var a=audit();var first=ReviewProcessPolicy.advise(a,List.of());var next=ReviewProcessPolicy.advise(a,List.of(Map.of("processAdvice",first)));assertEquals("repair-only-open-issues",first.get("strategy"));assertEquals("change-method-at-failing-target",next.get("strategy"));assertEquals(2L,((Map<?,?>)next.get("occurrences")).get("figures:F2"));}
}
