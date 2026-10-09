package com.ekina.bootstrap.controller;

import com.ekina.application.job.SshJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    @Autowired
    private SshJobService sshJobService;

    @PostMapping("/ssh")
    public Map<String, Object> ssh(@RequestBody Map<String, Object> body) {
        String userId = String.valueOf(body.getOrDefault("userId", "user-1"));
        String command = String.valueOf(body.getOrDefault("command", ""));
        return sshJobService.runRemote(userId, command);
    }
}
