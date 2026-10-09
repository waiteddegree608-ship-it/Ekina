package com.ekina.bootstrap.controller;

import com.ekina.application.review.ReviewDocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.*;
import java.util.*;

@RestController
@RequestMapping("/api/reviews/{campaign}/topics/{topic}/document")
public class ReviewDocumentController {
    @Autowired private ReviewDocumentService documents;
    @GetMapping public Object state(@PathVariable String campaign,@PathVariable String topic,@RequestParam(defaultValue="user-1") String userId){return documents.state(userId,campaign,topic);}
    @PostMapping("/sync") public Object sync(@PathVariable String campaign,@PathVariable String topic,@RequestBody Map<String,Object> body){return documents.sync(user(body),campaign,topic);}
    @PostMapping("/save") public Object save(@PathVariable String campaign,@PathVariable String topic,@RequestBody Map<String,Object> body){return documents.save(user(body),campaign,topic,str(body,"basedOn"),str(body,"expectedHead"),str(body,"source"),str(body,"message"));}
    @GetMapping("/versions/{version}/{name}") public ResponseEntity<Resource> file(@PathVariable String campaign,@PathVariable String topic,@PathVariable String version,@PathVariable String name,@RequestParam(defaultValue="user-1") String userId){
        var path=documents.file(userId,campaign,topic,version,name);
        return ResponseEntity.ok().header("X-Content-Type-Options","nosniff").header("Cache-Control","no-store")
            .contentType(MediaType.parseMediaType(name.endsWith(".pdf")?"application/pdf":name.endsWith(".png")?"image/png":"text/plain;charset=utf-8"))
            .body(new FileSystemResource(path));
    }
    private String user(Map<String,Object> b){return String.valueOf(b.getOrDefault("userId","user-1"));}
    private String str(Map<String,Object> b,String k){return String.valueOf(b.getOrDefault(k,""));}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST) public Object invalid(IllegalArgumentException e){return Map.of("error",e.getMessage());}
}
