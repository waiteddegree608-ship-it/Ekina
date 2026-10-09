package com.ekina.bootstrap.controller;

import com.ekina.application.channel.ChannelMessage;
import com.ekina.application.reminder.ReminderRule;
import com.ekina.application.reminder.ReminderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reminders")
public class ReminderController {

    @Autowired
    private ReminderService reminderService;

    @GetMapping
    public List<ReminderRule> list(@RequestParam(required = false) String userId) {
        return reminderService.listRules(userId);
    }

    @PostMapping
    public ReminderRule upsert(@RequestBody ReminderRule rule) {
        return reminderService.upsert(rule);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        boolean ok = reminderService.delete(id);
        return Map.of("deleted", ok, "id", id);
    }

    @PostMapping("/ack")
    public ChannelMessage ack(@RequestBody Map<String, String> body) {
        String userId = body.getOrDefault("userId", "user-1");
        String messageId = body.get("messageId");
        String action = body.getOrDefault("action", "ack");
        return reminderService.ack(userId, messageId, action);
    }
}
