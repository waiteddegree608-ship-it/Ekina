package com.ekina.application.agent;

import com.ekina.domain.agent.ChatMessage;
import com.ekina.domain.agent.LlmService;
import com.ekina.domain.repository.UserMemoryDomainRepository;
import com.ekina.application.agentreg.AgentRegistry;
import com.ekina.application.skill.SkillService;
import com.ekina.application.project.ProjectBoardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit test for ChatMemoryService
 */
public class ChatMemoryServiceTest {

    @InjectMocks
    private ChatMemoryService chatMemoryService;

    @Mock
    private LlmService llmService;

    @Mock
    private UserMemoryDomainRepository userMemoryRepository;

    @Mock private AgentRegistry agentRegistry;
    @Mock private SkillService skillService;
    @Mock private ProjectBoardService projectBoardService;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(agentRegistry.isLife(anyString())).thenReturn(true);
        when(agentRegistry.loadPersonaMarkdown(anyString())).thenReturn("Ekina 科研助手");
        when(skillService.buildPromptSection(anyString())).thenReturn("");
        when(llmService.embed(anyString())).thenReturn(new float[]{1.0f});
    }

    @Test
    public void testGetContextualMessages() {
        String userId = "user1";
        chatMemoryService.resetSession(userId);

        // Stub repository to return empty memories
        when(userMemoryRepository.findNearest(anyString(), any(float[].class), anyInt()))
                .thenReturn(Collections.emptyList());

        // Add a message
        chatMemoryService.addMessage(userId, ChatMessage.builder()
                .role(ChatMessage.MessageRole.USER)
                .content("Hello Ekina!")
                .build());

        List<ChatMessage> context = chatMemoryService.getContextualMessages(userId, "weather Shanghai");
        
        assertNotNull(context);
        // Expecting 2 messages: 1 SystemMessage (prepended system instructions) + 1 UserMessage
        assertEquals(2, context.size());
        assertEquals(ChatMessage.MessageRole.SYSTEM, context.get(0).getRole());
        assertEquals(ChatMessage.MessageRole.USER, context.get(1).getRole());
        assertEquals("Hello Ekina!", context.get(1).getContent());
        assertTrue(context.get(0).getContent().contains("合成数据"));
        assertFalse(context.get(0).getContent().contains("自主生活"));
    }
}
