package com.ekina.application.review;

import com.ekina.application.home.HomeQuotaService;
import com.ekina.application.multiagent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewPaperFiguresTest {
    @TempDir Path home;
    @Test void waitsForAcceptedBaseDispatchesOnceAndPreservesOriginals() throws Exception {
        var quota=mock(HomeQuotaService.class);when(quota.homePath()).thenReturn(home);
        var workers=mock(MultiAgentOrchestrator.class);var task=new AgentTask();task.setId("plan");task.setStatus("running");
        when(workers.dispatch(any(),any(),any(),any(),any())).thenReturn(task);when(workers.get("plan")).thenReturn(Optional.of(task));
        var service=new ReviewPaperFigures();ReflectionTestUtils.setField(service,"home",quota);ReflectionTestUtils.setField(service,"workers",workers);
        Path base=home.resolve("workspace/opencode-deliverables/base");Files.createDirectories(base);Files.writeString(base.resolve("review.md"),"unchanged prose");Files.writeString(base.resolve("sources.json"),"[]");
        var topic=new LinkedHashMap<String,Object>(Map.of("status","needs_revision","draftArtifactId","base","projectId","project","title","Title"));
        var campaign=Map.<String,Object>of("id","campaign","userId","u1");service.enable(topic,"owner-pilot");
        assertFalse(service.advance(campaign,topic,"opencode-go/glm-5.3-flash"));verifyNoInteractions(workers);
        topic.put("status","draft_ready");assertTrue(service.advance(campaign,topic,"opencode-go/glm-5.3-flash"));
        assertFalse(service.advance(campaign,topic,"opencode-go/glm-5.3-flash"));
        verify(workers).dispatch(eq("u1"),eq("CODER"),any(),any(),argThat(meta->"paper-plan.json".equals(meta.get("paperFigureOutput"))&&((Map<?,?>)meta.get("reviewInputs")).containsKey("review.md")));
        Object originalState=topic.get("paperFigures");service.enable(topic,"another");assertSame(originalState,topic.get("paperFigures"));
        assertEquals("unchanged prose",Files.readString(base.resolve("review.md")));
        // An edited base must never hide a running paid worker and open a duplicate seat.
        Files.writeString(base.resolve("review.md"),"user edited");service.settle(topic);assertTrue(ReviewPaperFigures.active(topic));
        task.setStatus("failed");service.settle(topic);assertFalse(ReviewPaperFigures.active(topic));
        assertTrue(service.advance(campaign,topic,"opencode-go/glm-5.3-flash"));
        assertEquals("user edited",Files.readString(base.resolve("review.md")));
    }
    @Test void displayOnlyUsesAcceptedSupplementForSameBaseline() {
        var topic=new LinkedHashMap<String,Object>(Map.of("draftArtifactId","base","paperFigures",new LinkedHashMap<>(Map.of("phase","checking","baseArtifactId","base","artifactId","extra"))));
        assertEquals("base",ReviewPaperFigures.displayArtifact(topic));ReviewPaperFigures.state(topic).put("phase","ready");assertEquals("extra",ReviewPaperFigures.displayArtifact(topic));
        topic.put("draftArtifactId","newbase");assertEquals("newbase",ReviewPaperFigures.displayArtifact(topic));
    }
    @Test void auditRequiresRealReadsOfPlateAndOriginalPageAndEveryIdentityCheck() throws Exception {
        Path events=home.resolve("events.jsonl");var mapper=new ObjectMapper();
        var figures=List.of(Map.<String,Object>of("id","P01","file","figure-paper-01.png","panels",List.of(Map.of("paperId","p1","page",2))));
        var check=new LinkedHashMap<String,Object>(Map.of("id","P01","identityVerified",true,"figureVerified",true,"cropComplete",true,"relevant",true,"readable",true,"assessment","Verified title, DOI and Fig. 2 against PDF page."));
        var audit=new LinkedHashMap<String,Object>(Map.of("verdict","pass","blockingIssues",List.of(),"checks",List.of(check)));
        Files.writeString(events,read(mapper,"figure-paper-01.png"));assertFalse(ReviewPaperFigures.auditPasses(audit,figures,events));
        Files.writeString(events,read(mapper,"p1-page-002.png"),StandardOpenOption.APPEND);assertTrue(ReviewPaperFigures.auditPasses(audit,figures,events));
        check.put("identityVerified",false);assertFalse(ReviewPaperFigures.auditPasses(audit,figures,events));check.put("identityVerified",true);
        audit.put("blockingIssues",List.of("cropped axis"));assertFalse(ReviewPaperFigures.auditPasses(audit,figures,events));
    }
    private String read(ObjectMapper mapper,String file)throws Exception{return mapper.writeValueAsString(Map.of("type","tool_use","part",Map.of("tool","read","state",Map.of("status","completed","input",Map.of("filePath",home.resolve(file).toString())))))+"\n";}
}
