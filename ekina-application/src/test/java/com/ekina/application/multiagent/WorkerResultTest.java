package com.ekina.application.multiagent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class WorkerResultTest {
    @TempDir Path dir;
    @Test void successfulExecutionMayReportMissingEvidenceOrFetchTimeouts(){
        assertEquals("succeeded",MultiAgentOrchestrator.classifyResult("OpenCode exit=0\nRESULT_OK\n报告：未找到临床证据。抓取曾 timed out。科学审查需要修订。"));
    }
    @Test void documentTextCannotOverrideTransportFailure(){
        assertEquals("failed",MultiAgentOrchestrator.classifyResult("OpenCode exit=1\nRESULT_OK\nsummary"));
        assertEquals("failed",MultiAgentOrchestrator.classifyResult("OpenCode exit=0\nmodel=x\nstdout: RESULT_OK"));
        assertEquals("failed",MultiAgentOrchestrator.classifyResult("OpenCode timed out after 1800s\nRESULT_OK"));
    }
    @Test void structuredStatusCannotBeSpoofedByQuotedProse() throws Exception {
        Path result=dir.resolve("RESULT.json");
        Files.writeString(result,"{\"status\":\"failed\",\"summary\":\"RESULT_OK\",\"expected\":\"succeeded\"}");
        assertFalse(OpenCodeWorker.hasSucceededResultJson(result));
        Files.writeString(result,"{\"status\":\"succeeded\",\"summary\":\"审查要求修订\"}");
        assertTrue(OpenCodeWorker.hasSucceededResultJson(result));
    }
}
