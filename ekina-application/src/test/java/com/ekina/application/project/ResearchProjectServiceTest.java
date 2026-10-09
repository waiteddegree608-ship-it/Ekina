package com.ekina.application.project;

import com.ekina.application.home.HomeQuotaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ResearchProjectServiceTest {
    @TempDir Path home;
    @Test void explicitResearchSurvivesLegacyTitleHeuristicsAndRetries() {
        var quota=mock(HomeQuotaService.class);when(quota.homePath()).thenReturn(home);
        var projects=new ProjectService();ReflectionTestUtils.setField(projects,"homeQuotaService",quota);
        var first=projects.createResearch("user-1","TabPFN 环境与训练实验","idea","request-12345");
        assertEquals(first.getId(),projects.createResearch("user-1","TabPFN 环境与训练实验","idea","request-12345").getId());
        projects.consolidateTaskProjectsIntoPortfolios("user-1");
        assertTrue(projects.isUserFacing(first));
        assertTrue(projects.listDock("user-1","ekina").stream().anyMatch(p->p.getId().equals(first.getId())));
        assertFalse(projects.listDock("user-1","agent").stream().anyMatch(p->p.getId().equals(first.getId())));
        assertThrows(IllegalArgumentException.class,()->projects.createResearch("user-1","不同目标","idea","request-12345"));
    }
}
