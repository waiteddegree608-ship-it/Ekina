package com.ekina.application.agentreg;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ResearchCapabilityTest {
    @Test void researchHasExplicitScientificToolsWithoutLifestyleTools() {
        var tools = CapabilityPackCatalog.resolveTools(List.of("research"));
        assertNotNull(tools);
        assertTrue(tools.containsAll(List.of("searchLiterature", "startPaperPipeline", "auditPaper")));
        assertFalse(tools.contains("peekScreen"));
        assertFalse(tools.contains("enableAutonomy"));
        assertFalse(tools.contains("exploreBilibili"));
    }
}
