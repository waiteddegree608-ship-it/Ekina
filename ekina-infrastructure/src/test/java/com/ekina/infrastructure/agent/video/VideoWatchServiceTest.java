package com.ekina.infrastructure.agent.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VideoWatchServiceTest {

    @Test
    void extractBvidFromUrl() {
        assertEquals("BV1Ca2XBNEAo",
                VideoWatchService.extractBvid("https://www.bilibili.com/video/BV1Ca2XBNEAo/?spm_id_from=333.1007"));
    }

    @Test
    void extractBvidBare() {
        assertEquals("BV1Ca2XBNEAo", VideoWatchService.extractBvid("BV1Ca2XBNEAo"));
    }

    @Test
    void extractBvidMissing() {
        assertNull(VideoWatchService.extractBvid("https://example.com/watch?v=123"));
    }
}
