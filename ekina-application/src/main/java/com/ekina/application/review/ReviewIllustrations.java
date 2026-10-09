package com.ekina.application.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Structural/provenance checks, never a substitute for inspecting rendered figures. */
public final class ReviewIllustrations {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static boolean figureFile(String name){return name.matches("figure-[a-z0-9-]{1,64}\\.(svg|png)");}
    public static List<Map<String,Object>> readPlan(Path folder,Set<String> sourceIds) throws Exception {
        return validate(JSON.readValue(Files.readString(folder.resolve("figure-plan.json")),new TypeReference<List<Map<String,Object>>>(){}),sourceIds);
    }
    public static List<Map<String,Object>> readFigures(Path folder,Set<String> sourceIds) throws Exception {
        var figures=validate(JSON.readValue(Files.readString(folder.resolve("figures.json")),new TypeReference<List<Map<String,Object>>>(){}),sourceIds);
        for(var f:figures)validateSvg(folder.resolve(f.get("file").toString()));
        return figures;
    }
    private static List<Map<String,Object>> validate(List<Map<String,Object>> figs,Set<String> sourceIds) {
        return validate(figs,sourceIds,false);
    }
    private static List<Map<String,Object>> validate(List<Map<String,Object>> figs,Set<String> sourceIds,boolean single) {
        if(single?figs.size()!=1:figs.size()<3||figs.size()>6)throw new IllegalArgumentException(single?"单图任务必须恰好交付一幅图":"需要3至6幅承担不同解释任务的综述图");
        Set<String> ids=new HashSet<>(),files=new HashSet<>(),roles=new HashSet<>();
        for(var f:figs) {
            for(String key:List.of("id","file","title","purpose","kind","caption","section","evidenceBoundary"))
                if(!(f.get(key) instanceof String text)||text.isBlank())throw new IllegalArgumentException("图信息缺少"+key);
            String file=f.get("file").toString();
            if(!file.matches("figure-[a-z0-9-]{1,64}\\.svg")||!files.add(file)||!ids.add(f.get("id").toString()))throw new IllegalArgumentException("图编号或文件名无效");
            roles.add(f.get("kind").toString());
            if(!(f.get("sourceIds") instanceof List<?> refs)||refs.isEmpty()||!sourceIds.containsAll(refs))throw new IllegalArgumentException("图必须对应已有来源");
        }
        if(!single&&roles.size()<3)throw new IllegalArgumentException("图不能全是同一种流程或文字框");
        return figs;
    }
    /** Single tasks retain all source/safety checks; the assembled set still needs 3–6 diverse figures. */
    public static Map<String,Object> readSingleFigure(Path folder,Set<String> sourceIds,Map<String,Object> expected) throws Exception {
        var figs=validate(JSON.readValue(Files.readString(folder.resolve("figures.json")),new TypeReference<List<Map<String,Object>>>(){}),sourceIds,true);
        var f=figs.getFirst();
        for(String key:List.of("id","file","sourceIds"))if(!Objects.equals(f.get(key),expected.get(key)))throw new IllegalArgumentException("单图任务与原计划不一致："+key);
        validateSvg(folder.resolve(f.get("file").toString()));return f;
    }
    /** Serialize the author's existing plan, not newly authored scientific content. */
    public static void ensureSingleManifest(Path folder,Set<String> sourceIds,Map<String,Object> expected) throws Exception {
        validate(List.of(expected),sourceIds,true);
        validateSvg(folder.resolve(expected.get("file").toString()));
        Path manifest=folder.resolve("figures.json");
        if(!Files.exists(manifest))Files.writeString(manifest,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(List.of(expected)),StandardOpenOption.CREATE_NEW);
        readSingleFigure(folder,sourceIds,expected);
    }
    public static void validateSvg(Path path) throws Exception {
        if(!Files.isRegularFile(path)||Files.size(path)>1_000_000)throw new IllegalArgumentException("图文件缺失或过大");
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        var doc=factory.newDocumentBuilder().parse(path.toFile());var root=doc.getDocumentElement();
        if(!"svg".equals(root.getLocalName())||!root.hasAttribute("viewBox"))throw new IllegalArgumentException("SVG需要viewBox");
        Set<String> allowed=Set.of("svg","g","defs","path","rect","circle","ellipse","line","polyline","polygon","text","tspan","linearGradient","radialGradient","stop","marker","clipPath","pattern","title","desc");
        var nodes=doc.getElementsByTagName("*");
        for(int i=0;i<nodes.getLength();i++) {
            var node=(Element)nodes.item(i);
            if(!allowed.contains(node.getLocalName()))throw new IllegalArgumentException("SVG含不支持或主动内容");
            for(int j=0;j<node.getAttributes().getLength();j++) {
                var attr=node.getAttributes().item(j);String key=attr.getNodeName().toLowerCase(Locale.ROOT),value=attr.getNodeValue();
                if(key.startsWith("on")||key.equals("style")||key.endsWith("href")||value.toLowerCase(Locale.ROOT).contains("javascript:"))throw new IllegalArgumentException("SVG不允许脚本/样式/外部引用");
                if(value.contains("url(")&&!value.matches("url\\(#[a-zA-Z0-9_-]+\\)"))throw new IllegalArgumentException("SVG仅允许内部颜色/箭头引用");
            }
        }
    }
    /** Reuse structurally valid drawing output even if the worker reported failure; never alter that result. */
    public static Map<String,String> recoveryInputs(Path folder,Set<String> sourceIds) throws Exception {
        var figures=readFigures(folder,sourceIds);
        Map<String,String> inputs=new LinkedHashMap<>();
        inputs.put("previous-figures.json",folder.resolve("figures.json").toString());
        for(var figure:figures) {
            String name=figure.get("file").toString();
            inputs.put("previous-"+name,folder.resolve(name).toString());
        }
        if(Files.isRegularFile(folder.resolve("RESULT.json")))inputs.put("previous-drawing-result.json",folder.resolve("RESULT.json").toString());
        return inputs;
    }
    public static void validateTextLinks(Path folder,List<Map<String,Object>> figs) throws Exception {
        String prose=Files.readString(folder.resolve("review.md"));
        for(var f:figs)if(!prose.contains("]("+f.get("file")+")"))throw new IllegalArgumentException("正文缺少图引用："+f.get("id"));
    }
    public static boolean auditPasses(Map<String,Object> audit,List<Map<String,Object>> figures,Path events) throws Exception {
        if(!"pass".equals(audit.get("readabilityVerdict"))||!(audit.get("figureChecks") instanceof List<?> checks)||checks.size()!=figures.size())return false;
        Set<String> readImages=new HashSet<>();
        if(Files.isRegularFile(events))try(var lines=Files.lines(events)) {
            lines.forEach(line->{try {var e=JSON.readTree(line);var p=e.path("part");var st=p.path("state");
                if("tool_use".equals(e.path("type").asText())&&"read".equals(p.path("tool").asText())&&"completed".equals(st.path("status").asText()))
                    readImages.add(Path.of(st.path("input").path("filePath").asText()).getFileName().toString());
            }catch(Exception ignored){}});
        }
        Set<String> checked=new HashSet<>();
        for(var f:figures) {
            String id=f.get("id").toString(),png=f.get("file").toString().replace(".svg",".png");
            var found=checks.stream().filter(x->x instanceof Map<?,?> m&&id.equals(m.get("id"))).findFirst();
            if(found.isEmpty()||!checked.add(id))return false;
            var x=(Map<?,?>)found.get();
            if(!"pass".equals(x.get("verdict"))||!png.equals(x.get("viewedFile"))||!readImages.contains(png)
                    ||!(x.get("assessment") instanceof String a)||a.isBlank())return false;
        }
        return true;
    }
    public static final String PLAN_PROMPT="面向组内阅读写清晰中文图文综述，不模仿英文期刊双栏、不堆论文条目。结构应包括问题/分类框架、机制、代表进展、路线比较、应用与局限。先纠正旧稿已知语言和证据问题；协议、目录/执行记录等留在附录，不进入科研正文。另写figure-plan.json数组，规划3至6幅互补图：总览/分类、真实机制或器件剖面、路线或证据比较、工艺/应用/展望按主题选择，至少三类。每项{id,file:figure-英文短名.svg,title,purpose,kind,caption,section,sourceIds:[来源id],evidenceBoundary}。caption为完整中文图注，说明各面板和来源/证据边界。正文相关小节插入![图号及标题](figure-英文短名.svg)，图前引出问题，图后解释比较关系。此阶段只写图计划，不画SVG；后续独立制图任务完成图片。示意图不得编造成真实实验图；无可比数据不用伪量化曲线，数据图必须在evidence.csv中有数据/条件/来源。";
    public static final String DRAW_PROMPT="你是Ekina综述制图阶段。只读inputs里的review.md、sources.json、evidence.csv及figure-plan.json。按照图计划逐幅原创绘制可编辑SVG，并写figures.json（与图计划字段一致，id/file/sourceIds保持一致）。不要另写或改写正文，不修改inputs。绘图必须有实际科学视觉内容：器件/结构剖面、几何部件、物质传输箭头、机制前后状态或比较面板等；不能全部是彩色文字框/流程框，不能只交图计划。白底，有限而一致的配色，中文标签，清楚层级，面板(a)(b)，留足间距，避免细线和密小字。每图宽1200至1800、高700至1200，viewBox匹配，标签至少20px、标题28px。使用SVG原生形状/路径/文字和属性（Microsoft YaHei字体），不使用style属性、style元素、脚本、foreignObject、image、use或任何外部资源，只允许内部url(#id)颜色/箭头。不要把从文献重绘的示意图标为实验原图，不捏造数据；在图注说明原创综合示意及sourceIds。缺少事实时保守表示边界，不用视觉精美掩盖不确定性。每完成一幅立即write落盘；不反复检索、不运行shell或生成训练。产出3至6幅SVG、figures.json、RESULT.md/json。软件将自动渲染PNG供独立审查。";
    public static final String AUDIT_PROMPT="这是图文阅读稿验收：必须用read逐幅打开inputs中的figure-*.png实际查看图像（只读SVG源码不算视觉检查）。如果当前模型/工具看不到图像，明确判insufficient，不能声称已看。核对标签/箭头/结构与证据、图注、正文对应，检查重叠裁切、中文缺字、字号、图例和是否仅文字框凑图；图无科学信息或图文矛盾属于blockingIssues。正文破损混语、影响理解的数字转录及重要偏倚遗漏也属交付阻断，不再归为随意可忽略的润色。audit.json增加readabilityVerdict:pass|revise、figureChecks:[{id,viewedFile:figure-英文短名.png,verdict:pass|revise,assessment:具体图面与证据检查结果}]，覆盖每幅图。来源核查规则仍保留。只写审查记录，不亲自改稿/改图。";
}
