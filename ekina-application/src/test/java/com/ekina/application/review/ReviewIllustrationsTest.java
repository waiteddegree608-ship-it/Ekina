package com.ekina.application.review;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ReviewIllustrationsTest {
    @TempDir Path dir;
    final ObjectMapper json=new ObjectMapper();
    @Test void softwareManifestPreservesAuthorPlanAndNeverRepairsInvalidArtworkOrOverwritesExistingManifest() throws Exception {
        var plan=figures().getFirst();Path svg=dir.resolve(plan.get("file").toString());
        String content="<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1200 800'><text>fixture</text></svg>";
        assertThrows(Exception.class,()->ReviewIllustrations.ensureSingleManifest(dir,Set.of("S1"),plan));
        Files.writeString(svg,content);ReviewIllustrations.ensureSingleManifest(dir,Set.of("S1"),plan);
        assertEquals(plan,ReviewIllustrations.readSingleFigure(dir,Set.of("S1"),plan));assertEquals(content,Files.readString(svg));
        Files.writeString(dir.resolve("figures.json"),"broken manifest");
        assertThrows(Exception.class,()->ReviewIllustrations.ensureSingleManifest(dir,Set.of("S1"),plan));
        assertEquals("broken manifest",Files.readString(dir.resolve("figures.json")));
    }
    @Test void singleCheckpointRequiresExactPlannedFigureAndDoesNotRelaxWholeSetGate() throws Exception {
        var one=figures().getFirst();
        Files.writeString(dir.resolve("figures.json"),json.writeValueAsString(List.of(one)));
        Files.writeString(dir.resolve("figure-1.svg"),"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1200 800'><text>fixture</text></svg>");
        assertEquals(one,ReviewIllustrations.readSingleFigure(dir,Set.of("S1"),one));
        assertThrows(Exception.class,()->ReviewIllustrations.readFigures(dir,Set.of("S1")));
        assertThrows(Exception.class,()->ReviewIllustrations.readSingleFigure(dir,Set.of("S1"),figures().get(1)));
        var changed=new HashMap<>(one);changed.put("sourceIds",List.of("S2"));
        assertThrows(Exception.class,()->ReviewIllustrations.readSingleFigure(dir,Set.of("S1","S2"),changed));
        Files.writeString(dir.resolve("figure-1.svg"),"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1 1'><script/></svg>");
        assertThrows(Exception.class,()->ReviewIllustrations.readSingleFigure(dir,Set.of("S1"),one));
    }
    List<Map<String,Object>> figures(){
        List<Map<String,Object>> list=new ArrayList<>();
        for(int i=1;i<=3;i++)list.add(new HashMap<>(Map.of("id","F"+i,"file","figure-"+i+".svg","title","图标题","purpose","解释机制","kind","role"+i,"caption","来源与面板说明","section","第一节","sourceIds",List.of("S1"),"evidenceBoundary","原创综合示意，不是实测曲线")));
        return list;
    }
    @Test void planNeedsDiverseReferencedFiguresAndTextLinks() throws Exception {
        var figures=figures();Files.writeString(dir.resolve("figure-plan.json"),json.writeValueAsString(figures));
        var read=ReviewIllustrations.readPlan(dir,Set.of("S1"));assertEquals(3,read.size());
        Files.writeString(dir.resolve("review.md"),"![图](figure-1.svg)\n![图](figure-2.svg)");
        assertThrows(Exception.class,()->ReviewIllustrations.validateTextLinks(dir,read));
        Files.writeString(dir.resolve("review.md"),"![图](figure-1.svg)\n![图](figure-2.svg)\n![图](figure-3.svg)");
        ReviewIllustrations.validateTextLinks(dir,read);
        figures.getFirst().put("sourceIds",List.of("invented"));Files.writeString(dir.resolve("figure-plan.json"),json.writeValueAsString(figures));
        assertThrows(Exception.class,()->ReviewIllustrations.readPlan(dir,Set.of("S1")));
    }
    @Test void strictJsonRejectsTrailingArtifactCorruption() throws Exception {
        Files.writeString(dir.resolve("figure-plan.json"),json.writeValueAsString(figures())+"}");
        assertThrows(Exception.class,()->ReviewIllustrations.readPlan(dir,Set.of("S1")));
    }
    @Test void svgRejectsActiveContentExternalFilesAndUnsafePaths() throws Exception {
        Path p=dir.resolve("figure-test.svg");
        Files.writeString(p,"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1200 800'><text x='50' y='50'>示意</text></svg>");
        ReviewIllustrations.validateSvg(p);
        for(String child:List.of("<script>alert(1)</script>","<image href='file:///secret'/>","<rect onload='alert(1)'/>","<rect fill='url(https://tracker)'/>")) {
            Files.writeString(p,"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1200 800'>"+child+"</svg>");
            assertThrows(Exception.class,()->ReviewIllustrations.validateSvg(p));
        }
        assertFalse(ReviewIllustrations.figureFile("../figure-test.svg"));
    }
    @Test void modelPassWithoutActualImageReadsCannotPassVisualGate() throws Exception {
        var figs=figures();List<Map<String,Object>> checks=new ArrayList<>();StringBuilder events=new StringBuilder();
        for(var f:figs){String png=f.get("file").toString().replace(".svg",".png");checks.add(Map.of("id",f.get("id"),"viewedFile",png,"verdict","pass","assessment","箭头/标签/图注与来源核对"));events.append(json.writeValueAsString(Map.of("type","tool_use","part",Map.of("tool","read","state",Map.of("status","completed","input",Map.of("filePath",dir.resolve(png).toString())))))).append('\n');}
        var audit=Map.<String,Object>of("readabilityVerdict","pass","figureChecks",checks);Path log=dir.resolve("events.jsonl");
        assertFalse(ReviewIllustrations.auditPasses(audit,figs,log));
        Files.writeString(log,events.toString());assertTrue(ReviewIllustrations.auditPasses(audit,figs,log));
        checks.removeLast();assertFalse(ReviewIllustrations.auditPasses(audit,figs,log));
    }
    @Test void staticPatternAllowedButNestedActiveContentAndExternalLinksRejected() throws Exception {
        Path p=dir.resolve("figure-pattern.svg");
        String start="<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1200 800'><defs><pattern id='mesh' width='20' height='20' patternUnits='userSpaceOnUse'>";
        String end="</pattern></defs><rect width='100' height='100' fill='url(#mesh)'/></svg>";
        Files.writeString(p,start+"<path d='M0 0 L20 20' stroke='#aaa'/>"+end);
        ReviewIllustrations.validateSvg(p);
        for(String unsafe:List.of("<script>alert(1)</script>","<image href='https://example.org/x'/>","<rect onload='x'/>","<rect fill='url(file:///secret)'/>")) {
            Files.writeString(p,start+unsafe+end);
            assertThrows(Exception.class,()->ReviewIllustrations.validateSvg(p));
        }
    }
    @Test void failedDrawingRecoveryPreservesOriginalFilesAndFailureRecord() throws Exception {
        Files.writeString(dir.resolve("figures.json"),json.writeValueAsString(figures()));
        for(int i=1;i<=3;i++)Files.writeString(dir.resolve("figure-"+i+".svg"),"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1200 800'><text>new revision</text></svg>");
        String failure="{\"status\":\"failed\",\"summary\":\"PNG deferred\"}";
        Files.writeString(dir.resolve("RESULT.json"),failure);
        var inputs=ReviewIllustrations.recoveryInputs(dir,Set.of("S1"));
        assertEquals(5,inputs.size());assertEquals(failure,Files.readString(Path.of(inputs.get("previous-drawing-result.json"))));
        assertTrue(Files.readString(Path.of(inputs.get("previous-figure-2.svg"))).contains("new revision"));
        assertFalse(inputs.containsKey("previous-review.md"));
        Files.writeString(dir.resolve("figure-2.svg"),"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1 1'><script/></svg>");
        assertThrows(Exception.class,()->ReviewIllustrations.recoveryInputs(dir,Set.of("S1")));
    }
}
