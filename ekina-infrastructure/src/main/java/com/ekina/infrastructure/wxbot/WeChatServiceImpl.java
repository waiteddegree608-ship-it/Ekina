package com.ekina.infrastructure.wxbot;

import com.ekina.domain.agent.WeChatOutboundMsg;
import com.ekina.domain.agent.WeChatService;
import com.ekina.domain.repository.WeChatOutboundMsgRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * WeChat Service Implementation.
 * Uses a database-backed outbound message queue (durable WeChat outbound queue).
 * Supports sequential FIFO delivery per user, automatic message consolidation, simulated human
 * typing delay with jitter, outbound rate-limiting/cooldown, and exponential backoff retries.
 */
@Service
public class WeChatServiceImpl implements WeChatService {

    @Value("${ekina.wxbot.enabled:false}")
    private boolean enabled;

    @Value("${ekina.wxbot.token:}")
    private String token;

    @Value("${ekina.wxbot.api-url:}")
    private String apiUrl;

    @Autowired
    private WeChatOutboundMsgRepository weChatOutboundMsgRepository;

    private final RestClient restClient = RestClient.builder().build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> activeUsers = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        // Start background message queue consumer thread
        executor.submit(this::messageConsumerLoop);
    }

    @PreDestroy
    public void destroy() {
        running = false;
        executor.shutdown();
    }

    @Override
    public void sendMessage(String userId, String content) {
        if (content == null || content.trim().isEmpty()) {
            return;
        }

        // Strip action descriptions in parentheses and asterisks
        String filteredContent = content
                .replaceAll("（[^）]*）", "")
                .replaceAll("\\([^)]*\\)", "")
                .replaceAll("\\*[^*]*\\*", "")
                .replaceAll("(?m)^\\s+$", "") // Remove blank lines consisting only of spaces
                .replaceAll("\n{2,}", "\n")   // Condense consecutive newlines
                .trim();

        if (filteredContent.isEmpty()) {
            return;
        }

        WeChatOutboundMsg msg = new WeChatOutboundMsg(userId, "TEXT", filteredContent, null);
        weChatOutboundMsgRepository.save(msg);
        System.out.println("[WeChat Queue] Persisted text message targeting: " + userId + " (filtered content length: " + filteredContent.length() + ")");
    }

    @Override
    public void sendAudio(String userId, byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) {
            return;
        }
        WeChatOutboundMsg msg = new WeChatOutboundMsg(userId, "AUDIO", null, audioBytes);
        weChatOutboundMsgRepository.save(msg);
        System.out.println("[WeChat Queue] Persisted audio message targeting: " + userId);
    }

    /**
     * Poller running every 1 second.
     */
    private void messageConsumerLoop() {
        System.out.println("WeChat outbound message consumer started.");
        while (running) {
            try {
                Thread.sleep(1000);
                if (!enabled) {
                    continue;
                }

                LocalDateTime now = LocalDateTime.now();
                List<WeChatOutboundMsg> pending = weChatOutboundMsgRepository.findPendingMessages(now);
                if (pending.isEmpty()) {
                    continue;
                }

                // Group messages by userId to enforce sequential delivery per user
                Map<String, List<WeChatOutboundMsg>> groupedByUserId = pending.stream()
                        .collect(Collectors.groupingBy(WeChatOutboundMsg::getUserId));

                for (Map.Entry<String, List<WeChatOutboundMsg>> entry : groupedByUserId.entrySet()) {
                    String userId = entry.getKey();
                    if (activeUsers.contains(userId)) {
                        // Already processing this user's queue sequentially
                        continue;
                    }

                    List<WeChatOutboundMsg> userMsgs = entry.getValue();
                    // Sort by ID to ensure FIFO order
                    userMsgs.sort(Comparator.comparing(WeChatOutboundMsg::getId));

                    activeUsers.add(userId);
                    executor.submit(() -> {
                        try {
                            processUserMessages(userId, userMsgs);
                        } finally {
                            activeUsers.remove(userId);
                        }
                    });
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("WeChat consumer queue processor error: " + e.getMessage());
            }
        }
    }

    /**
     * Processes all eligible messages for a single user sequentially.
     */
    private void processUserMessages(String userId, List<WeChatOutboundMsg> userMsgs) {
        // Step 1: Consolidate consecutive TEXT messages for the same user
        List<WeChatOutboundMsg> consolidated = new ArrayList<>();
        WeChatOutboundMsg currentText = null;

        for (WeChatOutboundMsg msg : userMsgs) {
            if ("TEXT".equals(msg.getMessageType())) {
                if (currentText == null) {
                    currentText = msg;
                    consolidated.add(currentText);
                } else {
                    // Append content
                    currentText.setContent(currentText.getContent() + "\n" + msg.getContent());
                    // Mark the merged message as SENT directly
                    msg.setStatus(WeChatOutboundMsg.WeChatMsgStatus.SENT);
                    msg.setErrorMessage("Consolidated into message ID: " + currentText.getId());
                    weChatOutboundMsgRepository.save(msg);
                }
            } else {
                // Non-text (AUDIO) message breaks consolidation group
                currentText = null;
                consolidated.add(msg);
            }
        }

        // Save updated content for the root consolidated messages
        for (WeChatOutboundMsg msg : consolidated) {
            if ("TEXT".equals(msg.getMessageType()) && msg.getStatus() == WeChatOutboundMsg.WeChatMsgStatus.PENDING) {
                weChatOutboundMsgRepository.save(msg);
            }
        }

        // Step 2: Send sequentially with cooldown and typing simulation
        for (int i = 0; i < consolidated.size(); i++) {
            WeChatOutboundMsg msg = consolidated.get(i);
            if (msg.getStatus() == WeChatOutboundMsg.WeChatMsgStatus.SENT) {
                continue;
            }

            try {
                if ("TEXT".equals(msg.getMessageType())) {
                    simulateTypingDelay(msg.getContent());
                    sendTextMessageRaw(msg.getUserId(), msg.getContent());
                } else {
                    Thread.sleep(2000); // Constant delay for audio
                    sendAudioMessageRaw(msg.getUserId(), msg.getPayload());
                }

                // Update database on success
                msg.setStatus(WeChatOutboundMsg.WeChatMsgStatus.SENT);
                msg.setErrorMessage(null);
                weChatOutboundMsgRepository.save(msg);

            } catch (Exception e) {
                // Increment retry and schedule exponential backoff
                int newRetry = msg.getRetryCount() + 1;
                msg.setRetryCount(newRetry);
                msg.setErrorMessage(e.getMessage());

                if (newRetry < 3) {
                    msg.setStatus(WeChatOutboundMsg.WeChatMsgStatus.PENDING);
                    // delay = 10 * 2^retry seconds
                    int delaySeconds = 10 * (1 << newRetry);
                    msg.setNextSendTime(LocalDateTime.now().plusSeconds(delaySeconds));
                } else {
                    msg.setStatus(WeChatOutboundMsg.WeChatMsgStatus.FAILED);
                }
                weChatOutboundMsgRepository.save(msg);

                // Break to enforce sequential FIFO delivery: do not process subsequent messages if one fails
                break;
            }

            // Cooldown between consecutive sends to the same user
            if (i < consolidated.size() - 1) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Simulates typing delay based on content length with jitter.
     */
    private void simulateTypingDelay(String content) throws InterruptedException {
        int charCount = content != null ? content.length() : 0;
        // 150ms ~ 250ms per character
        double msPerChar = 150.0 + Math.random() * 100.0;
        double baseDelay = charCount * msPerChar;
        // Clamp base delay between 1.0s and 4.0s
        double clamped = Math.max(1000.0, Math.min(4000.0, baseDelay));
        // Apply +/- 15% random jitter
        double jitterMultiplier = 0.85 + Math.random() * 0.30;
        long finalDelay = Math.round(clamped * jitterMultiplier);

        System.out.println("[WeChat Queue] Simulating typing latency: " + finalDelay + "ms for character count: " + charCount);
        Thread.sleep(finalDelay);
    }

    private void sendTextMessageRaw(String userId, String content) {
        System.out.println(">>> [WeChat OUTBOUND SEND] To: " + userId + " | Content: " + content);
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            System.out.println("[WeChat Client] Warning: API URL is empty, skipping HTTP delivery.");
            return;
        }

        Map<String, String> body = Map.of(
                "userId", userId,
                "content", content
        );

        restClient.post()
                .uri(apiUrl + "/sendText")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private void sendAudioMessageRaw(String userId, byte[] audioBytes) {
        System.out.println(">>> [WeChat OUTBOUND SEND] To: " + userId + " | Content: Audio Payload (" + (audioBytes != null ? audioBytes.length : 0) + " bytes)");
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            System.out.println("[WeChat Client] Warning: API URL is empty, skipping HTTP delivery.");
            return;
        }

        String base64Audio = audioBytes != null ? Base64.getEncoder().encodeToString(audioBytes) : "";
        Map<String, String> body = Map.of(
                "userId", userId,
                "audio", base64Audio
        );

        restClient.post()
                .uri(apiUrl + "/sendAudio")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
