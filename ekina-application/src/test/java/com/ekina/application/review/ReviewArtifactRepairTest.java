package com.ekina.application.review;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReviewArtifactRepairTest {
    static String source(String id,String locator){return "{\n\"id\":\""+id+"\",\"title\":\"original title\",\"url\":\"https://example.org/"+id+"\",\"year\":2025,\"readLevel\":\"abstract\",\n\"locator\": \""+locator+"\"\n}";}
    @Test void escapesOnlySerializationLeavingEvidenceTextAndOtherFields() throws Exception {
        String raw="["+source("a","存在\"发表偏倚\"信号")+","+source("b","unchanged")+","+source("c","unchanged")+"]";
        var r=ReviewArtifactRepair.repairLocatorQuotes(raw);
        assertEquals(2,r.escapedQuotes());assertEquals("存在\"发表偏倚\"信号",r.sources().getFirst().get("locator"));
        assertEquals(raw,r.text().replace("\\\"","\""));
        assertEquals("abstract",r.sources().getFirst().get("readLevel"));
    }
    @Test void doesNotRepairTruncationMissingFieldsOrAlreadyValidJson() {
        String valid="["+source("a","ok")+","+source("b","ok")+","+source("c","ok")+"]";
        assertThrows(IllegalArgumentException.class,()->ReviewArtifactRepair.repairLocatorQuotes(valid));
        assertThrows(Exception.class,()->ReviewArtifactRepair.repairLocatorQuotes(valid.substring(0,valid.length()-1)));
        assertThrows(Exception.class,()->ReviewArtifactRepair.repairLocatorQuotes("["+source("a","broken \"quote\"")+"]"));
        assertThrows(Exception.class,()->ReviewArtifactRepair.repairLocatorQuotes(valid+"garbage"));
    }
}
