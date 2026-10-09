package com.ekina.application.review;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ReviewModelAvailabilityTest {
    @TempDir Path dir;
    @Test void onlyProvider403AccessEventsCountNotWebpageText()throws Exception {
        Path events=dir.resolve("events.jsonl");
        Files.writeString(events,"{\"type\":\"tool_use\",\"error\":{\"data\":{\"statusCode\":403,\"message\":\"Your organization does not have access to this model.\"}}}");
        assertFalse(ReviewModelAvailability.denied(events));
        Files.writeString(events,"{\"type\":\"error\",\"error\":{\"data\":{\"statusCode\":403,\"message\":\"Your organization does not have access to this model.\"}}}");
        assertTrue(ReviewModelAvailability.denied(events));
    }
    @Test void skipsDeniedModelWithoutExpandingAllowlistAndHonorsExpiry() {
        Instant now=Instant.parse("2026-10-05T00:00:00Z");String luna=ReviewModelAvailability.MODELS.getFirst(),glm=ReviewModelAvailability.MODELS.getLast();
        var until=new HashMap<String,String>();until.put(luna,now.plusSeconds(3600).toString());
        assertEquals(glm,ReviewModelAvailability.select(luna,until,now));
        until.put(glm,now.plusSeconds(3600).toString());
        assertThrows(IllegalArgumentException.class,()->ReviewModelAvailability.select(luna,until,now));
        assertEquals(luna,ReviewModelAvailability.select(luna,until,now.plusSeconds(3601)));
    }
}
