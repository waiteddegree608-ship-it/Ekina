package com.ekina.bootstrap.controller;

import com.ekina.application.contact.AgentEmotionService;
import com.ekina.application.contact.ContactRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/contacts")
public class ContactController {

    @Autowired
    private ContactRegistry contactRegistry;

    @Autowired
    private AgentEmotionService agentEmotionService;

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(defaultValue = "user-1") String userId
    ) {
        return contactRegistry.listForApi(userId);
    }

    @GetMapping("/agent/emotion")
    public Map<String, Object> agentEmotion() {
        return agentEmotionService.get();
    }

    @PostMapping("/agent/emotion")
    public Map<String, Object> patchAgentEmotion(@RequestBody(required = false) Map<String, Object> body) {
        return agentEmotionService.update(body == null ? Map.of() : body);
    }
}
