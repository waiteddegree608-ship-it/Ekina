package com.ekina.application.review;
import com.ekina.application.home.HomeQuotaService;
import com.ekina.application.multiagent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReviewFullTextTest {
 @TempDir Path root;
 @Test void independentPaperReadersRespectSlotsAndUseDistinctImmutableInputs()throws Exception {
  var service=new ReviewFullText();var home=mock(HomeQuotaService.class);when(home.homePath()).thenReturn(root);ReflectionTestUtils.setField(service,"home",home);
  var workers=mock(MultiAgentOrchestrator.class);ReflectionTestUtils.setField(service,"workers",workers);var count=new java.util.concurrent.atomic.AtomicInteger();
  when(workers.dispatch(any(),any(),any(),any(),any())).thenAnswer(call->{var task=new AgentTask();task.setId("r"+count.incrementAndGet());task.setStatus("queued");return task;});
  Path work=root.resolve("workspace/opencode-deliverables/fulltext_test");Files.createDirectories(work);var inventory=new ArrayList<Map<String,Object>>();var papers=new ArrayList<Map<String,Object>>();
  for(int i=1;i<=2;i++){String key="p"+i,name=key+"-text-001.txt";Files.writeString(work.resolve(name),"Actual full article text");inventory.add(Map.of("id",key,"title","A distinct full paper title "+i));papers.add(new LinkedHashMap<>(Map.of("sourceId",key,"key",key,"status","downloaded","sha256","hash"+i,"chunks",List.of(Map.of("file",name)),"readFiles",new ArrayList<>())));}
  Files.writeString(work.resolve("source-inventory.json"),new ObjectMapper().writeValueAsString(inventory));
  var state=new LinkedHashMap<String,Object>(Map.of("enabled",true,"phase","work_pending","corpusId","fulltext_test","papers",papers,"history",new ArrayList<>()));var topic=new LinkedHashMap<String,Object>(Map.of("id","t1","title","Topic","projectId","p","fullText",state));var campaign=Map.<String,Object>of("id","c","userId","u");
  assertTrue(service.advance(campaign,topic,"opencode-go/gpt-6-luna",1));assertEquals(1,ReviewFullText.activeCount(topic));
  assertTrue(service.advance(campaign,topic,"opencode-go/gpt-6-luna",1));assertEquals(2,ReviewFullText.activeCount(topic));
  assertFalse(service.advance(campaign,topic,"opencode-go/gpt-6-luna",1));assertEquals(2,count.get());
  assertTrue(Files.exists(work.resolve("p1-current-paper.json")));assertTrue(Files.exists(work.resolve("p2-current-paper.json")));
 }

 @Test void missingPdfUsesPerPaperSearchWithoutBlockingOtherPapers()throws Exception {
  var service=new ReviewFullText();var home=mock(HomeQuotaService.class);when(home.homePath()).thenReturn(root);ReflectionTestUtils.setField(service,"home",home);var workers=mock(MultiAgentOrchestrator.class);ReflectionTestUtils.setField(service,"workers",workers);
  when(workers.dispatch(any(),any(),any(),any(),any())).thenAnswer(call->{var task=new AgentTask();task.setId("search");task.setStatus("queued");return task;});
  Path work=root.resolve("workspace/opencode-deliverables/fulltext_test");Files.createDirectories(work);Files.writeString(work.resolve("source-inventory.json"),"[{\"id\":\"a\",\"title\":\"Original target paper\"}]");
  var paper=new LinkedHashMap<String,Object>(Map.of("key","p1","sourceId","a","status","needs_access","pdfUrls",List.of(),"readFiles",new ArrayList<>()));var state=new LinkedHashMap<String,Object>(Map.of("enabled",true,"phase","work_pending","corpusId","fulltext_test","papers",List.of(paper),"history",new ArrayList<>()));var topic=new LinkedHashMap<String,Object>(Map.of("id","t","projectId","p","title","Title","fullText",state));
  assertTrue(service.advance(Map.of("id","c","userId","u"),topic,"opencode-go/gpt-6-luna",12));assertEquals(1,ReviewFullText.activeCount(topic));
  verify(workers).dispatch(any(),any(),any(),any(),argThat(meta->"fulltext-plan.json".equals(meta.get("paperFigureOutput"))&&((Map<?,?>)meta.get("reviewInputs")).get("source-inventory.json").toString().endsWith("p1-inventory.json")));
 }
 @Test void planCannotOmitHardToAcquirePaper(){
  var sources=List.<Map<String,Object>>of(Map.of("id","a"),Map.of("id","b"));
  var plan=List.<Map<String,Object>>of(Map.of("sourceId","a","documentType","paper","reason","DOI","pdfUrls",List.of()));
  assertThrows(IllegalArgumentException.class,()->ReviewFullText.validatePlan(sources,plan));
 }
 @Test void freshTopicStartsWithoutOldMaterial()throws Exception {
  var service=new ReviewFullText();var home=mock(HomeQuotaService.class);when(home.homePath()).thenReturn(root);ReflectionTestUtils.setField(service,"home",home);
  var topic=new LinkedHashMap<String,Object>();service.enable(topic,"reset");assertEquals("discovery_pending",ReviewFullText.state(topic).get("phase"));assertTrue(ReviewFullText.required(topic));assertFalse(ReviewFullText.active(topic));
 }
 @Test void selfClaimWithoutActualReadAndInventedQuotesCannotPass()throws Exception {
  String quote="Actual methods include a controlled comparison and measured errors.";Files.writeString(root.resolve("p1-text-001.txt"),quote);
  Path events=root.resolve("events.jsonl");Files.writeString(events,"");
  var note=new LinkedHashMap<String,Object>(Map.of("identityMatches",true,"completeArticle",true,"identityEvidence","DOI and title checked","segments",List.of(Map.of("file","p1-text-001.txt","summary","Methods and outcomes","quote",quote))));
  assertThrows(IllegalArgumentException.class,()->ReviewFullText.validateReading(note,List.of("p1-text-001.txt"),root,events));
  Files.writeString(events,"{\"type\":\"tool_use\",\"part\":{\"tool\":\"read\",\"state\":{\"status\":\"completed\",\"input\":{\"filePath\":\"inputs/p1-text-001.txt\"}}}}\n");
  assertDoesNotThrow(()->ReviewFullText.validateReading(note,List.of("p1-text-001.txt"),root,events));
  note.put("completeArticle",false);assertThrows(IllegalArgumentException.class,()->ReviewFullText.validateReading(note,List.of("p1-text-001.txt"),root,events));
 }
 @Test void authorCannotProceedBeforeFullTextReady(){assertThrows(IllegalArgumentException.class,()->new ReviewFullText().validateSources(new HashMap<>(),List.of()));}
}
