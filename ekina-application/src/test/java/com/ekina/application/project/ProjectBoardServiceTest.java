package com.ekina.application.project;

import com.ekina.application.home.HomeQuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectBoardServiceTest {
    @TempDir Path home;
    ProjectService projects;
    HomeQuotaService quota;
    ProjectBoardService boards;
    ProjectRecord project;
    @BeforeEach void setup() {
        projects=mock(ProjectService.class); quota=mock(HomeQuotaService.class);
        when(quota.homePath()).thenReturn(home);
        project=new ProjectRecord(); project.setId("research_test"); project.setTitle("研究测试"); project.setUserId("user-1");
        when(projects.get("research_test")).thenReturn(Optional.of(project));
        when(projects.listDock("user-1","ekina")).thenReturn(List.of(project));
        when(projects.toView(project)).thenReturn(Map.of("id",project.getId()));
        boards=service();
    }
    ProjectBoardService service() {
        var s=new ProjectBoardService(); ReflectionTestUtils.setField(s,"homeQuotaService",quota); ReflectionTestUtils.setField(s,"projectService",projects); return s;
    }
    @Test void legacyBoardRemainsReadableAndOwnerSettingsSurviveAgentPatches() throws Exception {
        Path file=home.resolve("workspace/projects/research_test/board.json");Files.createDirectories(file.getParent());
        Files.writeString(file,"{\"next\":[\"原有步骤\"],\"findings\":[{\"text\":\"原有发现\"}]}");
        assertEquals(List.of("原有步骤"),boards.get(project.getId()).get("next"));
        boards.setCollaboration(project.getId(),Map.of("mode","guided","budget",120));
        boards.upsert(project.getId(),Map.of("collaboration",Map.of("mode","delegated","budget",900),"now","准备验证"));
        Map<?,?> settings=(Map<?,?>)service().get(project.getId()).get("collaboration");
        assertEquals("guided",settings.get("mode"));assertEquals(120.0,settings.get("budget"));
        assertEquals(List.of("原有步骤"),boards.get(project.getId()).get("next"));
    }
    @Test void guidanceIsDurableIdempotentAndInjectedIntoContext() {
        boards.addGuidance(project.getId(),"实验规模","先做小样本","request-one");
        boards.addGuidance(project.getId(),"实验规模","先做小样本","request-one");
        assertEquals(1,((List<?>)service().get(project.getId()).get("guidance")).size());
        assertTrue(boards.asPromptBlock("user-1","ekina",project.getId()).contains("先做小样本"));
        assertThrows(IllegalArgumentException.class,()->boards.addGuidance(project.getId(),"实验规模","改了内容","request-one"));
        boards.upsert(project.getId(),Map.of("guidanceReply",Map.of("id","request-one","effect","先验证数据与代码，暂缓大规模实验")));
        Map<?,?> item=(Map<?,?>)((List<?>)service().get(project.getId()).get("guidance")).getFirst();
        assertEquals("responded",item.get("status"));assertTrue(item.get("reply").toString().contains("暂缓"));
    }
    @Test void initializationRetryDoesNotResetUserEdits() {
        boards.initializeResearch(project.getId(),"果蝇大脑","explore",Map.of());
        boards.setCollaboration(project.getId(),Map.of("mode","guided","budget",50));
        boards.upsert(project.getId(),Map.of("now","已找到原始论文"));
        boards.initializeResearch(project.getId(),"果蝇大脑","explore",Map.of("mode","delegated"));
        assertEquals("已找到原始论文",boards.get(project.getId()).get("now"));
        assertEquals("guided",((Map<?,?>)boards.get(project.getId()).get("collaboration")).get("mode"));
    }
    @Test void concurrentGuidanceAndAgentUpdatesDoNotLoseInstructions() throws Exception {
        try(var pool=Executors.newFixedThreadPool(4)) {
            List<Future<?>> jobs=new ArrayList<>();
            for(int i=0;i<12;i++){int n=i;jobs.add(pool.submit(()->boards.addGuidance(project.getId(),"整个课题","指导"+n,"request-"+n)));jobs.add(pool.submit(()->boards.upsert(project.getId(),Map.of("now","步骤"+n))));}
            for(Future<?> job:jobs)job.get(5,TimeUnit.SECONDS);
        }
        assertEquals(12,((List<?>)service().get(project.getId()).get("guidance")).size());
    }
    @Test void corruptBoardIsNotOverwrittenWithEmptyDefaults() throws Exception {
        Path file=home.resolve("workspace/projects/research_test/board.json");Files.createDirectories(file.getParent());Files.writeString(file,"broken");
        assertThrows(IllegalStateException.class,()->boards.get(project.getId()));assertEquals("broken",Files.readString(file));
    }

    @Test void researchRecordsSurviveRestartAndFailuresAreAtomic() {
        var patch = Map.<String,Object>of("researchUpdate",Map.of("expectedRevision",0,"records",List.of(
                ResearchLedgerTest.evidence("e"),ResearchLedgerTest.conclusion("c",List.of("e"),List.of()))),"now","核对证据");
        boards.upsert(project.getId(),patch);
        var restored = service().get(project.getId());
        assertFalse(restored.containsKey("researchLog"));
        assertEquals(2,((Map<?,?>)restored.get("research")).get("revision"));
        assertTrue(boards.asPromptBlock("user-1","ekina",project.getId()).contains("unreviewed"));
        var invalid = Map.<String,Object>of("now","不应保存", "researchUpdate",Map.of("expectedRevision",2,"records",List.of(
                ResearchLedgerTest.evidence("new"),ResearchLedgerTest.review("bad","missing","supports"))));
        assertThrows(IllegalArgumentException.class,()->boards.upsert(project.getId(),invalid));
        assertEquals("核对证据",service().get(project.getId()).get("now"));
        assertEquals(2,((Map<?,?>)service().get(project.getId()).get("research")).get("revision"));
        boards.upsert(project.getId(),Map.of("research",Map.of("revision",0),"researchLog",List.of()));
        assertEquals(2,((Map<?,?>)service().get(project.getId()).get("research")).get("revision"));
        boards.upsert(project.getId(),Map.of("researchUpdate",Map.of("expectedRevision",2,"records",List.of(ResearchLedgerTest.withdrawal("w","e")))));
        var ledger = (Map<?,?>)service().get(project.getId()).get("research");
        var conclusion = ((List<?>)ledger.get("records")).stream().map(r->(Map<?,?>)r).filter(r->"c".equals(r.get("id"))).findFirst().orElseThrow();
        assertEquals("needs_review",conclusion.get("assessment"));
    }

    @Test void simultaneousResearchUpdatesRequireMergeInsteadOfOverwriting() throws Exception {
        boards.get(project.getId());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            List<Future<Boolean>> writes=new ArrayList<>();
            for(int i=0;i<2;i++){int n=i;writes.add(pool.submit(()->{
                start.await();
                try { boards.upsert(project.getId(),Map.of("researchUpdate",Map.of("expectedRevision",0,"records",List.of(ResearchLedgerTest.evidence("e"+n)))));return true; }
                catch(ResearchLedger.Conflict conflict){return false;}
            }));}
            start.countDown();
            int accepted=0; for(var write:writes) if(write.get(5,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);
            assertEquals(1,((Map<?,?>)service().get(project.getId()).get("research")).get("revision"));
        }
    }
}
