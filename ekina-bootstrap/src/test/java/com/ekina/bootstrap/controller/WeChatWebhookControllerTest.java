package com.ekina.bootstrap.controller;

import com.ekina.application.agent.ReActAgentExecutor;
import com.ekina.domain.agent.WeChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WeChatWebhookController.class)
public class WeChatWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReActAgentExecutor agentExecutor;

    @MockBean
    private WeChatService weChatService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void testWebhookReturnsImmediatelyAndRunsAsynchronously() throws Exception {
        WeChatWebhookController.WebhookRequest request = new WeChatWebhookController.WebhookRequest();
        request.setUserId("user_test_123");
        request.setContent("Hi, help me do something");

        when(agentExecutor.executeChat(anyString(), anyString())).thenReturn("This is an agent response");

        long startTime = System.currentTimeMillis();

        mockMvc.perform(post("/api/wxbot/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));

        long duration = System.currentTimeMillis() - startTime;
        // Assert that the HTTP response was returned very quickly (under 500ms)
        assertTrue(duration < 500, "Webhook response took too long: " + duration + "ms");

        // The async thread should finish and send the outbound message
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            verify(agentExecutor, times(1)).executeChat("user_test_123", "Hi, help me do something");
            verify(weChatService, times(1)).sendMessage("user_test_123", "This is an agent response");
        });
    }

    private void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
