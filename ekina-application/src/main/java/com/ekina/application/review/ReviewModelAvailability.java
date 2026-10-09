package com.ekina.application.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

final class ReviewModelAvailability {
    static final List<String> MODELS=List.of("opencode-go/gpt-6-luna","opencode-go/glm-5.3-flash");
    static boolean denied(Path events) {
        var json=new ObjectMapper();
        try(var lines=Files.lines(events)) {
            for(String line:(Iterable<String>)lines::iterator) {
                var event=json.readTree(line);
                if(!"error".equals(event.path("type").asText()))continue;
                var data=event.path("error").path("data");
                String message=data.path("message").asText("").toLowerCase(Locale.ROOT);
                if(data.path("statusCode").asInt()==403 && message.contains("does not have access to this model"))return true;
            }
        }catch(Exception ignored){}
        return false;
    }
    static String select(String preferred,Map<String,String> until,Instant now) {
        var candidates=new ArrayList<String>();if(MODELS.contains(preferred))candidates.add(preferred);
        for(String m:MODELS)if(!candidates.contains(m))candidates.add(m);
        for(String model:candidates)if(!until.containsKey(model)||!now.isBefore(Instant.parse(until.get(model))))return model;
        throw new IllegalArgumentException("已授权低价模型暂不可用，保留材料并等待访问恢复；未扩大模型或账户权限");
    }
}
