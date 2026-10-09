package com.ekina.application.workflow;

import com.ekina.domain.repository.WorkflowStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration unit test for WorkflowEngine.
 * Simulates asynchronous execution inside virtual threads, Kryo serialization suspension,
 * and context resumption.
 */
public class WorkflowEngineTest {

    @InjectMocks
    private WorkflowEngine workflowEngine;

    @Mock
    private WorkflowStateRepository stateRepository;

    private final Map<String, byte[]> databaseMock = new HashMap<>();

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        databaseMock.clear();

        // Stub the repository to simulate PostgreSQL storage of Kryo bytes
        doAnswer(invocation -> {
            String wId = invocation.getArgument(0);
            byte[] bytes = invocation.getArgument(1);
            databaseMock.put(wId, bytes);
            return null;
        }).when(stateRepository).saveState(anyString(), any(byte[].class));

        when(stateRepository.getState(anyString())).thenAnswer(invocation -> {
            String wId = invocation.getArgument(0);
            return databaseMock.get(wId);
        });
    }

    @Test
    public void testWorkflowSuspensionAndResume() throws Exception {
        String userId = "user-test";
        String workflowId = workflowEngine.startWorkflow(userId, "{ \"trigger\": \"manual\" }");

        assertNotNull(workflowId);

        // Wait briefly for the virtual thread to execute Node 1 and suspend on Node 2
        Thread.sleep(400);

        // Verify that the workflow context was serialized using Kryo and stored in the database
        verify(stateRepository, atLeastOnce()).saveState(eq(workflowId), any(byte[].class));
        assertTrue(databaseMock.containsKey(workflowId));
        assertNotNull(databaseMock.get(workflowId));

        // Resume the workflow by mimicking user approval callback
        workflowEngine.resumeWorkflow(workflowId, true);

        // Wait briefly for Node 3 to complete
        Thread.sleep(400);

        // Verify that the database state was cleaned up upon successful completion
        verify(stateRepository, atLeastOnce()).deleteState(eq(workflowId));
    }
}
