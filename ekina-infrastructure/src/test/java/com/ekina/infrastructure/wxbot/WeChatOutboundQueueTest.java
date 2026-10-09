package com.ekina.infrastructure.wxbot;

import com.ekina.domain.agent.WeChatOutboundMsg;
import com.ekina.domain.repository.WeChatOutboundMsgRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class WeChatOutboundQueueTest {

    @Mock
    private WeChatOutboundMsgRepository weChatOutboundMsgRepository;

    @InjectMocks
    private WeChatServiceImpl weChatService;

    @BeforeEach
    public void setup() {
        // Disable enabled check/HTTP requests during mock tests by default
        ReflectionTestUtils.setField(weChatService, "enabled", false);
        ReflectionTestUtils.setField(weChatService, "apiUrl", "");
    }

    @Test
    public void testSendMessageEnqueuesToRepository() {
        weChatService.sendMessage("user1", "Hello World");

        ArgumentCaptor<WeChatOutboundMsg> captor = ArgumentCaptor.forClass(WeChatOutboundMsg.class);
        verify(weChatOutboundMsgRepository).save(captor.capture());

        WeChatOutboundMsg enqueued = captor.getValue();
        assertEquals("user1", enqueued.getUserId());
        assertEquals("TEXT", enqueued.getMessageType());
        assertEquals("Hello World", enqueued.getContent());
        assertEquals(WeChatOutboundMsg.WeChatMsgStatus.PENDING, enqueued.getStatus());
    }

    @Test
    public void testSendMessageFiltersActionDescriptions() {
        String originalContent = "主人，您好！(轻轻用尾巴尖卷起您的衣角，金色的竖瞳温柔地注视着你) 今天天气很棒哦。*害羞*";
        weChatService.sendMessage("user1", originalContent);

        ArgumentCaptor<WeChatOutboundMsg> captor = ArgumentCaptor.forClass(WeChatOutboundMsg.class);
        verify(weChatOutboundMsgRepository).save(captor.capture());

        WeChatOutboundMsg enqueued = captor.getValue();
        assertEquals("user1", enqueued.getUserId());
        assertEquals("TEXT", enqueued.getMessageType());
        // Verified: Action descriptions in both English/Chinese parentheses and asterisks are stripped
        assertEquals("主人，您好！ 今天天气很棒哦。", enqueued.getContent());
    }

    @Test
    public void testSendAudioEnqueuesToRepository() {
        byte[] audioBytes = new byte[]{1, 2, 3};
        weChatService.sendAudio("user1", audioBytes);

        ArgumentCaptor<WeChatOutboundMsg> captor = ArgumentCaptor.forClass(WeChatOutboundMsg.class);
        verify(weChatOutboundMsgRepository).save(captor.capture());

        WeChatOutboundMsg enqueued = captor.getValue();
        assertEquals("user1", enqueued.getUserId());
        assertEquals("AUDIO", enqueued.getMessageType());
        assertArrayEquals(audioBytes, enqueued.getPayload());
        assertEquals(WeChatOutboundMsg.WeChatMsgStatus.PENDING, enqueued.getStatus());
    }

    @Test
    public void testConsolidationLogic() throws Exception {
        // We will call the private processUserMessages using reflection to test its behavior
        Method processMethod = WeChatServiceImpl.class.getDeclaredMethod("processUserMessages", String.class, List.class);
        processMethod.setAccessible(true);

        WeChatOutboundMsg msg1 = new WeChatOutboundMsg("user1", "TEXT", "Hello 1", null);
        msg1.setId(1L);
        WeChatOutboundMsg msg2 = new WeChatOutboundMsg("user1", "TEXT", "Hello 2", null);
        msg2.setId(2L);

        List<WeChatOutboundMsg> msgs = new ArrayList<>();
        msgs.add(msg1);
        msgs.add(msg2);

        // Invoke processUserMessages
        processMethod.invoke(weChatService, "user1", msgs);

        // msg1 should have been saved with consolidated text "Hello 1\nHello 2"
        // msg2 should have been marked SENT and consolidated
        ArgumentCaptor<WeChatOutboundMsg> captor = ArgumentCaptor.forClass(WeChatOutboundMsg.class);
        verify(weChatOutboundMsgRepository, atLeastOnce()).save(captor.capture());

        List<WeChatOutboundMsg> savedMsgs = captor.getAllValues();
        
        // Find msg1 saved state
        WeChatOutboundMsg savedMsg1 = savedMsgs.stream()
                .filter(m -> m.getId().equals(1L))
                .findFirst()
                .orElse(null);
        assertNotNull(savedMsg1);
        assertEquals("Hello 1\nHello 2", savedMsg1.getContent());

        // Find msg2 saved state
        WeChatOutboundMsg savedMsg2 = savedMsgs.stream()
                .filter(m -> m.getId().equals(2L))
                .findFirst()
                .orElse(null);
        assertNotNull(savedMsg2);
        assertEquals(WeChatOutboundMsg.WeChatMsgStatus.SENT, savedMsg2.getStatus());
        assertTrue(savedMsg2.getErrorMessage().contains("Consolidated"));
    }
}
