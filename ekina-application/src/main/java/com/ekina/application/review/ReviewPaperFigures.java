package com.ekina.application.review;

import com.ekina.application.home.HomeQuotaService;
import com.ekina.application.multiagent.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Optional post-acceptance original-paper illustration track. Never edits the accepted manuscript. */
@Service
public class ReviewPaperFigures {
    @Autowired private HomeQuotaService home;
    @Autowired private MultiAgentOrchestrator workers;
    @Value("${ekina.workspace-dir:.}") private String workspace;
    @Value("${ekina.reviews.python:python}") private String python;
    private final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ExecutorService executor=Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"paper-figure-render");t.setDaemon(true);return t;});
    private final Map<String,Future<?>> jobs=new HashMap<>();
    @SuppressWarnings("unchecked") static Map<String,Object> state(Map<String,Object> t){return t.get("paperFigures") instanceof Map<?,?> m?(Map<String,Object>)m:null;}
    public static boolean active(Map<String,Object> t){var s=state(t);return s!=null&&Set.of("planning","fetching","selecting","rendering","checking","assembling").contains(s.get("phase"));}
    public static String displayArtifact(Map<String,Object> t) {
        String base=String.valueOf(t.getOrDefault("draftArtifactId",t.get("draftTaskId")));var s=state(t);
        return s!=null&&"ready".equals(s.get("phase"))&&base.equals(s.get("baseArtifactId"))?String.valueOf(s.get("artifactId")):base;
    }
    Path folder(String id){if(!id.matches("[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("Invalid artifact ID");return home.homePath().resolve("workspace/opencode-deliverables").resolve(id);}
    private Map<String,Object> object(Path p)throws Exception{return json.readValue(Files.readString(p),new TypeReference<>(){});}
    private List<Map<String,Object>> array(Path p)throws Exception{return json.readValue(Files.readString(p),new TypeReference<>(){});}
    private void write(Path p,Object data)throws Exception{Files.writeString(p,json.writerWithDefaultPrettyPrinter().writeValueAsString(data));}
    public void enable(Map<String,Object> t,String requestId) {
        if(state(t)!=null&&Boolean.TRUE.equals(state(t).get("enabled")))return;
        t.put("paperFigures",new LinkedHashMap<>(Map.of("enabled",true,"phase","waiting_base","requestId",requestId,"enabledAt",Instant.now().toString(),"history",new ArrayList<>())));
    }
    private void initialize(Map<String,Object> t,Map<String,Object> s)throws Exception {
        String base=String.valueOf(t.getOrDefault("draftArtifactId",t.get("draftTaskId")));
        String id="paper_"+UUID.randomUUID().toString().replace("-","");Path work=folder(id);Files.createDirectories(work);
        Map<String,String> hashes=new LinkedHashMap<>();try(var files=Files.list(folder(base))){for(var f:files.filter(Files::isRegularFile).toList())hashes.put(f.getFileName().toString(),ReviewSourceValidation.hash(f));}
        write(work.resolve("base-hashes.json"),hashes);s.put("workArtifactId",id);s.put("baseArtifactId",base);s.put("phase","plan_pending");
    }
    private void checkBase(Map<String,Object> t,Map<String,Object> s)throws Exception {
        String current=String.valueOf(t.getOrDefault("draftArtifactId",t.get("draftTaskId")));
        if(!current.equals(s.get("baseArtifactId")))throw new IllegalArgumentException("原稿已更新，旧补图运行保留；等待新成稿重新建立快照");
        var hashes=object(folder(s.get("workArtifactId").toString()).resolve("base-hashes.json"));
        for(var e:hashes.entrySet())if(!ReviewSourceValidation.hash(folder(current).resolve(e.getKey())).equals(e.getValue()))throw new IllegalArgumentException("原稿/原概括图哈希已变化，不能合并");
    }
    public void settle(Map<String,Object> t) {
        var s=state(t);if(s==null||!active(t))return;
        String phase=s.get("phase").toString();
        try {
            Path work=folder(s.get("workArtifactId").toString());
            if(Set.of("fetching","rendering","assembling").contains(phase)) {
                Future<?> future=jobs.get(work.toString());
                if(future==null){s.put("phase",Map.of("fetching","fetch_pending","rendering","render_pending","assembling","assemble_pending").get(phase));return;}
                if(!future.isDone())return;future.get();jobs.remove(work.toString());checkBase(t,s);
                if("fetching".equals(phase)) {
                    var catalog=object(work.resolve("paper-catalog.json"));
                    if(((List<?>)catalog.get("papers")).isEmpty()){s.put("phase","no_sources");s.put("gaps",catalog.get("gaps"));}
                    else s.put("phase","select_pending");
                }else if("rendering".equals(phase))s.put("phase","check_pending");
                else {s.put("phase","ready");s.put("artifactId","supplemented_"+s.get("auditTaskId"));s.put("figureCount",array(work.resolve("paper-figures.json")).size());s.put("completedAt",Instant.now().toString());}
                return;
            }
            var task=workers.get(String.valueOf(s.get("taskId")));if(task.isEmpty()||Set.of("running","queued").contains(task.get().getStatus()))return;
            checkBase(t,s);
            var done=task.get();
            if(!"succeeded".equals(done.getStatus()))throw new IllegalArgumentException("原图补充执行未成功；已保留该阶段材料："+done.getId());
            Path output=folder(done.getId());
            if("planning".equals(phase)) {var plan=object(output.resolve("paper-plan.json"));write(work.resolve("paper-plan.json"),plan);s.put("phase","fetch_pending");}
            else if("selecting".equals(phase)) {
                var selection=object(output.resolve("paper-selections.json"));
                if(selection.get("figures") instanceof List<?> figures&&figures.isEmpty()&&selection.get("gaps") instanceof List<?> gaps&&!gaps.isEmpty()) {s.put("phase","no_sources");s.put("gaps",gaps);return;}
                write(work.resolve("paper-selections.json"),selection);s.put("phase","render_pending");
            } else {
                var audit=object(output.resolve("paper-audit.json"));s.put("audit",audit);s.put("auditTaskId",done.getId());
                if(!auditPasses(audit,array(work.resolve("paper-figures.json")),Path.of(done.getMeta().get("jobDir").toString()).resolve("events.jsonl"))) {
                    write(work.resolve("paper-feedback.json"),audit);throw new IllegalArgumentException("原图身份/裁剪/对应关系核查未通过，保持原稿不变");
                }
                s.put("phase","assemble_pending");
            }
        }catch(Exception e){fail(s,phase,e);}
    }
    private void fail(Map<String,Object> s,String phase,Exception e) {
        String resume=switch(phase){case "planning"->"plan_pending";case "fetching"->"fetch_pending";case "selecting","rendering"->"select_pending";case "checking"->"select_pending";case "assembling"->"assemble_pending";default->phase;};
        // Execution failure of the checker resumes checking; scientific revise goes back to selection.
        if("checking".equals(phase)&&e.getMessage()!=null&&e.getMessage().contains("执行未成功"))resume="check_pending";
        int n=s.get("attempts") instanceof Number v?v.intValue()+1:1;s.put("attempts",n);
        s.put("lastError",String.valueOf(e.getMessage()));s.put("resumePhase",resume);s.put("retryAt",Instant.now().plusSeconds(Math.min(900,30L*(1L<<Math.min(n,5)))).toString());s.put("phase","waiting");
        @SuppressWarnings("unchecked") var history=(List<Object>)s.get("history");history.add(Map.of("phase",phase,"taskId",String.valueOf(s.get("taskId")),"error",String.valueOf(e.getMessage()),"at",Instant.now().toString()));
    }
    /** Called by the campaign scheduler only when its normal worker seat is available. */
    public boolean advance(Map<String,Object> c,Map<String,Object> t,String model) {
        var s=state(t);if(s==null||!Boolean.TRUE.equals(s.get("enabled"))||!"draft_ready".equals(t.get("status"))||active(t))return false;
        String phase=String.valueOf(s.get("phase"));
        try {
            if(s.containsKey("baseArtifactId"))try{checkBase(t,s);}catch(Exception changed){
                @SuppressWarnings("unchecked") var history=(List<Object>)s.get("history");
                history.add(Map.of("phase",phase,"reason","accepted_base_changed","baseArtifactId",s.get("baseArtifactId"),"workArtifactId",s.get("workArtifactId"),"at",Instant.now().toString()));
                for(String key:List.of("baseArtifactId","workArtifactId","artifactId","taskId","auditTaskId","audit","lastError","resumePhase","retryAt","figureCount"))s.remove(key);
                s.put("phase","waiting_base");phase="waiting_base";
            }
            if(Set.of("ready","no_sources").contains(phase))return false;
            if("waiting_base".equals(phase)){initialize(t,s);phase=s.get("phase").toString();}
            if("waiting".equals(phase)){if(Instant.now().isBefore(Instant.parse(s.get("retryAt").toString())))return false;phase=s.get("resumePhase").toString();s.put("phase",phase);}
            checkBase(t,s);Path base=folder(s.get("baseArtifactId").toString()),work=folder(s.get("workArtifactId").toString());
            if(Set.of("fetch_pending","render_pending","assemble_pending").contains(phase)) {
                String operation=Map.of("fetch_pending","acquire","render_pending","render","assemble_pending","assemble").get(phase);
                var request=new LinkedHashMap<String,Object>(Map.of("operation",operation,"base",base.toString(),"work",work.toString(),"plan",work.resolve("paper-plan.json").toString(),"selection",work.resolve("paper-selections.json").toString(),"auditTask",String.valueOf(s.get("auditTaskId")),"output",folder("supplemented_"+s.get("auditTaskId")).toString()));
                Path req=work.resolve("request-"+operation+".json");write(req,request);
                jobs.put(work.toString(),executor.submit(()->{try{
                    var p=new ProcessBuilder(python,Path.of(workspace,"scripts/review_paper_figures.py").toString(),req.toString()).redirectErrorStream(true).redirectOutput(work.resolve(operation+".log").toFile()).start();
                    if(!p.waitFor(240,TimeUnit.SECONDS)){p.destroyForcibly();throw new IllegalStateException("原图处理超时，已下载PDF保留");}
                    if(p.exitValue()!=0){String log=Files.readString(work.resolve(operation+".log"));throw new IllegalStateException("原图处理失败："+log.substring(Math.max(0,log.length()-1800)));}
                }catch(Exception e){throw new CompletionException(e);}}));
                s.put("phase",Map.of("fetch_pending","fetching","render_pending","rendering","assemble_pending","assembling").get(phase));return true;
            }
            Map<String,String> inputs=new LinkedHashMap<>();for(String name:List.of("review.md","sources.json","evidence.csv","figures.json"))if(Files.isRegularFile(base.resolve(name)))inputs.put(name,base.resolve(name).toString());
            if(Files.isRegularFile(work.resolve("paper-feedback.json")))inputs.put("paper-feedback.json",work.resolve("paper-feedback.json").toString());
            if(!"plan_pending".equals(phase))try(var files=Files.list(work)){for(Path file:files.filter(Files::isRegularFile).toList()) {
                String n=file.getFileName().toString();if(n.equals("paper-catalog.json")||n.equals("paper-figures.json")||n.matches("p[1-4]-(page-[0-9]{3}|sheet-[0-9]+)\\.png")||n.matches("figure-paper-[0-9]{2}\\.png"))inputs.put(n,file.toString());
            }}
            String output,role;
            if("plan_pending".equals(phase)){output="paper-plan.json";role=PLAN;}
            else if("select_pending".equals(phase)){output="paper-selections.json";role=SELECT;}
            else if("check_pending".equals(phase)){output="paper-audit.json";role=CHECK;}
            else return false;
            Map<String,Object> meta=new LinkedHashMap<>(Map.of("projectId",t.get("projectId"),"reviewOnly",true,"reviewCampaign",c.get("id"),"reviewStage","paper-figures","paperFigureOutput",output,"reviewInputs",inputs,"reviewRecoveryModel",model));
            String prompt=COMMON+role+"\n此前本阶段错误（只作诊断）："+s.getOrDefault("lastError","");
            var task=workers.dispatch(c.get("userId").toString(),"CODER","论文原图补充 · "+t.get("title"),prompt,meta);
            s.put("taskId",task.getId());s.put("phase",Map.of("plan_pending","planning","select_pending","selecting","check_pending","checking").get(phase));return true;
        }catch(Exception e){fail(s,phase,e);return false;}
    }
    static boolean auditPasses(Map<String,Object> audit,List<Map<String,Object>> figures,Path events)throws Exception {
        if(!"pass".equals(audit.get("verdict"))||!(audit.get("blockingIssues") instanceof List<?> issues)||!issues.isEmpty()||!(audit.get("checks") instanceof List<?> checks)||checks.size()!=figures.size())return false;
        Set<String> read=new HashSet<>();var mapper=new ObjectMapper();
        try(var lines=Files.lines(events)){for(String line:(Iterable<String>)lines::iterator){var e=mapper.readTree(line);var p=e.path("part");var state=p.path("state");if("tool_use".equals(e.path("type").asText())&&"read".equals(p.path("tool").asText())&&"completed".equals(state.path("status").asText()))read.add(Path.of(state.path("input").path("filePath").asText()).getFileName().toString());}}
        Set<String> checked=new HashSet<>();for(Object raw:checks) {
            if(!(raw instanceof Map<?,?> check)||!checked.add(String.valueOf(check.get("id"))))return false;
            var fig=figures.stream().filter(f->Objects.equals(f.get("id"),check.get("id"))).findFirst();if(fig.isEmpty()||!read.contains(fig.get().get("file")))return false;
            for(String key:List.of("identityVerified","figureVerified","cropComplete","relevant","readable"))if(!Boolean.TRUE.equals(check.get(key)))return false;
            if(!(check.get("assessment") instanceof String detail)||detail.isBlank())return false;
            for(Object p:(List<?>)fig.get().get("panels")) {var panel=(Map<?,?>)p;String page=panel.get("paperId")+"-page-"+String.format("%03d",((Number)panel.get("page")).intValue())+".png";if(!read.contains(page))return false;}
        }return true;
    }
    private static final String COMMON="你是成稿后的论文原图补充代理。正文、原概括图和inputs全部只读，不能润色、删改或替换；只新增原论文图摘录与新增图注。不能生成/重绘数据图，也不能声称网页阅读等于本地PDF已下载。仅用公开合法可访问全文，不用账号、cookie、付费购买、验证码绕过或高价模型。原图仅裁剪、等比例缩放和组图，保留原图轴、单位、图例、panel标签。由软件下载PDF、渲染、裁剪和最后插入，代理不写脚本、不生成图像字节。最多4幅新增组图、每图最多4个panel。最后按当前要求写JSON和RESULT.md/RESULT.json。\n";
    private static final String PLAN="阶段1：对现有正文中代表性研究寻找原论文PDF的真实下载URL，先读取sources.json和正文，仅从已有sourceId选择最多4篇最能补充正文且有真实实验/结构/显微原图的论文。可用websearch/webfetch寻找出版商/机构仓储/作者公开全文；不凭空拼下载地址，不以搜索结果缩略图代替原图。写paper-plan.json={papers:[{sourceId,pdfUrl,license:实际许可或未核实,licenseUrl,reason}],gaps:[无法取得全文的记录]}。没有可用PDF时papers空数组并具体说明。许可未知如实记录，不冒称开放许可。不要改写综述、不要等待下载，本阶段只交候选URL，下一阶段软件取得真实PDF。";
    private static final String SELECT="阶段2：paper-catalog.json来自软件实际下载并保存的PDF，含原始URL/SHA256/页文字/预览。先核对PDF题名/作者/DOI与expectedSource一致，读sheet缩略页找到代表图，再必须read完整pN-page-NNN.png确认实际图号、完整边界。页码用PDF的1起始页码；bbox=[left,top,right,bottom]为0到1归一化裁剪框，包含完整坐标轴/图例/原panel标签，不能仅截好看的局部。选择互补的真实图，可同文多panel或不同文组图但不得暗示同一实验；标明各panel原Fig号。插入位置afterSection必须逐字等于review.md里唯一的Markdown标题行（含##），软件插在该节末尾。写paper-selections.json={figures:[{title,caption:新增中文图注并说明研究对象/条件/对应正文，不能增加正文未支持结论,afterSection,panels:[{paperId,page,bbox,originalFigure:原图号及panel,identityEvidence:题名作者DOI核对依据}]}],gaps:[]}。不合适/错论文/缺图则跳过，不强凑；全不可用可以figures空数组并说明具体gaps。若有paper-feedback.json按反馈调整选择/边界，不改正文。";
    private static final String CHECK="阶段3：独立审查原图补充。必须实际read每张figure-paper-*.png和对应pN-page-NNN.png；结合paper-catalog.json页文字核对原PDF身份与原来源，检查原图号、裁剪没有丢轴/标签/图例、组合没有误导比较，新增图注/插入章节与现有正文相关。不能只信上轮代理的identityEvidence，须用PDF文字和实际页自行核对。正文和原概括图由哈希锁定，本阶段不改。写paper-audit.json={verdict:pass|revise,blockingIssues:[],checks:[{id:P01,identityVerified:true|false,figureVerified:true|false,cropComplete:true|false,relevant:true|false,readable:true|false,assessment:详细核验依据}],limitations:[包括未核许可/全文覆盖局限]}。看不到图时不得pass。只写审查记录，不修图。";
}
