package com.ekina.application.review;

import com.ekina.application.home.HomeQuotaService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewDocumentServiceTest {
    @Test void runtimeTransitionsDoNotMakeNewManuscriptEditionsAndEmbeddedImagesAreCounted() {
        assertEquals(ReviewDocumentService.documentStatus("blocked"),ReviewDocumentService.documentStatus("auditing"));
        assertNotEquals(ReviewDocumentService.documentStatus("draft_ready"),ReviewDocumentService.documentStatus("auditing"));
        assertEquals(2,ReviewDocumentService.figureCount("\\includegraphics[width=\\linewidth]{figure-one.png}\n\\includegraphics{figure-two.png}"));
        assertEquals(0,ReviewDocumentService.figureCount("配图尚未完成"));
    }
    @TempDir Path home;
    ReviewDocumentService service() throws Exception {
        Files.createDirectories(home.resolve("state"));
        Files.writeString(home.resolve("state/reviews.json"),"[{\"id\":\"review_a\",\"userId\":\"u1\",\"topics\":[{\"id\":\"t1\",\"title\":\"Review\"}]}]");
        var s=new ReviewDocumentService();var quota=mock(HomeQuotaService.class);when(quota.homePath()).thenReturn(home);
        ReflectionTestUtils.setField(s,"home",quota);return s;
    }
    @Test void rejectsTraversalAndOtherOwners() throws Exception {
        var s=service();assertThrows(IllegalArgumentException.class,()->s.state("other","review_a","t1"));
        assertThrows(IllegalArgumentException.class,()->s.state("u1","../state","t1"));
        assertThrows(IllegalArgumentException.class,()->s.file("u1","review_a","t1","../secret","main.pdf"));
        assertThrows(IllegalArgumentException.class,()->s.file("u1","review_a","t1","v_1","../../secret"));
    }
    @Test void servesOnlySavedVersionsWithoutChangingResearchState() throws Exception {
        var s=service();Path root=home.resolve("workspace/review-documents/review_a_t1/versions/v_1");Files.createDirectories(root);
        Files.writeString(root.resolve("main.tex"),"source");
        String before=Files.readString(home.resolve("state/reviews.json"));
        assertEquals("source",Files.readString(s.file("u1","review_a","t1","v_1","main.tex")));
        assertEquals(before,Files.readString(home.resolve("state/reviews.json")));
    }
    @Test void concurrentNewVersionRejectsStaleSaveAndPreservesText() throws Exception {
        var s=service();Path root=home.resolve("workspace/review-documents/review_a_t1");Files.createDirectories(root);
        Files.writeString(root.resolve("index.json"),"{\"versions\":[{\"id\":\"v_2\"},{\"id\":\"v_1\"}]}");
        assertThrows(IllegalArgumentException.class,()->s.save("u1","review_a","t1","v_1","v_1","my edited source","edit"));
        assertFalse(Files.exists(root.resolve("versions")));
    }
    @Test void findsUserVersionWithoutReplacingNewerEkinaVersion() throws Exception {
        var s=service();Path root=home.resolve("workspace/review-documents/review_a_t1");Files.createDirectories(root);
        Files.writeString(root.resolve("index.json"),"{\"versions\":[{\"id\":\"v_3\",\"author\":\"Ekina\"},{\"id\":\"v_2\",\"author\":\"用户编辑\"}]}");
        assertEquals(root.resolve("versions/v_2/main.tex"),s.latestUserSource("review_a","t1").orElseThrow());
    }
}
