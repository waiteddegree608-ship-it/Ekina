package com.ekina.application.multiagent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
class OpenCodeReviewConfigTest {
    @Test void originalPaperStagesCannotEditProseOrImagesOrRunShell() throws Exception {
        var json=new ObjectMapper();
        for(String output:new String[]{"paper-plan.json","paper-selections.json","paper-audit.json"}) {
            var permissions=json.readTree(OpenCodeWorker.paperFigureConfig("opencode-go/glm-5.3-flash",output)).path("permission");
            assertEquals("deny",permissions.path("edit").path("*").asText());
            assertEquals("allow",permissions.path("edit").path("*/"+output).asText());
            assertEquals("deny",permissions.path("edit").path("*/inputs/*").asText());
            assertEquals("deny",permissions.path("bash").asText());
            assertEquals(output.equals("paper-plan.json")?"allow":"deny",permissions.path("webfetch").asText());
        }
        assertThrows(IllegalArgumentException.class,()->OpenCodeWorker.paperFigureConfig("opencode-go/glm-5.3-flash","review.md"));
    }
    @Test void partialEvidenceSynthesisDisablesMoreRetrievalButAuditRemainsOnline() throws Exception {
        var json=new ObjectMapper();
        var writer=json.readTree(OpenCodeWorker.reviewConfig("opencode-go/gpt-6-luna",false,false,true));
        assertEquals("deny",writer.path("permission").path("webfetch").asText());
        assertEquals("deny",writer.path("permission").path("websearch").asText());
        assertEquals("allow",writer.path("permission").path("read").asText());
        assertEquals("low",writer.path("agent").path("literature-review").path("options").path("reasoningEffort").asText());
        var audit=json.readTree(OpenCodeWorker.reviewConfig("opencode-go/gpt-6-luna",true,false,true));
        assertEquals("allow",audit.path("permission").path("webfetch").asText());
        assertEquals("deny",audit.path("permission").path("write").path("*").asText());
    }
    @TempDir Path temp;
    @Test void sharedWrapperDoesNotReintroduceJsonWritingForSingleFigureTasks() {
        String instructions=OpenCodeWorker.reviewResultInstruction(true);
        assertTrue(instructions.contains("RESULT.md"));assertFalse(instructions.contains("RESULT.json"));
        assertTrue(OpenCodeWorker.reviewResultInstruction(false).contains("RESULT.json"));
    }
    @Test void reportedReasoningTokensArePreservedEvenWhenVisibleOutputIsZero() throws Exception {
        Path job=Files.createDirectory(temp.resolve("job")),report=Files.createDirectory(temp.resolve("report"));
        String event="{\"type\":\"step_finish\",\"part\":{\"id\":\"once\",\"tokens\":{\"input\":10,\"output\":0,\"reasoning\":32000},\"cost\":0.02}}";
        Files.writeString(job.resolve("events.jsonl"),event+"\n"+event+"\n");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(new OpenCodeWorker(),"writeUsage",job,report,"opencode-go/glm-5.3-flash");
        var usage=new ObjectMapper().readTree(Files.readString(report.resolve("usage.json")));
        assertEquals(32000,usage.path("reasoningTokens").asLong());assertEquals(0,usage.path("outputTokens").asLong());
        assertEquals(1,usage.path("steps").asInt());assertEquals(0.02,usage.path("providerEstimatedCostUsd").asDouble());
    }
    @Test void illustrationUsesSupportedLowEffortWithoutDisablingForcedThinking() throws Exception {
        var json=new ObjectMapper();
        var drawing=json.readTree(OpenCodeWorker.reviewConfig("opencode-go/glm-5.3-flash",false,true));
        var options=drawing.path("agent").path("literature-review").path("options");
        assertEquals("low",options.path("reasoningEffort").asText());
        assertFalse(options.has("thinking"));
        assertTrue(json.readTree(OpenCodeWorker.reviewConfig("opencode-go/glm-5.3-flash",true)).path("agent").path("literature-review").path("options").isEmpty());
        assertEquals(36,drawing.path("agent").path("literature-review").path("steps").asInt());
        assertEquals("low",json.readTree(OpenCodeWorker.reviewConfig("opencode-go/gpt-6-luna",false,true)).path("agent").path("literature-review").path("options").path("reasoningEffort").asText());
    }
    @Test void reviewCannotEscalateModelOrLaunchCompute() throws Exception {
        var config=new ObjectMapper().readTree(OpenCodeWorker.reviewConfig("opencode-go/gpt-6-luna"));
        var agent=config.path("agent").path("literature-review");
        assertEquals(36,agent.path("steps").asInt());
        for(String action:new String[]{"bash","task","external_directory","*"})assertEquals("deny",agent.path("permission").path(action).asText());
        assertEquals("allow",agent.path("permission").path("webfetch").asText());
        assertEquals("opencode-go/gpt-6-luna",config.path("small_model").asText());
        assertThrows(IllegalArgumentException.class,()->OpenCodeWorker.reviewConfig("opencode-go/expensive-model"));
    }
    @Test void auditCanOnlyWriteReportsAndNeverInputCopies() throws Exception {
        var config=new ObjectMapper().readTree(OpenCodeWorker.reviewConfig("opencode-go/glm-5.3-flash",true));
        var edits=config.path("agent").path("literature-review").path("permission").path("edit");
        assertEquals("deny",edits.path("*").asText());
        assertEquals("allow",edits.path("*/audit.json").asText());
        assertEquals("deny",edits.path("*/inputs/*").asText());
        assertEquals("deny",edits.path("*\\inputs\\*").asText());
        assertTrue(edits.path("*/review.md").isMissingNode());
        var draft=new ObjectMapper().readTree(OpenCodeWorker.reviewConfig("opencode-go/glm-5.3-flash"));
        assertEquals("allow",draft.path("permission").path("edit").path("*").asText());
        assertEquals("deny",draft.path("permission").path("edit").path("inputs/*").asText());
    }
    @Test void modifiedAuditCopyCannotPassEvenWithSuccessfulResult() throws Exception {
        Path source=temp.resolve("original.md"),job=temp.resolve("job"),copy=job.resolve("inputs/review.md");
        Files.createDirectories(copy.getParent());Files.writeString(source,"original");Files.copy(source,copy);
        var task=new AgentTask();task.setMeta(Map.of("reviewInputs",Map.of("review.md",source.toString())));
        assertTrue(OpenCodeWorker.reviewInputsUnchanged(task,job));
        Files.writeString(copy,"revised by auditor");assertFalse(OpenCodeWorker.reviewInputsUnchanged(task,job));
        assertEquals("original",Files.readString(source));
        Files.delete(copy);assertFalse(OpenCodeWorker.reviewInputsUnchanged(task,job));
    }
}
