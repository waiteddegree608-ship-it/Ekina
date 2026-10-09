package com.ekina.application.review;

import com.ekina.application.home.HomeQuotaService;
import com.ekina.application.multiagent.*;
import com.ekina.application.project.ProjectBoardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewCampaignServiceTest {
 @Test void restartVoidsOldAcceptanceAndIsIdempotentWithoutCopyingOldDraft()throws Exception {
  var w=mock(MultiAgentOrchestrator.class);var t=topic("a");t.putAll(Map.of("status","draft_ready","researchReady",true,"draftTaskId","old"));var s=service(List.of(t),w);
  var fresh=s.restartFromFullText("u1","c1","explicit-owner-restart");assertTrue(Boolean.TRUE.equals(fresh.get("strictFullText")));assertTrue(((List<?>)fresh.get("topics")).isEmpty());
  var old=s.list("u1").stream().filter(c->"c1".equals(c.get("id"))).findFirst().orElseThrow();assertEquals("voided",old.get("status"));assertEquals("voided",((Map<?,?>)((List<?>)old.get("topics")).getFirst()).get("status"));
  assertTrue(Files.exists(home.resolve("state/review-voided/c1.json")));assertEquals(fresh.get("id"),s.restartFromFullText("u1","c1","same").get("id"));verifyNoInteractions(w);
 }
 @Test void fulltextGatePreventsAuthorDispatchAndRestartCannotInterruptPaidReader()throws Exception {
  var w=mock(MultiAgentOrchestrator.class);var t=topic("a");var s=service(List.of(t),w);var c=((List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns")).getFirst();c.put("strictFullText",true);
  ReflectionTestUtils.invokeMethod(s,"dispatch",c,t,false);verifyNoInteractions(w);
  var live=((List<Map<String,Object>>)c.get("topics")).getFirst();live.put("fullText",Map.of("enabled",true,"phase","reading","taskId","paid"));assertThrows(IllegalArgumentException.class,()->s.restartFromFullText("u1","c1","restart"));
 }

    @Test void supplementaryWorkerOwnsCampaignSeatAndPreventsConcurrentManuscriptRecovery() throws Exception {
        var workers=mock(MultiAgentOrchestrator.class);var a=topic("a");a.putAll(Map.of("status","draft_ready","paperFigures",new LinkedHashMap<>(Map.of("enabled",true,"phase","selecting","taskId","select"))));
        var b=topic("b");b.putAll(Map.of("status","blocked","retryCount",0,"retryLimit",2));
        var service=service(List.of(a,b),workers);service.tick();
        verify(workers,never()).dispatch(any(),any(),any(),any(),any());
        assertThrows(IllegalArgumentException.class,()->service.retry("u1","c1","b","draft"));
    }
    @Test void deniedModelSwitchesPendingRecoveryImmediatelyAndPersistsCooldown() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var a=topic("a");a.putAll(Map.of("status","blocked","taskId","denied","error","provider error"));
        var f=worker("denied","failed");f.getMeta().put("model","opencode-go/gpt-6-luna");when(w.get("denied")).thenReturn(Optional.of(f));
        var s=service(List.of(a),w);ReflectionTestUtils.setField(s,"workspaceDir",home.toString());
        Path events=home.resolve("sandbox/opencode-jobs/denied/events.jsonl");Files.createDirectories(events.getParent());
        Files.writeString(events,"{\"type\":\"error\",\"error\":{\"data\":{\"statusCode\":403,\"message\":\"Your organization does not have access to this model.\"}}}");
        var c=((List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns")).getFirst();var t=((List<Map<String,Object>>)c.get("topics")).getFirst();
        t.put("completionRecovery",new LinkedHashMap<>(Map.of("taskId","denied","stage","draft","status","waiting","model","opencode-go/gpt-6-luna","diagnosis","403","nextAttemptAt","2099-01-01T00:00:00Z")));
        ReflectionTestUtils.invokeMethod(s,"observeModelAccessFailures");
        ReflectionTestUtils.invokeMethod(s,"continueCompletion",c);
        assertEquals("pending",t.get("status"));assertEquals("opencode-go/glm-5.3-flash",((Map<?,?>)t.get("completionRecovery")).get("model"));
        assertTrue(Files.readString(home.resolve("state/reviews.json")).contains("provider_model_access_denied"));
        verify(w,never()).dispatch(any(),any(),any(),any(),any());
    }
    @Test void successfulDraftCanBeRevalidatedWithoutPaidRewriteButFailedExecutionCannot() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var a=topic("a");a.putAll(Map.of("status","blocked","taskId","failed-later","retryCount",7));
        var old=worker("complete","succeeded");old.setUserId("u1");old.getMeta().putAll(Map.of("reviewOnly",true,"projectId","p_a"));when(w.get("complete")).thenReturn(Optional.of(old));bundle("complete",corpus("ok"));
        var s=service(List.of(a),w);var r=s.reuseValidatedDraft("u1","c1","a","complete");var t=(Map<?,?>)((List<?>)r.get("topics")).getFirst();assertEquals("audit_pending",t.get("status"));assertEquals(7,t.get("retryCount"));assertEquals("unchanged original material",Files.readString(home.resolve("workspace/opencode-deliverables/complete/review.md")));verify(w,never()).dispatch(any(),any(),any(),any(),any());
        old.setStatus("failed");var c=((List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns")).getFirst();((List<Map<String,Object>>)c.get("topics")).getFirst().put("status","blocked");assertThrows(IllegalArgumentException.class,()->s.reuseValidatedDraft("u1","c1","a","complete"));
    }
    @Test void repairBriefIncludesActualFieldErrorInsteadOfOnlyGenericStrategy() throws Exception {
        var s=service(List.of(topic("a")),mock(MultiAgentOrchestrator.class));var t=topic("a");t.put("completionRecovery",Map.of("diagnosis","SOURCE_SCHEMA T1.url invalid","strategy","targeted-repair"));t.put("validationIssues",List.of(Map.of("sourceId","T1","field","url","expected","HTTP or bound snapshot")));
        String brief=ReflectionTestUtils.invokeMethod(s,"completionInstructions",t);assertTrue(brief.contains("SOURCE_SCHEMA T1.url invalid"));assertTrue(brief.contains("HTTP or bound snapshot"));
    }
    @Test void downstreamUsesVerifiedFactsWhileParentVisualDeliveryStillNeedsRepair() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var a=topic("a");var b=topic("b","a");
        Path facts=home.resolve("verified.json"),sources=home.resolve("sources.json");Files.writeString(facts,"{\"findings\":[]}");Files.writeString(sources,"[]");
        a.putAll(Map.of("status","needs_revision","researchReady",true,"verifiedLedgerFile",facts.toString(),"verifiedSourcesFile",sources.toString()));
        when(w.dispatch(any(),any(),any(),any(),any())).thenReturn(worker("next","queued"));
        var s=service(List.of(a,b),w);s.tick();
        verify(w).dispatch(eq("u1"),eq("CODER"),anyString(),anyString(),argThat(m->{var inputs=(Map<?,?>)m.get("reviewInputs");return inputs.containsKey("a-verified-findings.json")&&!inputs.containsKey("a-review.md");}));
        var t=(Map<?,?>)((List<?>)s.list("u1").getFirst().get("topics")).getFirst();assertEquals("needs_revision",t.get("status"));
    }
    @Test void supplementaryEvidenceIsTransportedWithoutExecutionInstructionsOrHistoricalInputs() throws Exception {
        Path folder=home.resolve("attachments");Files.createDirectories(folder);
        for(String n:List.of("identity-verification.md","review.md","audit.md","RESULT.md","AGENTS.md","previous-review.md"))Files.writeString(folder.resolve(n),"evidence");
        var files=ReviewCampaignService.supplementaryFiles(folder);assertEquals(Set.of("identity-verification.md"),files.keySet());
        assertEquals("evidence",Files.readString(Path.of(files.get("identity-verification.md"))));
    }
    @Test void continuousCompletionRepairsPastOldLimitAndPersistsDiagnosisWithoutDuplicateDispatch() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var a=topic("a");a.putAll(Map.of("status","needs_revision","taskId","audit","retryCount",9,"retryLimit",9,"audit",Map.of("verdict","revise","blockingIssues",List.of("missing attachment"))));
        var f=worker("audit","succeeded");f.getMeta().put("auditSchema",3);when(w.get("audit")).thenReturn(Optional.of(f));
        var s=service(List.of(a),w);s.setContinuousCompletion("u1","c1",true);
        var c=((List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns")).getFirst();var t=((List<Map<String,Object>>)c.get("topics")).getFirst();
        s.setPaused("u1","c1",true);ReflectionTestUtils.invokeMethod(s,"continueCompletion",c);assertFalse(t.containsKey("completionRecovery"));
        s.setPaused("u1","c1",false);ReflectionTestUtils.invokeMethod(s,"continueCompletion",c);
        var r=(Map<String,Object>)t.get("completionRecovery");assertTrue(r.get("diagnosis").toString().contains("missing attachment"));
        Object deadline=r.get("nextAttemptAt");ReflectionTestUtils.invokeMethod(s,"continueCompletion",c);assertEquals(deadline,((Map<?,?>)t.get("completionRecovery")).get("nextAttemptAt"));
        ((Map<String,Object>)t.get("completionRecovery")).put("nextAttemptAt","2000-01-01T00:00:00Z");ReflectionTestUtils.invokeMethod(s,"continueCompletion",c);
        assertEquals("pending",t.get("status"));assertEquals(9,t.get("retryCount"));assertEquals(1,t.get("completionAttempts"));
        ReflectionTestUtils.invokeMethod(s,"continueCompletion",c);assertEquals(1,t.get("completionAttempts"));
        ((List<?>)ReflectionTestUtils.getField(s,"campaigns")).clear();s.load();
        c=((List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns")).getFirst();t=((List<Map<String,Object>>)c.get("topics")).getFirst();assertEquals(1,t.get("completionAttempts"));
        var f2=worker("audit2","succeeded");f2.getMeta().put("auditSchema",3);when(w.get("audit2")).thenReturn(Optional.of(f2));t.put("taskId","audit2");t.put("status","needs_revision");
        ReflectionTestUtils.invokeMethod(s,"continueCompletion",c);r=(Map<String,Object>)t.get("completionRecovery");assertEquals("change-method-at-failing-target",r.get("strategy"));assertEquals("opencode-go/glm-5.3-flash",r.get("model"));
        verify(w,never()).dispatch(any(),any(),any(),any(),any());
    }
    @Test void continuousCompletionDoesNotDuplicateActiveOrMissingWorkersOrSkipDependency() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var a=topic("a");a.putAll(Map.of("status","blocked","taskId","active"));
        when(w.get("active")).thenReturn(Optional.of(worker("active","running")));
        var b=topic("b","a");b.putAll(Map.of("status","blocked","taskId","failed"));
        var s=service(List.of(a,b),w);s.setContinuousCompletion("u1","c1",true);
        var c=((List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns")).getFirst();ReflectionTestUtils.invokeMethod(s,"continueCompletion",c);
        for(var t:(List<Map<String,Object>>)c.get("topics"))assertFalse(t.containsKey("completionRecovery"));
        verify(w,never()).get("failed");verify(w,never()).dispatch(any(),any(),any(),any(),any());
    }
    @Test void runtimeRecoveryPersistsCooldownAndKeepsScientificAllowance() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var a=topic("a");a.putAll(Map.of("status","blocked","taskId","network","retryCount",2,"retryLimit",2));
        var failed=worker("network","failed");when(w.get("network")).thenReturn(Optional.of(failed));
        var s=service(List.of(a),w);ReflectionTestUtils.setField(s,"workspaceDir",home.toString());
        Path event=home.resolve("sandbox/opencode-jobs/network/events.jsonl");Files.createDirectories(event.getParent());
        Files.writeString(event,"{\"type\":\"error\",\"error\":{\"data\":{\"message\":\"unknown certificate verification error\"}}}\n");
        s.setAutomaticRuntimeRecovery("u1","c1",true);
        var c=((List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns")).getFirst();
        var t=((List<Map<String,Object>>)c.get("topics")).getFirst();
        s.setPaused("u1","c1",true);assertFalse((boolean)ReflectionTestUtils.invokeMethod(s,"recoverRuntime",c));
        s.setPaused("u1","c1",false);assertTrue((boolean)ReflectionTestUtils.invokeMethod(s,"recoverRuntime",c));
        var r=(Map<String,Object>)t.get("runtimeRecovery");Object deadline=r.get("nextAttemptAt");
        ReflectionTestUtils.invokeMethod(s,"recoverRuntime",c);assertEquals(deadline,((Map<?,?>)t.get("runtimeRecovery")).get("nextAttemptAt"));
        ((Map<String,Object>)t.get("runtimeRecovery")).put("nextAttemptAt","2000-01-01T00:00:00Z");
        ReflectionTestUtils.invokeMethod(s,"recoverRuntime",c);
        assertEquals("pending",t.get("status"));assertEquals(2,t.get("retryCount"));assertEquals(1,t.get("runtimeRetryCount"));
        t.put("status","blocked");ReflectionTestUtils.invokeMethod(s,"recoverRuntime",c);
        assertEquals("exhausted",((Map<?,?>)t.get("runtimeRecovery")).get("status"));assertEquals(1,t.get("runtimeRetryCount"));
        verify(w,never()).dispatch(any(),any(),any(),any(),any());
    }
    @Test void runtimeAuditRecoveryPreservesDraftAndDoesNotRepeatAfterReload() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var a=topic("a");a.putAll(Map.of("status","blocked","taskId","audit-failed","draftTaskId","draft","retryCount",2));
        var f=worker("audit-failed","failed");f.getMeta().put("auditSchema",3);when(w.get(f.getId())).thenReturn(Optional.of(f));
        bundle("draft",corpus("ok"));var s=service(List.of(a),w);ReflectionTestUtils.setField(s,"workspaceDir",home.toString());
        Path event=home.resolve("sandbox/opencode-jobs/audit-failed/events.jsonl");Files.createDirectories(event.getParent());
        Files.writeString(event,"{\"type\":\"error\",\"error\":{\"message\":\"503 overloaded\"}}\n");
        s.setAutomaticRuntimeRecovery("u1","c1",true);
        var c=((List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns")).getFirst();
        ReflectionTestUtils.invokeMethod(s,"recoverRuntime",c);
        var t=((List<Map<String,Object>>)c.get("topics")).getFirst();
        ((Map<String,Object>)t.get("runtimeRecovery")).put("nextAttemptAt","2000-01-01T00:00:00Z");
        ReflectionTestUtils.invokeMethod(s,"recoverRuntime",c);assertEquals("audit_pending",t.get("status"));assertEquals("draft",t.get("draftTaskId"));
        ((List<?>)ReflectionTestUtils.getField(s,"campaigns")).clear();s.load();
        c=((List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns")).getFirst();
        assertFalse((boolean)ReflectionTestUtils.invokeMethod(s,"recoverRuntime",c));
        t=((List<Map<String,Object>>)c.get("topics")).getFirst();assertEquals(1,t.get("runtimeRetryCount"));assertEquals(2,t.get("retryCount"));
        verify(w,never()).dispatch(any(),any(),any(),any(),any());
    }
    @Test void runtimeClassifierIgnoresToolTextAndPermanentCredentials() throws Exception {
        var s=service(List.of(topic("a")),mock(MultiAgentOrchestrator.class));ReflectionTestUtils.setField(s,"workspaceDir",home.toString());
        Path event=home.resolve("sandbox/opencode-jobs/f/events.jsonl");Files.createDirectories(event.getParent());
        var f=worker("f","failed");
        Files.writeString(event,"{\"type\":\"tool_use\",\"output\":\"certificate timeout 503\"}\n");
        assertEquals("",ReflectionTestUtils.invokeMethod(s,"runtimeCategory",f));
        Files.writeString(event,"{\"type\":\"error\",\"error\":{\"message\":\"unauthorized 503\"}}\n");
        assertEquals("",ReflectionTestUtils.invokeMethod(s,"runtimeCategory",f));
        Files.writeString(event,"{\"type\":\"error\",\"error\":{\"message\":\"503 overloaded\"}}\n");
        assertEquals("transient",ReflectionTestUtils.invokeMethod(s,"runtimeCategory",f));
        f.setStatus("succeeded");assertEquals("",ReflectionTestUtils.invokeMethod(s,"runtimeCategory",f));
    }
    @Test void validAuditAutomaticallyUsesRemainingRetryAndWakesDependenciesWithoutRaisingLimit() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var a=topic("a");
        a.putAll(Map.of("status","needs_revision","taskId","audit","retryCount",1,"retryLimit",2,
                "audit",Map.of("verdict","revise","blockingIssues",List.of("outdated stage statement"))));
        var b=topic("b","a");b.putAll(Map.of("status","blocked","error","前置综述未通过检查，等待修订后再综合"));
        var audit=worker("audit","succeeded");audit.getMeta().put("auditSchema",3);when(w.get("audit")).thenReturn(Optional.of(audit));
        var s=service(List.of(a,b),w);s.setAutomaticRevision("u1","c1",true);
        var c=((List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns")).getFirst();
        s.setPaused("u1","c1",true);assertFalse((boolean)ReflectionTestUtils.invokeMethod(s,"reviseAutomatically",c));
        s.setPaused("u1","c1",false);assertTrue((boolean)ReflectionTestUtils.invokeMethod(s,"reviseAutomatically",c));
        var topics=(List<Map<String,Object>>)c.get("topics");var actual=topics.getFirst();
        assertEquals("pending",actual.get("status"));assertEquals(2,actual.get("retryCount"));assertEquals(2,actual.get("retryLimit"));
        assertEquals("pending",topics.get(1).get("status"));assertFalse(actual.containsKey("retryAuthorizations"));
        assertEquals("audit",((Map<?,?>)actual.get("automaticRevisionRecord")).get("auditTaskId"));
        actual.put("status","needs_revision");assertFalse((boolean)ReflectionTestUtils.invokeMethod(s,"reviseAutomatically",c));
        assertTrue((boolean)((Map<?,?>)((List<?>)s.list("u1").getFirst().get("topics")).getFirst()).get("needsUserAction"));
        verify(w,never()).dispatch(any(),any(),any(),any(),any());
    }
    @Test void legacyBatchesAndIncompleteAuditsDoNotSilentlyTriggerPaidRevision() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var a=topic("a");a.putAll(Map.of("status","needs_revision","taskId","audit","audit",Map.of("verdict","pass","blockingIssues",List.of())));
        var s=service(List.of(a),w);var c=((List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns")).getFirst();
        assertFalse((boolean)ReflectionTestUtils.invokeMethod(s,"reviseAutomatically",c));
        s.setAutomaticRevision("u1","c1",true);assertFalse((boolean)ReflectionTestUtils.invokeMethod(s,"reviseAutomatically",c));
        assertThrows(IllegalArgumentException.class,()->s.setAutomaticRevision("other","c1",true));
        var created=s.start("u1","帮我做一份研究现状综述");assertEquals(true,created.get("automaticRevision"));
        verifyNoInteractions(w);
    }
    Map<String,Object> addCampaign(ReviewCampaignService service,String id,List<Map<String,Object>> topics) {
        var c=new LinkedHashMap<String,Object>(Map.of("id",id,"userId","u1","status","running","topics",topics));
        ((List<Map<String,Object>>)ReflectionTestUtils.getField(service,"campaigns")).add(c);return c;
    }
    AgentTask worker(String id,String status){var t=new AgentTask();t.setId(id);t.setStatus(status);return t;}
    @Test void independentCampaignRunsAlongsideBusyCampaignWithoutDuplicatingItsPhase() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var a=topic("a");a.putAll(Map.of("status","drafting","taskId","active"));
        when(w.get("active")).thenReturn(Optional.of(worker("active","running")));
        when(w.get("new")).thenReturn(Optional.of(worker("new","queued")));
        when(w.dispatch(any(),any(),any(),any(),any())).thenReturn(worker("new","queued"));
        var s=service(List.of(a,topic("next")),w);addCampaign(s,"c2",List.of(topic("independent")));
        s.tick();s.tick();
        verify(w,times(1)).dispatch(eq("u1"),eq("CODER"),contains("independent"),any(),argThat(m->"c2".equals(m.get("reviewCampaign"))));
        assertEquals(2,s.list("u1").getFirst().get("maxConcurrentCampaigns"));
    }
    @Test void twoCampaignCapAndFairQueueSurviveReloadAndSettleFailureWhileOtherRuns() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var a=topic("a");a.putAll(Map.of("status","drafting","taskId","active1"));
        var b=topic("b");b.putAll(Map.of("status","auditing","taskId","active2"));
        var first=worker("active1","running");
        when(w.get("active1")).thenReturn(Optional.of(first));when(w.get("active2")).thenReturn(Optional.of(worker("active2","running")));
        when(w.dispatch(any(),any(),any(),any(),any())).thenReturn(worker("third","queued"));
        var s=service(List.of(a,topic("next")),w);
        var c1=((List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns")).getFirst();c1.put("lastDispatchAt","2026-10-04T00:00:00Z");
        addCampaign(s,"c2",List.of(b));addCampaign(s,"c3",List.of(topic("waiting")));
        s.setPaused("u1","c1",false); // persist all three for a restart
        var restored=new ReviewCampaignService();
        for(String name:List.of("home","workers","boards"))ReflectionTestUtils.setField(restored,name,ReflectionTestUtils.getField(s,name));
        restored.load();restored.tick();verify(w,never()).dispatch(any(),any(),any(),any(),any());
        first.setStatus("failed");restored.tick();
        verify(w,times(1)).dispatch(any(),any(),contains("waiting"),any(),argThat(m->"c3".equals(m.get("reviewCampaign"))));
        var topics=(List<?>)restored.list("u1").getFirst().get("topics");
        assertEquals("blocked",((Map<?,?>)topics.getFirst()).get("status"));
    }
    @Test void pausingAndRetryingOneCampaignDoesNotBlockAnother() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var a=topic("a");a.put("status","needs_revision");
        var s=service(List.of(a),w);s.setPaused("u1","c1",true);
        var other=addCampaign(s,"c2",List.of(topic("other")));
        when(w.dispatch(any(),any(),any(),any(),any())).thenReturn(worker("otherJob","queued"));
        when(w.get("otherJob")).thenReturn(Optional.of(worker("otherJob","running")));
        s.tick();s.retry("u1","c1","a","draft");s.tick();
        assertEquals(true,s.list("u1").getFirst().get("paused"));
        assertEquals("running",other.get("status"));
        verify(w,times(1)).dispatch(any(),any(),contains("other"),any(),any());
    }
    @Test void autonomousHandoffWaitsForGatePauseAndExplicitEnableWithoutReplenishingOnReload() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var medical=topic("medical");medical.put("status","blocked");
        var rest=topic("rest");rest.putAll(Map.of("status","needs_revision","taskId","audit1","retryCount",2,"retryLimit",2));
        var audit=new AgentTask();audit.setId("audit1");audit.setStatus("succeeded");audit.getMeta().put("auditSchema",3);
        when(w.get("audit1")).thenReturn(Optional.of(audit));
        var s=service(List.of(medical,rest),w);
        s.autonomousContinuation("u1","c1","handoff","medical",List.of("rest"),false,"human asks independent continuation");
        var c=((List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns")).getFirst();
        assertFalse((boolean)ReflectionTestUtils.invokeMethod(s,"recoverAutonomously",c));
        s.autonomousContinuation("u1","c1","handoff","medical",List.of("rest"),true,"same grant");
        assertFalse((boolean)ReflectionTestUtils.invokeMethod(s,"recoverAutonomously",c));
        var actual=(List<Map<String,Object>>)c.get("topics");actual.getFirst().put("status","draft_ready");
        s.setPaused("u1","c1",true);assertFalse((boolean)ReflectionTestUtils.invokeMethod(s,"recoverAutonomously",c));
        s.setPaused("u1","c1",false);assertTrue((boolean)ReflectionTestUtils.invokeMethod(s,"recoverAutonomously",c));
        assertEquals("pending",actual.get(1).get("status"));assertEquals("draft",actual.get(1).get("recoveryStage"));
        assertEquals(3,actual.get(1).get("retryCount"));
        // Reload from the actual persistent state; do not reset the allowance.
        var reloaded=new ReviewCampaignService();
        for(String name:List.of("home","workers","boards"))ReflectionTestUtils.setField(reloaded,name,ReflectionTestUtils.getField(s,name));
        reloaded.load();
        var saved=reloaded.autonomousContinuation("u1","c1","handoff","medical",List.of("rest"),true,"same grant");
        assertEquals(1,((Map<?,?>)((Map<?,?>)saved.get("autonomousContinuation")).get("used")).get("rest"));
        assertThrows(IllegalArgumentException.class,()->reloaded.autonomousContinuation("u1","c1","reset","medical",List.of("rest"),true,"reset forbidden"));
        verify(w,never()).dispatch(any(),any(),any(),any(),any());
    }
    @Test void autonomousRecoveryIsBoundedAndNeverChargesDependencyWaitsOrMissingRecords() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var gate=topic("gate");gate.put("status","draft_ready");
        var rest=topic("rest");rest.putAll(Map.of("status","needs_revision","taskId","audit1","retryCount",2));
        var dependent=topic("dependent","rest");dependent.put("status","blocked");
        var s=service(List.of(gate,rest,dependent),w);
        s.autonomousContinuation("u1","c1","handoff","gate",List.of("rest","dependent"),true,"independent continuation");
        var c=((List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns")).getFirst();
        assertFalse((boolean)ReflectionTestUtils.invokeMethod(s,"recoverAutonomously",c));
        var actual=((List<Map<String,Object>>)c.get("topics")).get(1);
        for(int n=1;n<=3;n++) {
            String taskId="audit"+n;var task=new AgentTask();task.setId(taskId);task.setStatus("succeeded");task.getMeta().put("auditSchema",3);
            when(w.get(taskId)).thenReturn(Optional.of(task));actual.put("taskId",taskId);actual.put("status","needs_revision");
            assertEquals(n<=2,(boolean)ReflectionTestUtils.invokeMethod(s,"recoverAutonomously",c));
        }
        assertEquals(4,actual.get("retryCount"));
        assertEquals(2,((List<?>)((Map<?,?>)c.get("autonomousContinuation")).get("history")).size());
        verify(w,never()).dispatch(any(),any(),any(),any(),any());
    }
    @Test void explicitIllustratedUpgradePreservesPriorWorkAndIsIdempotent() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var t=topic("a");t.putAll(Map.of("status","draft_ready","draftTaskId","original","taskId","audit","retryCount",4,"retryLimit",4));
        var service=service(List.of(t),w);
        var first=service.illustratedEdition("u1","c1","owner-illustrations","用户需要中文写作和综述图片");
        var upgraded=(Map<?,?>)((List<?>)first.get("topics")).getFirst();
        assertEquals("pending",upgraded.get("status"));assertEquals(4,upgraded.get("retryCount"));assertEquals(6,upgraded.get("retryLimit"));
        assertEquals("draft_ready",((Map<?,?>)upgraded.get("previousDelivery")).get("status"));
        assertEquals(first,service.illustratedEdition("u1","c1","owner-illustrations","same"));
        assertThrows(IllegalArgumentException.class,()->service.illustratedEdition("u1","c1","new-id","cannot reset again"));
        verifyNoInteractions(w);
    }
    @Test void illustratedUpgradeWaitsForActivePhase() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var t=topic("a");t.put("status","illustrating");
        var service=service(List.of(t),w);
        assertThrows(IllegalArgumentException.class,()->service.illustratedEdition("u1","c1","owner","new scope"));
    }
    @TempDir Path home;
    final ObjectMapper json=new ObjectMapper();
    @Test void savedSingleFigureSurvivesReloadAndNextDispatchSkipsIt() throws Exception {
        var w=mock(MultiAgentOrchestrator.class);var a=topic("a");
        a.putAll(Map.of("status","illustration_pending","draftTaskId","draft"));
        Path draft=home.resolve("workspace/opencode-deliverables/draft");Files.createDirectories(draft);
        var plan=new ReviewIllustrationsTest().figures();
        Files.writeString(draft.resolve("sources.json"),"[{\"id\":\"S1\"}]");
        Files.writeString(draft.resolve("figure-plan.json"),json.writeValueAsString(plan));
        var task=new AgentTask();task.setId("single1");
        when(w.dispatch(any(),any(),any(),any(),any())).thenAnswer(call->{task.setMeta(call.getArgument(4));return task;});
        var s=service(List.of(a),w);var campaign=new LinkedHashMap<String,Object>(Map.of("id","c1","userId","u1"));
        ReflectionTestUtils.invokeMethod(s,"dispatchIllustrations",campaign,a);
        assertEquals("F1",task.getMeta().get("reviewFigureId"));assertEquals("illustrating",a.get("status"));
        Path output=home.resolve("workspace/opencode-deliverables/single1");Files.createDirectories(output);
        Files.writeString(output.resolve("figures.json"),json.writeValueAsString(List.of(plan.getFirst())));
        Files.writeString(output.resolve("figure-1.svg"),"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1200 800'><text>fixture</text></svg>");
        ReflectionTestUtils.invokeMethod(s,"finishIllustrations",a,task);
        assertEquals("illustration_pending",a.get("status"));assertEquals(Map.of("F1","single1"),a.get("illustrationParts"));
        var reloaded=service(List.of(a),w);
        var stored=(List<Map<String,Object>>)ReflectionTestUtils.getField(reloaded,"campaigns");
        var restored=(Map<String,Object>)((List<?>)stored.getFirst().get("topics")).getFirst();
        ReflectionTestUtils.invokeMethod(reloaded,"dispatchIllustrations",stored.getFirst(),restored);
        assertEquals("F2",task.getMeta().get("reviewFigureId"));
        assertEquals(Map.of("F1","single1"),restored.get("illustrationParts"));
        assertEquals("draft",restored.get("draftTaskId"));
    }
    Map<String,Object> topic(String id,String... deps){return new LinkedHashMap<>(Map.of("id",id,"title",id,"brief","综述范围","dependsOn",List.of(deps),"projectId","p_"+id,"status","pending"));}
    ReviewCampaignService service(List<Map<String,Object>> topics,MultiAgentOrchestrator workers) throws Exception {
        Files.createDirectories(home.resolve("state"));
        Files.writeString(home.resolve("state/reviews.json"),json.writeValueAsString(List.of(Map.of("id","c1","userId","u1","status","running","topics",topics))));
        var s=new ReviewCampaignService();var quota=mock(HomeQuotaService.class);when(quota.homePath()).thenReturn(home);
        ReflectionTestUtils.setField(s,"home",quota);ReflectionTestUtils.setField(s,"workers",workers);
        var boards=mock(ProjectBoardService.class);when(boards.get(anyString())).thenReturn(Map.of("collaboration",Map.of("priorities","先读原文")));
        ReflectionTestUtils.setField(s,"boards",boards);s.load();return s;
    }
    @Test void detectsRequestsButHonorsNegation(){
        assertTrue(ReviewCampaignService.matches("开始以下研究：1.人工胰腺研究现状综述"));
        assertFalse(ReviewCampaignService.matches("不要开始水凝胶综述"));
        assertFalse(ReviewCampaignService.matches("综述和研究论文有什么区别"));
        assertFalse(ReviewCampaignService.matches("帮我分析一下综述有什么不足"));
    }
    @Test void dependenciesCannotPointForwardOrRepeat(){
        assertEquals(2,ReviewCampaignService.validatePlan(List.of(topic("a"),topic("b","a"))).size());
        assertThrows(IllegalArgumentException.class,()->ReviewCampaignService.validatePlan(List.of(topic("a","b"),topic("b"))));
        assertThrows(IllegalArgumentException.class,()->ReviewCampaignService.validatePlan(List.of(topic("a"),topic("b","a","a"))));
    }
    @Test void auditRequiresActualCorpusIdsAndNoIssues(){
        var ids=Set.of("a","b","c");
        assertTrue(ReviewCampaignService.auditPasses(Map.of("verdict","pass","issues",List.of(),"checkedSources",List.of("a","b","c")),ids));
        assertFalse(ReviewCampaignService.auditPasses(Map.of("verdict","pass","issues",List.of(),"checkedSources",List.of("a","a","a")),ids));
        assertFalse(ReviewCampaignService.auditPasses(Map.of("verdict","pass","issues",List.of("无法核对"),"checkedSources",List.of("a","b","c")),ids));
        assertFalse(ReviewCampaignService.auditPasses(Map.of("verdict","pass","issues",List.of(),"checkedSources",List.of("x","y","z")),ids));
    }
    @Test void auditCannotSilentlyRenumberSources(){
        List<Map<String,Object>> sources=List.of(Map.of("id","a","url","https://a.test"),Map.of("id","b","url","https://b.test"),Map.of("id","c","url","https://c.test"));
        List<Map<String,Object>> checks=new ArrayList<>();
        for(var s:sources){var check=new HashMap<>(s);check.put("locator","abstract");check.put("assessment","核对完成");checks.add(check);}
        var audit=Map.<String,Object>of("checkedSources",List.of("a","b","c"),"sourceChecks",checks);
        assertTrue(ReviewCampaignService.auditSourceChecksMatch(audit,sources));
        checks.getFirst().put("url","https://b.test");assertFalse(ReviewCampaignService.auditSourceChecksMatch(audit,sources));
    }
    @Test void runningTaskPreventsDuplicateAndDependentDispatchAfterReload() throws Exception {
        var workers=mock(MultiAgentOrchestrator.class);var a=topic("a");a.put("status","drafting");a.put("taskId","job1");
        var task=new AgentTask();task.setStatus("running");when(workers.get("job1")).thenReturn(Optional.of(task));
        var s=service(List.of(a,topic("b","a")),workers);s.tick();s.tick();
        verify(workers,never()).dispatch(any(),any(),any(),any(),any());
    }
    @Test void failedDependencyDoesNotBlockIndependentTopicAndOnlyOneDispatch() throws Exception {
        var workers=mock(MultiAgentOrchestrator.class);var a=topic("a");a.put("status","needs_revision");
        var task=new AgentTask();task.setId("job2");task.setStatus("queued");
        when(workers.dispatch(any(),any(),any(),any(),any())).thenReturn(task);when(workers.get("job2")).thenReturn(Optional.of(task));
        var s=service(List.of(a,topic("b","a"),topic("c"),topic("d")),workers);s.tick();s.tick();
        verify(workers,times(1)).dispatch(eq("u1"),eq("CODER"),contains("c"),contains("先读原文"),argThat(m->Boolean.TRUE.equals(m.get("reviewOnly"))));
        var topics=(List<?>)s.list("u1").getFirst().get("topics");assertEquals("pending",((Map<?,?>)topics.get(1)).get("status"));
    }
    @Test void pausePersistsAndPreventsNewPaidWork() throws Exception {
        var workers=mock(MultiAgentOrchestrator.class);var s=service(List.of(topic("a")),workers);
        s.setPaused("u1","c1",true);s.tick();verifyNoInteractions(workers);
        assertTrue(Files.readString(home.resolve("state/reviews.json")).contains("\"paused\" : true"));
        assertThrows(IllegalArgumentException.class,()->s.setPaused("other","c1",false));
    }
    @Test void revisionIsExplicitBoundedAndCannotRaceRunningWork() throws Exception {
        var workers=mock(MultiAgentOrchestrator.class);var a=topic("a");a.put("status","needs_revision");
        var s=service(List.of(a),workers);s.retry("u1","c1","a");
        assertThrows(IllegalArgumentException.class,()->s.retry("u1","c1","a"));
        verifyNoInteractions(workers);
    }
    @Test void extraRecoveryRequiresSeparateIdempotentOwnerAuthorization() throws Exception {
        var workers=mock(MultiAgentOrchestrator.class);var a=topic("a");a.put("status","needs_revision");a.put("retryCount",2);
        var s=service(List.of(a),workers);
        assertThrows(IllegalArgumentException.class,()->s.retry("u1","c1","a"));
        assertThrows(IllegalArgumentException.class,()->s.authorizeRetry("other","c1","a","approval1","继续"));
        assertThrows(IllegalArgumentException.class,()->s.authorizeRetry("u1","c1","a","approval1",""));
        s.authorizeRetry("u1","c1","a","approval1","用户明确要求继续，一次定向修订");
        s.authorizeRetry("u1","c1","a","approval1","用户明确要求继续，一次定向修订");
        assertThrows(IllegalArgumentException.class,()->s.authorizeRetry("u1","c1","a","approval2","不能堆叠未使用额度"));
        var result=s.retry("u1","c1","a");
        var saved=(Map<?,?>)((List<?>)result.get("topics")).getFirst();
        assertEquals(3,saved.get("retryCount"));assertEquals(3,saved.get("retryLimit"));
        assertEquals(1,((List<?>)saved.get("retryAuthorizations")).size());
        // A repeat authorization after consuming the grant must not add another allowance.
        s.authorizeRetry("u1","c1","a","approval1","继续");
        assertThrows(IllegalArgumentException.class,()->s.retry("u1","c1","a"));
        verifyNoInteractions(workers);
    }
    @Test void newAuditSeparatesLimitationsButNeverOverridesOldOrContradictoryIssues() {
        var ids=Set.of("a","b","c");var audit=new HashMap<String,Object>();
        audit.put("verdict","pass");audit.put("checkedSources",List.of("a","b","c"));
        audit.put("blockingIssues",List.of());audit.put("nonBlockingLimitations",List.of("范围性覆盖局限已披露"));
        assertTrue(ReviewCampaignService.auditPasses(audit,ids,3));
        assertFalse(ReviewCampaignService.auditPasses(audit,ids,2));
        audit.put("issues",List.of("旧格式仍有错误"));assertFalse(ReviewCampaignService.auditPasses(audit,ids,3));
        audit.remove("issues");audit.put("blockingIssues",List.of("年份误报"));assertFalse(ReviewCampaignService.auditPasses(audit,ids,3));
        audit.put("blockingIssues",List.of());audit.remove("nonBlockingLimitations");assertFalse(ReviewCampaignService.auditPasses(audit,ids,3));
    }
    @Test void timedOutRevisionRecoversItsCompletePriorInputBundle() throws Exception {
        var workers=mock(MultiAgentOrchestrator.class);var a=topic("a");
        a.put("retried",true);a.put("draftTaskId","timeout");a.put("taskId","timeout");
        Map<String,String> inputs=new LinkedHashMap<>();
        for(String name:List.of("review.md","sources.json","evidence.csv","protocol.md","gaps.md","audit.md","audit.json")) {
            Path file=home.resolve("workspace/opencode-deliverables/old/"+name);Files.createDirectories(file.getParent());Files.writeString(file,"original material");
            inputs.put("previous-"+name,file.toString());
        }
        var old=new AgentTask();old.setMeta(Map.of("reviewInputs",inputs));when(workers.get("timeout")).thenReturn(Optional.of(old));
        var next=new AgentTask();next.setId("new");when(workers.dispatch(any(),any(),any(),any(),any())).thenReturn(next);
        var s=service(List.of(a),workers);s.tick();
        verify(workers).dispatch(eq("u1"),eq("CODER"),anyString(),anyString(),argThat(m->inputs.equals(m.get("reviewInputs"))));
        assertEquals("original material",Files.readString(Path.of(inputs.get("previous-review.md"))));
    }
    @Test void auditReceivesEvaluationInstructionsInsteadOfRevisionCommands() throws Exception {
        var workers=mock(MultiAgentOrchestrator.class);var a=topic("a");
        a.put("status","audit_pending");a.put("draftTaskId","draft");a.put("audit",Map.of("verdict","revise","issues",List.of("历史错误")));
        var next=new AgentTask();next.setId("audit");when(workers.dispatch(any(),any(),any(),any(),any())).thenReturn(next);
        bundle("draft",corpus("ok"));
        Files.writeString(home.resolve("workspace/opencode-deliverables/draft/identity-verification.md"),"verified identity");
        var s=service(List.of(a),workers);s.tick();
        verify(workers).dispatch(eq("u1"),eq("CODER"),contains("证据审查"),
                argThat(brief->brief.contains("仅评估当前稿是否解决，不亲自修稿")&&brief.contains("不得编辑inputs")&&!brief.contains("上一轮审查反馈，请逐项解决")),
                argThat(meta->Integer.valueOf(4).equals(meta.get("auditSchema"))&&((Map<?,?>)meta.get("reviewInputs")).containsKey("identity-verification.md")&&((Map<?,?>)meta.get("reviewInputs")).containsKey("audit-input-manifest.json")));
    }
    void bundle(String id,String sources) throws Exception {
        Path folder=home.resolve("workspace/opencode-deliverables/"+id);Files.createDirectories(folder);
        for(String name:List.of("review.md","evidence.csv","protocol.md","gaps.md"))Files.writeString(folder.resolve(name),"unchanged original material");
        Files.writeString(folder.resolve("sources.json"),sources);
    }
    String corpus(String locator){return "["+ReviewArtifactRepairTest.source("a",locator)+","+ReviewArtifactRepairTest.source("b","ok")+","+ReviewArtifactRepairTest.source("c","ok")+"]";}
    @Test void formattingRecoveryPreservesOriginalAndStillRequiresAudit() throws Exception {
        var workers=mock(MultiAgentOrchestrator.class);var a=topic("a");a.putAll(Map.of("status","blocked","taskId","draft","draftTaskId","draft","retryCount",3));
        var draft=new AgentTask();draft.setStatus("succeeded");when(workers.get("draft")).thenReturn(Optional.of(draft));
        String raw=corpus("contains \"quoted\" text");bundle("draft",raw);
        var s=service(List.of(a),workers);var result=s.repairSourceFormat("u1","c1","a");
        var t=(Map<?,?>)((List<?>)result.get("topics")).getFirst();
        assertEquals("audit_pending",t.get("status"));assertEquals(3,t.get("retryCount"));
        assertEquals(raw,Files.readString(home.resolve("workspace/opencode-deliverables/draft/sources.json")));
        assertEquals("unchanged original material",Files.readString(home.resolve("workspace/opencode-deliverables/"+t.get("draftArtifactId")+"/review.md")));
        assertEquals(result,s.repairSourceFormat("u1","c1","a"));
        verify(workers,never()).dispatch(any(),any(),any(),any(),any());
    }
    @Test void auditOnlyRetryConsumesAllowanceAndDoesNotRedoDraft() throws Exception {
        var workers=mock(MultiAgentOrchestrator.class);var a=topic("a");a.putAll(Map.of("status","blocked","taskId","failed-audit","draftTaskId","draft","retryCount",2));
        var failed=new AgentTask();failed.setStatus("failed");failed.setMeta(Map.of("auditSchema",3));when(workers.get("failed-audit")).thenReturn(Optional.of(failed));
        bundle("draft",corpus("ok"));var s=service(List.of(a),workers);
        assertThrows(IllegalArgumentException.class,()->s.retry("u1","c1","a","audit"));
        s.authorizeRetry("u1","c1","a","owner","only retry audit");
        var t=(Map<?,?>)((List<?>)s.retry("u1","c1","a","audit").get("topics")).getFirst();
        assertEquals("audit_pending",t.get("status"));assertEquals("draft",t.get("draftTaskId"));assertEquals(3,t.get("retryCount"));
    }
    @Test void scientificRevisionCannotBeReplacedByAuditRetry() throws Exception {
        var workers=mock(MultiAgentOrchestrator.class);var a=topic("a");a.putAll(Map.of("status","needs_revision","taskId","audit","draftTaskId","draft"));
        var audit=new AgentTask();audit.setStatus("succeeded");audit.setMeta(Map.of("auditSchema",3));when(workers.get("audit")).thenReturn(Optional.of(audit));
        var s=service(List.of(a),workers);assertThrows(IllegalArgumentException.class,()->s.retry("u1","c1","a","audit"));
    }
    @Test void figureRevisionPreservesTextAndStillRequiresFullAuditWithinLimit() throws Exception {
        var workers=mock(MultiAgentOrchestrator.class);var a=topic("a");
        a.putAll(Map.of("status","needs_revision","taskId","audit","draftTaskId","draft","retryCount",0));
        var audit=new AgentTask();audit.setStatus("succeeded");audit.setMeta(Map.of("auditSchema",3));
        when(workers.get("audit")).thenReturn(Optional.of(audit));bundle("draft",corpus("ok"));
        var s=service(List.of(a),workers);
        @SuppressWarnings("unchecked") var campaigns=(List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns");
        campaigns.getFirst().put("deliveryVersion",2);
        var revised=(Map<?,?>)((List<?>)s.retry("u1","c1","a","figures").get("topics")).getFirst();
        assertEquals("illustration_pending",revised.get("status"));assertEquals(1,revised.get("retryCount"));
        assertEquals("draft",revised.get("draftTaskId"));
        assertEquals("unchanged original material",Files.readString(home.resolve("workspace/opencode-deliverables/draft/review.md")));
        verify(workers,never()).dispatch(any(),any(),any(),any(),any());
    }
    @Test void figureRevisionCannotBypassExhaustedRecoveryLimit() throws Exception {
        var workers=mock(MultiAgentOrchestrator.class);var a=topic("a");a.putAll(Map.of("status","needs_revision","retryCount",2));
        var s=service(List.of(a),workers);assertThrows(IllegalArgumentException.class,()->s.retry("u1","c1","a","figures"));
        verifyNoInteractions(workers);
    }
    @Test void visualFeedbackPreservesModelVerdictAndDoesNotChargeOrIncreaseLimit() throws Exception {
        var workers=mock(MultiAgentOrchestrator.class);var a=topic("a");
        a.putAll(Map.of("status","draft_ready","illustrationTaskId","figures","taskId","audit","audit",Map.of("verdict","pass"),"retryCount",2));
        var s=service(List.of(a),workers);
        @SuppressWarnings("unchecked") var campaigns=(List<Map<String,Object>>)ReflectionTestUtils.getField(s,"campaigns");campaigns.getFirst().put("deliveryVersion",2);
        var result=s.figureFeedback("u1","c1","a","visual1","图中标签重叠且箭头方向不一致");
        var t=(Map<?,?>)((List<?>)result.get("topics")).getFirst();
        assertEquals("needs_revision",t.get("status"));assertEquals("pass",((Map<?,?>)t.get("audit")).get("verdict"));assertEquals(2,t.get("retryCount"));
        assertEquals(result,s.figureFeedback("u1","c1","a","visual1","same"));
        assertThrows(IllegalArgumentException.class,()->s.retry("u1","c1","a","figures"));verifyNoInteractions(workers);
    }
    @Test void visualFeedbackCannotInterruptAnActiveAudit() throws Exception {
        var s=service(List.of(new LinkedHashMap<>(Map.of("id","a","status","auditing"))),mock(MultiAgentOrchestrator.class));
        assertThrows(IllegalArgumentException.class,()->s.figureFeedback("u1","c1","a","qa","等待运行结束"));
    }
    @Test void dependencyChangeDoesNotAlterEvidenceOrPermitCycles() throws Exception {
        var workers=mock(MultiAgentOrchestrator.class);var a=topic("a");a.put("status","blocked");var b=topic("b","a");b.put("status","blocked");
        var s=service(List.of(a,b),workers);
        assertThrows(IllegalArgumentException.class,()->s.updateDependencies("u1","c1","a",List.of("b"),"invalid","cycle"));
        var result=s.updateDependencies("u1","c1","b",List.of(),"owner","independent question");
        var t=(Map<?,?>)((List<?>)result.get("topics")).get(1);
        assertEquals(List.of(),t.get("dependsOn"));assertEquals("pending",t.get("status"));
        assertEquals("blocked",((Map<?,?>)((List<?>)result.get("topics")).getFirst()).get("status"));
        verifyNoInteractions(workers);
    }
}
