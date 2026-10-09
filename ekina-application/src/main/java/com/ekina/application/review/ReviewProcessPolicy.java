package com.ekina.application.review;

import java.util.*;

/** Conservative routing from independent, structured audits; never guesses scientific acceptance from prose. */
final class ReviewProcessPolicy {
    static List<Map<?,?>> repairs(Map<String,Object> audit) {
        if(!(audit.get("blockingIssues") instanceof List<?> issues)||issues.isEmpty()
                ||!(audit.get("repairItems") instanceof List<?> items)||items.size()!=issues.size())return List.of();
        List<Map<?,?>> result=new ArrayList<>();Set<Object> seen=new HashSet<>();
        for(Object raw:items) {
            if(!(raw instanceof Map<?,?> item)||!issues.contains(item.get("issue"))||!seen.add(item.get("issue"))
                    ||!Set.of("research","figures","delivery").contains(item.get("stage"))
                    ||!(item.get("target") instanceof String target)||target.isBlank())return List.of();
            result.add(item);
        }
        return result;
    }
    static String repairStage(Map<String,Object> audit) {
        var items=repairs(audit);
        return !items.isEmpty()&&items.stream().allMatch(i->"figures".equals(i.get("stage")))?"figures":"draft";
    }
    static boolean reusable(Map<String,Object> audit, List<Map<String,Object>> sources) {
        if(!"pass".equals(audit.get("researchVerdict"))||!(audit.get("researchBlockingIssues") instanceof List<?> blockers)||!blockers.isEmpty()
                ||!ReviewCampaignService.auditSourceChecksMatch(audit,sources))return false;
        if(!(audit.get("blockingIssues") instanceof List<?> issues))return false;
        if(!issues.isEmpty()&&(repairs(audit).size()!=issues.size()||repairs(audit).stream().anyMatch(i->"research".equals(i.get("stage")))))return false;
        if(!(audit.get("verifiedFindings") instanceof List<?> findings)||findings.isEmpty())return false;
        var checked=(List<?>)audit.get("checkedSources");
        for(Object raw:findings) {
            if(!(raw instanceof Map<?,?> f)||!(f.get("claim") instanceof String claim)||claim.isBlank()
                    ||!(f.get("sourceIds") instanceof List<?> ids)||ids.isEmpty()||!checked.containsAll(ids)
                    ||!(f.get("locator") instanceof String loc)||loc.isBlank())return false;
        }
        return true;
    }
    static Map<String,Object> advise(Map<String,Object> audit,List<?> history) {
        var items=repairs(audit);Map<String,Long> repeats=new LinkedHashMap<>();
        if(items.isEmpty()&&audit.get("blockingIssues") instanceof List<?> issues) {
            items=new ArrayList<>();for(Object issue:issues)if(issue instanceof String text)items.add(Map.of("stage","research","target",text,"issue",text));
        }
        for(var item:items) {
            String key=item.get("stage")+":"+item.get("target");long count=1;
            for(Object raw:history)if(raw instanceof Map<?,?> h&&h.get("processAdvice") instanceof Map<?,?> advice
                    &&advice.get("issueKeys") instanceof List<?> keys&&keys.contains(key))count++;
            repeats.put(key,count);
        }
        boolean repeated=repeats.values().stream().anyMatch(n->n>1);
        return Map.of("stage",repairStage(audit),"issueKeys",new ArrayList<>(repeats.keySet()),"occurrences",repeats,
            "strategy",repeated?"change-method-at-failing-target":"repair-only-open-issues",
            "briefing",repeated?"同一阶段/目标重复出现问题：先比较旧修复与当前输入，确认文件实际传递；只修失败目标，换布局或拆成更小步骤，保留已核查内容。":"仅解决列出的未关闭问题，分别交给对应阶段；禁止为排版问题重新检索全部资料。",
            "repairItems",items);
    }
}
