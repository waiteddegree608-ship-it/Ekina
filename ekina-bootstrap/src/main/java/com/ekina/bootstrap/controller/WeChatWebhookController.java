package com.ekina.bootstrap.controller;

import com.ekina.application.agent.ReActAgentExecutor;
import com.ekina.domain.agent.WeChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/wxbot/webhook")
public class WeChatWebhookController {

    @Autowired
    private ReActAgentExecutor agentExecutor;

    @Autowired
    private WeChatService weChatService;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public static class WebhookRequest {
        private String userId;
        private String content;

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    @PostMapping
    public ResponseEntity<String> receiveMessage(@RequestBody WebhookRequest request) {
        String userId = request.getUserId();
        String content = request.getContent();

        if (userId == null || content == null) {
            return ResponseEntity.badRequest().body("Invalid payload");
        }

        // Asynchronously process the chat turn and send response via persistent outbound queue
        executor.submit(() -> {
            try {
                String answer = agentExecutor.executeChat(userId, content);
                weChatService.sendMessage(userId, answer);
            } catch (Exception e) {
                System.err.println("Error processing webhook chat asynchronously: " + e.getMessage());
                e.printStackTrace();
            }
        });

        // Instantly return "success" within milliseconds to avoid WeChat's 5-second timeout retries
        return ResponseEntity.ok("success");
    }
}
