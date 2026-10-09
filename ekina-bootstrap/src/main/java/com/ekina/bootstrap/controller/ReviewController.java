package com.ekina.bootstrap.controller;

import com.ekina.application.review.ReviewCampaignService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    @Autowired private ReviewCampaignService reviews;
    @GetMapping public Object list(@RequestParam(defaultValue="user-1") String userId) { return reviews.list(userId); }
    @GetMapping("/{id}/topics/{topicId}/artifacts/{name}") public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> artifact(
            @PathVariable String id,@PathVariable String topicId,@PathVariable String name,@RequestParam(defaultValue="user-1") String userId) {
        var path=reviews.artifactPath(userId,id,topicId,name);
        return org.springframework.http.ResponseEntity.ok().header("X-Content-Type-Options","nosniff")
                .header("Content-Security-Policy","default-src 'none'; sandbox")
                .contentType(org.springframework.http.MediaType.parseMediaType(name.endsWith(".png")?"image/png":name.endsWith(".svg")?"image/svg+xml":"text/plain;charset=UTF-8"))
                .body(new org.springframework.core.io.FileSystemResource(path));
    }
    @PostMapping(value="/{id}/topics/{topicId}/fulltext-import",consumes="multipart/form-data") public Object importFullText(
        @PathVariable String id,@PathVariable String topicId,@RequestParam(defaultValue="user-1") String userId,@RequestParam String sourceId,@RequestParam String sourceUrl,@RequestParam org.springframework.web.multipart.MultipartFile file)throws Exception {
        return reviews.importFullText(userId,id,topicId,sourceId,file.getBytes(),sourceUrl);
    }
    @PostMapping public Object start(@RequestBody Map<String,Object> body) {
        return reviews.start(String.valueOf(body.getOrDefault("userId","user-1")),String.valueOf(body.getOrDefault("request","")));
    }
    @PostMapping("/{id}/pause") public Object pause(@PathVariable String id,@RequestBody Map<String,Object> body) {
        return reviews.setPaused(String.valueOf(body.getOrDefault("userId","user-1")),id,Boolean.TRUE.equals(body.get("paused")));
    }
    @PostMapping("/{id}/restart-from-fulltext") public Object restartFromFullText(@PathVariable String id,@RequestBody Map<String,Object> body) {
        if(!Boolean.TRUE.equals(body.get("userAuthorized")))throw new IllegalArgumentException("作废旧稿并重建需用户明确要求");
        return reviews.restartFromFullText(String.valueOf(body.getOrDefault("userId","user-1")),id,String.valueOf(body.getOrDefault("requestId","")));
    }
    @PostMapping("/{id}/paper-figures") public Object paperFigures(@PathVariable String id,@RequestBody Map<String,Object> body) {
        return reviews.enablePaperFigures(String.valueOf(body.getOrDefault("userId","user-1")),id,String.valueOf(body.getOrDefault("topicId","")),String.valueOf(body.getOrDefault("requestId","")));
    }
    @PostMapping("/{id}/illustrated-edition") public Object illustratedEdition(@PathVariable String id,@RequestBody Map<String,Object> body) {
        if(!Boolean.TRUE.equals(body.get("userAuthorized")))throw new IllegalArgumentException("更新交付要求需用户明确提出");
        return reviews.illustratedEdition(String.valueOf(body.getOrDefault("userId","user-1")),id,
                String.valueOf(body.getOrDefault("requestId","")),String.valueOf(body.getOrDefault("reason","")),String.valueOf(body.getOrDefault("referenceArtifactId","")));
    }
    @PostMapping("/{id}/retry") public Object retry(@PathVariable String id,@RequestBody Map<String,Object> body) {
        return reviews.retry(String.valueOf(body.getOrDefault("userId","user-1")),id,String.valueOf(body.getOrDefault("topicId","")),String.valueOf(body.getOrDefault("stage","draft")));
    }
    @PostMapping("/{id}/continuous-completion") public Object continuousCompletion(@PathVariable String id,@RequestBody Map<String,Object> body) {
        if(!Boolean.TRUE.equals(body.get("userAuthorized")))throw new IllegalArgumentException("持续完成需要用户授权");
        return reviews.setContinuousCompletion(String.valueOf(body.getOrDefault("userId","user-1")),id,Boolean.TRUE.equals(body.get("enabled")));
    }
    @PostMapping("/{id}/automatic-runtime-recovery") public Object automaticRuntimeRecovery(@PathVariable String id,@RequestBody Map<String,Object> body) {
        if(!Boolean.TRUE.equals(body.get("userAuthorized")))throw new IllegalArgumentException("更改自动恢复需要用户授权");
        return reviews.setAutomaticRuntimeRecovery(String.valueOf(body.getOrDefault("userId","user-1")),id,Boolean.TRUE.equals(body.get("enabled")));
    }
    @PostMapping("/{id}/automatic-revision") public Object automaticRevision(@PathVariable String id,@RequestBody Map<String,Object> body) {
        if(!Boolean.TRUE.equals(body.get("userAuthorized")))throw new IllegalArgumentException("更改自动修订需要用户授权");
        return reviews.setAutomaticRevision(String.valueOf(body.getOrDefault("userId","user-1")),id,Boolean.TRUE.equals(body.get("enabled")));
    }
    @PostMapping("/{id}/autonomous-continuation") public Object autonomousContinuation(@PathVariable String id,@RequestBody Map<String,Object> body) {
        if(!Boolean.TRUE.equals(body.get("userAuthorized")))throw new IllegalArgumentException("自主接续需要用户明确授权");
        if(!(body.get("topicIds") instanceof List<?> ids)||ids.stream().anyMatch(v->!(v instanceof String)))throw new IllegalArgumentException("需要明确课题范围");
        return reviews.autonomousContinuation(String.valueOf(body.getOrDefault("userId","user-1")),id,
                String.valueOf(body.getOrDefault("requestId","")),String.valueOf(body.getOrDefault("afterTopicId","")),
                ids.stream().map(String.class::cast).toList(),Boolean.TRUE.equals(body.get("enabled")),String.valueOf(body.getOrDefault("reason","")));
    }
    @PostMapping("/{id}/figure-feedback") public Object figureFeedback(@PathVariable String id,@RequestBody Map<String,Object> body) {
        return reviews.figureFeedback(String.valueOf(body.getOrDefault("userId","user-1")),id,String.valueOf(body.getOrDefault("topicId","")),String.valueOf(body.getOrDefault("requestId","")),String.valueOf(body.getOrDefault("reason","")));
    }
    @PostMapping("/{id}/reuse-validated-draft") public Object reuseValidatedDraft(@PathVariable String id,@RequestBody Map<String,Object> body) {
        return reviews.reuseValidatedDraft(String.valueOf(body.getOrDefault("userId","user-1")),id,String.valueOf(body.getOrDefault("topicId","")),String.valueOf(body.getOrDefault("taskId","")));
    }
    @PostMapping("/{id}/recheck") public Object recheck(@PathVariable String id,@RequestBody Map<String,Object> body) {
        return reviews.recheck(String.valueOf(body.getOrDefault("userId","user-1")),id,String.valueOf(body.getOrDefault("topicId","")));
    }
    @PostMapping("/{id}/authorize-retry") public Object authorizeRetry(@PathVariable String id,@RequestBody Map<String,Object> body) {
        if(!Boolean.TRUE.equals(body.get("userAuthorized")))throw new IllegalArgumentException("追加恢复需要用户明确授权");
        return reviews.authorizeRetry(String.valueOf(body.getOrDefault("userId","user-1")),id,
                String.valueOf(body.getOrDefault("topicId","")),String.valueOf(body.getOrDefault("requestId","")),String.valueOf(body.getOrDefault("reason","")));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object invalid(IllegalArgumentException e){return Map.of("error",e.getMessage());}
    @PostMapping("/{id}/repair-format") public Object repairFormat(@PathVariable String id,@RequestBody Map<String,Object> body) {
        return reviews.repairSourceFormat(String.valueOf(body.getOrDefault("userId","user-1")),id,String.valueOf(body.getOrDefault("topicId","")));
    }
    @PostMapping("/{id}/dependencies") public Object dependencies(@PathVariable String id,@RequestBody Map<String,Object> body) {
        if(!(body.get("dependsOn") instanceof List<?> deps)||deps.stream().anyMatch(v->!(v instanceof String)))throw new IllegalArgumentException("dependsOn必须为编号数组");
        return reviews.updateDependencies(String.valueOf(body.getOrDefault("userId","user-1")),id,String.valueOf(body.getOrDefault("topicId","")),deps.stream().map(String.class::cast).toList(),String.valueOf(body.getOrDefault("requestId","")),String.valueOf(body.getOrDefault("reason","")));
    }
}
