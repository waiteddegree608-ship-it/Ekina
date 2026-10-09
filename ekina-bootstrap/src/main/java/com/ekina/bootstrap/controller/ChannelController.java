package com.ekina.bootstrap.controller;

import com.ekina.application.channel.ChannelMessage;
import com.ekina.application.channel.ChannelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/channel")
public class ChannelController {

    @Autowired
    private ChannelService channelService;

    /** Poll new companion messages after a timestamp (ISO-8601). Chronological. */
    @GetMapping("/since")
    public List<ChannelMessage> since(
            @RequestParam(defaultValue = "user-1") String userId,
            @RequestParam(defaultValue = "") String afterTs,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "daily") String threadId
    ) {
        return channelService.listSince(userId, threadId, afterTs, limit);
    }

    @GetMapping
    public List<ChannelMessage> list(
            @RequestParam(defaultValue = "user-1") String userId,
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(defaultValue = "daily") String threadId
    ) {
        return channelService.list(userId, threadId, limit);
    }

    /** Manual / test: post a companion chat bubble as agent. */
    @PostMapping("/say")
    public ChannelMessage say(@RequestBody Map<String, Object> body) {
        String userId = String.valueOf(body.getOrDefault("userId", "user-1"));
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = body.get("payload") instanceof Map
                ? (Map<String, Object>) body.get("payload")
                : Map.of("source", "api");
        return channelService.sendCompanionChat(userId, text, payload);
    }

    @GetMapping("/pending")
    public List<ChannelMessage> pending(@RequestParam(defaultValue = "user-1") String userId) {
        return channelService.listPending(userId);
    }

    @PostMapping("/action")
    public ChannelMessage action(@RequestBody Map<String, Object> body) {
        String userId = String.valueOf(body.getOrDefault("userId", "user-1"));
        String id = String.valueOf(body.get("id"));
        String status = body.get("status") == null ? "done" : String.valueOf(body.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> patch = body.get("payload") instanceof Map
                ? (Map<String, Object>) body.get("payload")
                : null;
        return channelService.updateStatus(userId, id, status, patch);
    }
}
