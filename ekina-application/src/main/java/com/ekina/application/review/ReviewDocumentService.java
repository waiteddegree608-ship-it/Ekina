package com.ekina.application.review;

import com.ekina.application.home.HomeQuotaService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Local document versions are independent of research acceptance and model dispatch. */
@Service
public class ReviewDocumentService {
    @Autowired private HomeQuotaService home;
    @Value("${ekina.workspace-dir:.}") private String workspaceDir;
    @Value("${ekina.reviews.python:python}") private String python;
    private final ObjectMapper json=new ObjectMapper();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"review-typesetting");t.setDaemon(true);return t;});
    private final Set<String> busy=ConcurrentHashMap.newKeySet();
    private final Map<String,String> errors=new ConcurrentHashMap<>();
    private final Map<String,Long> attempts=new ConcurrentHashMap<>();

    public static void identifier(String value) {
        if(value==null||!value.matches("[a-zA-Z0-9_-]{1,100}"))throw new IllegalArgumentException("无效文档编号");
    }
    private Path root(String campaign,String topic){identifier(campaign);identifier(topic);return home.homePath().resolve("workspace/review-documents").resolve(campaign+"_"+topic);}
    private String key(String campaign,String topic){return campaign+"/"+topic;}
    private Map<String,Object> read(Path file) throws Exception {return Files.isRegularFile(file)?json.readValue(Files.readString(file),new TypeReference<>(){}):new LinkedHashMap<>(Map.of("versions",List.of()));}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> campaigns() throws Exception {
        Path file=home.homePath().resolve("state/reviews.json");
        return Files.exists(file)?json.readValue(Files.readString(file),new TypeReference<>(){}):List.of();
    }
    @SuppressWarnings("unchecked") private Map<String,Object> ownedTopic(String user,String campaign,String topic) {
        identifier(campaign);identifier(topic);
        try {for(var c:campaigns())if(campaign.equals(c.get("id"))&&user.equals(c.get("userId")))
            for(var t:(List<Map<String,Object>>)c.get("topics"))if(topic.equals(t.get("id")))return t;
        }catch(Exception e){throw new IllegalStateException("文档队列读取失败",e);}
        throw new IllegalArgumentException("课题不存在");
    }
    public Map<String,Object> state(String user,String campaign,String topic) {
        ownedTopic(user,campaign,topic);
        try {var state=read(root(campaign,topic).resolve("index.json"));state.put("busy",busy.contains(key(campaign,topic)));
            if(state.get("versions") instanceof List<?> versions)for(Object value:versions)if(value instanceof Map<?,?> raw) {
                @SuppressWarnings("unchecked") var version=(Map<String,Object>)raw;
                if(!version.containsKey("figureCount")) {
                    Path source=root(campaign,topic).resolve("versions").resolve(version.get("id").toString()).resolve("main.tex");
                    version.put("figureCount",Files.isRegularFile(source)?figureCount(Files.readString(source)):0);
                }
            }
            state.put("localPath",root(campaign,topic).toString());state.put("error",errors.getOrDefault(key(campaign,topic),""));return state;
        }catch(Exception e){throw new IllegalStateException("文档版本读取失败",e);}
    }
    static long figureCount(String tex){return java.util.regex.Pattern.compile("\\\\includegraphics(?:\\[[^\\]]*\\])?\\{figure-[a-z0-9-]+\\.png\\}").matcher(tex).results().count();}
    static String documentStatus(Object status){return "draft_ready".equals(status)?"draft_ready":"in_progress";}
    public Map<String,Object> sync(String user,String campaign,String topic) {enqueue(campaign,ownedTopic(user,campaign,topic),true);return state(user,campaign,topic);}
    public Path file(String user,String campaign,String topic,String version,String name) {
        ownedTopic(user,campaign,topic);identifier(version);
        if(!List.of("main.tex","main.pdf","compile.log").contains(name)&&!name.matches("page-[0-9]{3}\\.png"))throw new IllegalArgumentException("未知文稿文件");
        Path p=root(campaign,topic).resolve("versions").resolve(version).resolve(name);
        if(!Files.isRegularFile(p))throw new IllegalArgumentException("该版本文件尚未生成");return p;
    }
    @SuppressWarnings("unchecked") public synchronized Map<String,Object> save(String user,String campaign,String topic,String basedOn,String expectedHead,String source,String message) {
        var t=ownedTopic(user,campaign,topic);identifier(basedOn);
        if(source==null||source.isBlank()||source.length()>500000)throw new IllegalArgumentException("源码为空或过长");
        var state=state(user,campaign,topic);var versions=(List<Map<String,Object>>)state.get("versions");
        if(versions.isEmpty()||!expectedHead.equals(versions.getFirst().get("id")))throw new IllegalArgumentException("已有新版本，请先刷新；编辑内容未丢弃");
        if(versions.stream().noneMatch(v->basedOn.equals(v.get("id"))))throw new IllegalArgumentException("基准版本不存在");
        Map<String,Object> request=new LinkedHashMap<>(Map.of("mode","manual","documentRoot",root(campaign,topic).toString(),"bundle",file(user,campaign,topic,basedOn,"main.tex").getParent().toString(),"source",source,"title",t.get("title"),"basedOn",basedOn,"message",message==null?"":message));
        submit(campaign,topic,request);return state(user,campaign,topic);
    }
    private void submit(String campaign,String topic,Map<String,Object> request) {
        String key=key(campaign,topic);
        if(!busy.add(key))throw new IllegalArgumentException("该文稿正在编译或同步，请稍后保存");
        errors.remove(key);
        executor.submit(()->{Path req=null;try{
            Path folder=root(campaign,topic);Files.createDirectories(folder);
            req=Files.createTempFile(folder,"request-",".json");Files.writeString(req,json.writeValueAsString(request));
            Process p=new ProcessBuilder(python,Path.of(workspaceDir,"scripts/review_latex.py").toString(),req.toString())
                    .redirectErrorStream(true).redirectOutput(folder.resolve("typesetting.log").toFile()).start();
            if(!p.waitFor(150,TimeUnit.SECONDS)){p.descendants().forEach(ProcessHandle::destroy);p.destroyForcibly();throw new IllegalStateException("本地排版超时，历史版本保留");}
            if(p.exitValue()!=0)throw new IllegalStateException("本地排版程序失败，查看typesetting.log");
        }catch(Exception e){errors.put(key,String.valueOf(e.getMessage()));}
        finally{if(req!=null)try{Files.deleteIfExists(req);}catch(Exception ignored){}busy.remove(key);}});
    }
    private void enqueue(String campaign,Map<String,Object> topic,boolean explicit) {
        if("voided".equals(topic.get("status")))return;
        String id=topic.get("id").toString(),key=key(campaign,id);
        if(busy.contains(key))return;
        if(!explicit&&System.currentTimeMillis()-attempts.getOrDefault(key,0L)<60000)return;
        String artifact=ReviewPaperFigures.displayArtifact(topic);
        if(!artifact.matches("[a-zA-Z0-9_-]+"))return;
        Path bundle=home.homePath().resolve("workspace/opencode-deliverables").resolve(artifact);
        for(String n:List.of("review.md","sources.json","protocol.md","evidence.csv","gaps.md"))if(!Files.isRegularFile(bundle.resolve(n)))return;
        try {
            var digest=MessageDigest.getInstance("SHA-256");digest.update("latex-preview-v2".getBytes(StandardCharsets.UTF_8));
            try(var files=Files.list(bundle)){for(Path f:files.filter(Files::isRegularFile).sorted().toList()){
                String n=f.getFileName().toString();if(n.endsWith(".md")||n.endsWith(".json")||n.endsWith(".png")||n.endsWith(".csv")){digest.update(n.getBytes(StandardCharsets.UTF_8));digest.update(Files.readAllBytes(f));}
            }}
            // Runtime retries do not create new manuscript editions; acceptance still changes the notice.
            digest.update(documentStatus(topic.get("status")).getBytes(StandardCharsets.UTF_8));
            String signature=HexFormat.of().formatHex(digest.digest());
            var prior=read(root(campaign,id).resolve("index.json"));if(signature.equals(prior.get("lastAutoSignature")))return;
            attempts.put(key,System.currentTimeMillis());
            submit(campaign,id,new LinkedHashMap<>(Map.of("mode","auto","documentRoot",root(campaign,id).toString(),"bundle",bundle.toString(),"title",topic.get("title"),"researchStatus",topic.get("status"),"artifactId",artifact,"signature",signature)));
        }catch(Exception e){errors.put(key,String.valueOf(e.getMessage()));}
    }
    @Scheduled(fixedDelay=15000) @SuppressWarnings("unchecked") public void syncCompletedBundles() {
        try {for(var c:campaigns())for(var t:(List<Map<String,Object>>)c.get("topics"))enqueue(c.get("id").toString(),t,false);}
        catch(Exception ignored){} // An unavailable state file must not disturb running research workers.
    }
    @SuppressWarnings("unchecked") public Optional<Path> latestUserSource(String campaign,String topic) {
        try {for(var version:(List<Map<String,Object>>)read(root(campaign,topic).resolve("index.json")).get("versions"))
            if("用户编辑".equals(version.get("author")))return Optional.of(root(campaign,topic).resolve("versions").resolve(version.get("id").toString()).resolve("main.tex"));
        }catch(Exception ignored){}return Optional.empty();
    }
}
