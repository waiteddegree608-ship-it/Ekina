package com.ekina.application.review;
import java.util.*;
import java.nio.file.*;
import java.security.MessageDigest;

final class ReviewSourceValidation {
    static class Invalid extends IllegalArgumentException {
        final List<Map<String,Object>> issues;
        Invalid(List<Map<String,Object>> issues){super("SOURCE_SCHEMA: "+issues);this.issues=List.copyOf(issues);}
    }
    static String hash(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    static boolean external(Object url){return url instanceof String s&&s.matches("https?://[^\\s]+");}
    /** Stage verified snapshots under deliverables; never widen worker filesystem permissions. */
    static Map<String,Map<String,String>> stageSnapshots(Map<String,Map<String,String>> bindings,Path destination)throws Exception {
        Map<String,Map<String,String>> staged=new LinkedHashMap<>();
        for(var entry:bindings.entrySet()) {
            var binding=entry.getValue();String name=binding.getOrDefault("inputName","");
            if(!name.matches("[a-zA-Z0-9_-]+-(review\\.md|verified-findings\\.json)")||!entry.getKey().equals("inputs/"+name))
                throw new IllegalArgumentException("内部来源快照名称无效："+name);
            Path source=Path.of(binding.get("path"));
            if(!Files.isRegularFile(source)||Files.size(source)>4_000_000||!hash(source).equals(binding.get("sha256")))
                throw new IllegalArgumentException("内部来源快照已改变或不可用："+name);
            Files.createDirectories(destination);Path target=destination.resolve(name);
            Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING);
            if(!hash(target).equals(binding.get("sha256")))throw new IllegalArgumentException("内部来源传递哈希不一致："+name);
            var copy=new LinkedHashMap<>(binding);copy.put("path",target.toString());staged.put(entry.getKey(),copy);
        }
        return staged;
    }
    static void validate(List<Map<String,Object>> sources,Map<String,Map<String,String>> internal) {
        List<Map<String,Object>> errors=new ArrayList<>();Set<String> ids=new HashSet<>();int externalCount=0;
        for(var s:sources) {
            String id=String.valueOf(s.getOrDefault("id","<missing>"));
            if(id.isBlank()||id.length()>80||"<missing>".equals(id)||!ids.add(id))issue(errors,id,"id",id,"唯一非空编号，最长80字符");
            field(errors,id,s,"title",1000);field(errors,id,s,"locator",2000);
            if(!Set.of("metadata","abstract","full_text","paper_full_text","webpage_text","internal_report").contains(String.valueOf(s.get("readLevel"))))issue(errors,id,"readLevel",s.get("readLevel"),"metadata | abstract | paper_full_text | webpage_text | internal_report；旧full_text仅兼容历史记录");
            String url=String.valueOf(s.get("url"));if(url.length()>2000)issue(errors,id,"url",url.substring(0,100),"链接最长2000字符");
            if(external(url)) {externalCount++;if("internal".equals(s.get("kind")))issue(errors,id,"kind",s.get("kind"),"external，HTTP来源不能冒充内部成果");}
            else {
                var binding=internal.get(url);boolean valid=false;
                try{valid=binding!=null&&Files.isRegularFile(Path.of(binding.get("path")))&&hash(Path.of(binding.get("path"))).equals(binding.get("sha256"));}catch(Exception ignored){}
                if(!valid)issue(errors,id,"url",url,"HTTP(S)文献链接，或系统登记且哈希一致的inputs/前置成果；不能自行构造路径");
                else if("external".equals(s.get("kind")))issue(errors,id,"kind",s.get("kind"),"internal，内部报告不能当原始外部文献");
            }
        }
        if(externalCount<3)issue(errors,"*","sources",externalCount,"至少3条外部来源；内部成果不计入外部证据门槛");
        if(!errors.isEmpty())throw new Invalid(errors);
    }
    private static void field(List<Map<String,Object>> errors,String id,Map<String,Object> s,String name,int max){if(!(s.get(name) instanceof String v)||v.isBlank()||v.length()>max)issue(errors,id,name,s.get(name),"非空文本，最长"+max+"字符");}
    private static void issue(List<Map<String,Object>> errors,String id,String field,Object actual,String expected){errors.add(Map.of("code","SOURCE_SCHEMA","file","sources.json","sourceId",id,"field",field,"actual",String.valueOf(actual),"expected",expected,"stage","draft"));}
}
