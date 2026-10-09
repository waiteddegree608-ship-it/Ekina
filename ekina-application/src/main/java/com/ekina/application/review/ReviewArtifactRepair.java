package com.ekina.application.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.*;

/** Repairs serialization only: never substitutes research text or invents missing fields. */
final class ReviewArtifactRepair {
    record Result(String text,int escapedQuotes,List<Map<String,Object>> sources) {}
    static Result repairLocatorQuotes(String raw) throws Exception {
        ObjectMapper json=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        try { json.readValue(raw,new TypeReference<List<Map<String,Object>>>(){});
            throw new IllegalArgumentException("来源文件已经是合法JSON，无需格式恢复");
        } catch(com.fasterxml.jackson.core.JsonProcessingException expected) { /* narrow repair below */ }
        Matcher lines=Pattern.compile("(?m)^(\\h*\"locator\"\\h*:\\h*\")(.*)(\"\\h*,?\\h*\\r?$)").matcher(raw);
        StringBuilder repaired=new StringBuilder();int quotes=0;
        while(lines.find()) {
            String value=lines.group(2);
            if(Pattern.compile("\"\\s*[,}]").matcher(value).find())throw new IllegalArgumentException("字段边界存在歧义，不能自动修复");
            StringBuilder escaped=new StringBuilder();int slashes=0;
            for(char ch:value.toCharArray()) {
                if(ch=='"'&&slashes%2==0){escaped.append('\\');quotes++;}
                escaped.append(ch);slashes=ch=='\\'?slashes+1:0;
            }
            lines.appendReplacement(repaired,Matcher.quoteReplacement(lines.group(1)+escaped+lines.group(3)));
        }
        lines.appendTail(repaired);
        if(quotes==0)throw new IllegalArgumentException("不是可无损修复的locator引号错误，保留原文件");
        List<Map<String,Object>> sources=json.readValue(repaired.toString(),new TypeReference<>(){});
        ReviewCampaignService.validateSources(sources);
        return new Result(repaired.toString(),quotes,sources);
    }
}
