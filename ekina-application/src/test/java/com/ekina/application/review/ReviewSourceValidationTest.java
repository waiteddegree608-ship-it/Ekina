package com.ekina.application.review;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ReviewSourceValidationTest {
    @TempDir Path dir;
    Map<String,Object> source(String id,String url){return new LinkedHashMap<>(Map.of("id",id,"title","title","url",url,"readLevel","full_text","locator","section"));}
    List<Map<String,Object>> external(){return new ArrayList<>(List.of(source("a","https://example.org/a"),source("b","https://example.org/b"),source("c","https://example.org/c")));}
    @Test void knownInternalSnapshotAcceptedButNotCountedAsOriginalPaper()throws Exception {
        Path file=dir.resolve("report.md");Files.writeString(file,"prior report");
        var receipt=Map.of("inputs/t1-review.md",Map.of("path",file.toString(),"sha256",ReviewSourceValidation.hash(file)));
        var sources=external();sources.add(source("T1","inputs/t1-review.md"));assertDoesNotThrow(()->ReviewSourceValidation.validate(sources,receipt));
        sources.removeFirst();assertThrows(ReviewSourceValidation.Invalid.class,()->ReviewSourceValidation.validate(sources,receipt));
    }
    @Test void staleOrInventedInternalPathFailsWithActionableFieldDiagnosis()throws Exception {
        Path file=dir.resolve("report.md");Files.writeString(file,"old");var receipt=Map.of("inputs/t1-review.md",Map.of("path",file.toString(),"sha256",ReviewSourceValidation.hash(file)));Files.writeString(file,"changed");
        var sources=external();sources.add(source("T1","inputs/t1-review.md"));var e=assertThrows(ReviewSourceValidation.Invalid.class,()->ReviewSourceValidation.validate(sources,receipt));assertEquals("T1",e.issues.getFirst().get("sourceId"));assertEquals("url",e.issues.getFirst().get("field"));assertTrue(e.issues.getFirst().containsKey("expected"));
        assertThrows(ReviewSourceValidation.Invalid.class,()->ReviewSourceValidation.validate(sources,Map.of()));
    }
    @Test void internalCannotMasqueradeAsExternal()throws Exception {
        Path f=dir.resolve("r.md");Files.writeString(f,"report");var receipt=Map.of("inputs/t1-review.md",Map.of("path",f.toString(),"sha256",ReviewSourceValidation.hash(f)));var sources=external();var t=source("T1","inputs/t1-review.md");t.put("kind","external");sources.add(t);assertThrows(ReviewSourceValidation.Invalid.class,()->ReviewSourceValidation.validate(sources,receipt));
    }
    @Test void stagesExactHistoricalSnapshotIntoAllowedDeliverablesWithoutChangingReceipt()throws Exception {
        Path original=dir.resolve("sandbox/t1-review.md");Files.createDirectories(original.getParent());Files.writeString(original,"historical report");
        var receipt=Map.of("inputs/t1-review.md",Map.of("inputName","t1-review.md","path",original.toString(),"sha256",ReviewSourceValidation.hash(original)));
        Path allowed=dir.resolve("deliverables/bundle/internal-inputs");
        var staged=ReviewSourceValidation.stageSnapshots(receipt,allowed);
        assertEquals(-1,Files.mismatch(original,Path.of(staged.get("inputs/t1-review.md").get("path"))));
        assertTrue(Path.of(staged.get("inputs/t1-review.md").get("path")).startsWith(allowed));
        assertEquals(original.toString(),receipt.get("inputs/t1-review.md").get("path"));
        Files.writeString(original,"changed");
        assertThrows(IllegalArgumentException.class,()->ReviewSourceValidation.stageSnapshots(receipt,allowed));
        assertEquals("historical report",Files.readString(allowed.resolve("t1-review.md")));
    }
}
