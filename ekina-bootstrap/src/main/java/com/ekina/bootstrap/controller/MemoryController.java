package com.ekina.bootstrap.controller;

import com.ekina.domain.repository.KnowledgeChunkDomainRepository;
import com.ekina.domain.repository.UserMemoryDomainRepository;
import com.ekina.domain.repository.UserProfileDomainRepository;
import com.ekina.domain.user.UserMemory;
import com.ekina.domain.user.UserProfile;
import com.ekina.domain.rag.KnowledgeChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    @Autowired
    private UserProfileDomainRepository userProfileRepository;

    @Autowired
    private UserMemoryDomainRepository userMemoryRepository;

    @Autowired
    private KnowledgeChunkDomainRepository knowledgeChunkRepository;

    public static class ProfileSaveRequest {
        private String userId;
        private Integer age;
        private String gender;
        private String job;
        private String location;
        private String habits;
        private String birthday;

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }
        public String getGender() { return gender; }
        public void setGender(String gender) { this.gender = gender; }
        public String getJob() { return job; }
        public void setJob(String job) { this.job = job; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public String getHabits() { return habits; }
        public void setHabits(String habits) { this.habits = habits; }
        public String getBirthday() { return birthday; }
        public void setBirthday(String birthday) { this.birthday = birthday; }
    }

    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(@RequestParam("userId") String userId) {
        Optional<UserProfile> profileOpt = userProfileRepository.findByUserId(userId);
        if (profileOpt.isPresent()) {
            return ResponseEntity.ok(profileOpt.get());
        }
        return ResponseEntity.ok(Map.of("userId", userId));
    }

    @PostMapping("/profile")
    public ResponseEntity<?> saveProfile(@RequestBody ProfileSaveRequest request) {
        Optional<UserProfile> existingOpt = userProfileRepository.findByUserId(request.getUserId());
        UserProfile profile = existingOpt.orElseGet(() -> UserProfile.builder()
                .userId(request.getUserId())
                .build());

        profile.setAge(request.getAge());
        profile.setGender(request.getGender());
        profile.setJob(request.getJob());
        profile.setLocation(request.getLocation());
        profile.setHabits(request.getHabits());
        profile.setBirthday(request.getBirthday());
        profile.setUpdatedAt(LocalDateTime.now());

        UserProfile saved = userProfileRepository.save(profile);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/memories")
    public ResponseEntity<List<UserMemory>> getMemories(@RequestParam("userId") String userId) {
        return ResponseEntity.ok(userMemoryRepository.findAllByUserId(userId));
    }

    @DeleteMapping("/memories/{id}")
    public ResponseEntity<?> deleteMemory(@PathVariable("id") String id) {
        try {
            userMemoryRepository.deleteById(UUID.fromString(id));
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Memory deleted"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "message", e.getMessage()));
        }
    }

    @GetMapping("/knowledge")
    public ResponseEntity<List<KnowledgeChunk>> getKnowledge() {
        return ResponseEntity.ok(knowledgeChunkRepository.findAll());
    }

    @DeleteMapping("/knowledge/{id}")
    public ResponseEntity<?> deleteKnowledge(@PathVariable("id") String id) {
        try {
            knowledgeChunkRepository.deleteById(UUID.fromString(id));
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Knowledge chunk deleted"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "message", e.getMessage()));
        }
    }
}
