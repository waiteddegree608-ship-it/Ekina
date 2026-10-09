package com.ekina.infrastructure.agent.video;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for multimodal video watching (subtitle + audio ASR + keyframe vision).
 */
@Component
@ConfigurationProperties(prefix = "ekina.video")
public class VideoWatchProperties {

    /** Path or command name for yt-dlp. */
    private String ytdlpPath = "yt-dlp";

    /** Path or command name for ffmpeg. */
    private String ffmpegPath = "ffmpeg";

    /** Path or command name for ffprobe (defaults derived from ffmpeg dir if empty). */
    private String ffprobePath = "ffprobe";

    /** Max media duration to fully process (seconds). Longer videos are still sampled. */
    private int maxProcessSeconds = 2400;

    /** Max keyframes sent to the vision model. */
    private int maxFrames = 8;

    /** Target interval between keyframes (seconds). Actual interval may grow for long videos. */
    private int frameIntervalSeconds = 25;

    /** Vision model id (OpenAI-compatible multimodal). */
    private String visionModel = "Qwen/Qwen3-VL-8B-Instruct";

    /** ASR model id on OpenAI-compatible /audio/transcriptions endpoint. */
    private String asrModel = "whisper-1";

    /**
     * Optional separate base URL for ASR (e.g. https://api.openai.com).
     * Empty = use spring.ai.openai.base-url (often fails on OpenCode Zen — L2 will report skip).
     */
    private String asrBaseUrl = "";

    /** Max characters of subtitle/transcript kept in the tool result. */
    private int maxTranscriptChars = 12000;

    /** Max characters per frame vision description. */
    private int maxVisionCharsPerFrame = 280;

    /** Whether to keep downloaded raw media after analysis. */
    private boolean keepRawMedia = false;

    /** Prefer lower video height to save bandwidth/disk (e.g. 480). */
    private int maxVideoHeight = 480;

    /** Soft timeout for external processes (seconds). */
    private int processTimeoutSeconds = 600;

    public String getYtdlpPath() {
        return ytdlpPath;
    }

    public void setYtdlpPath(String ytdlpPath) {
        this.ytdlpPath = ytdlpPath;
    }

    public String getFfmpegPath() {
        return ffmpegPath;
    }

    public void setFfmpegPath(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    public String getFfprobePath() {
        return ffprobePath;
    }

    public void setFfprobePath(String ffprobePath) {
        this.ffprobePath = ffprobePath;
    }

    public int getMaxProcessSeconds() {
        return maxProcessSeconds;
    }

    public void setMaxProcessSeconds(int maxProcessSeconds) {
        this.maxProcessSeconds = maxProcessSeconds;
    }

    public int getMaxFrames() {
        return maxFrames;
    }

    public void setMaxFrames(int maxFrames) {
        this.maxFrames = maxFrames;
    }

    public int getFrameIntervalSeconds() {
        return frameIntervalSeconds;
    }

    public void setFrameIntervalSeconds(int frameIntervalSeconds) {
        this.frameIntervalSeconds = frameIntervalSeconds;
    }

    public String getVisionModel() {
        return visionModel;
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel;
    }

    public String getAsrModel() {
        return asrModel;
    }

    public void setAsrModel(String asrModel) {
        this.asrModel = asrModel;
    }

    public String getAsrBaseUrl() {
        return asrBaseUrl;
    }

    public void setAsrBaseUrl(String asrBaseUrl) {
        this.asrBaseUrl = asrBaseUrl;
    }

    public int getMaxTranscriptChars() {
        return maxTranscriptChars;
    }

    public void setMaxTranscriptChars(int maxTranscriptChars) {
        this.maxTranscriptChars = maxTranscriptChars;
    }

    public int getMaxVisionCharsPerFrame() {
        return maxVisionCharsPerFrame;
    }

    public void setMaxVisionCharsPerFrame(int maxVisionCharsPerFrame) {
        this.maxVisionCharsPerFrame = maxVisionCharsPerFrame;
    }

    public boolean isKeepRawMedia() {
        return keepRawMedia;
    }

    public void setKeepRawMedia(boolean keepRawMedia) {
        this.keepRawMedia = keepRawMedia;
    }

    public int getMaxVideoHeight() {
        return maxVideoHeight;
    }

    public void setMaxVideoHeight(int maxVideoHeight) {
        this.maxVideoHeight = maxVideoHeight;
    }

    public int getProcessTimeoutSeconds() {
        return processTimeoutSeconds;
    }

    public void setProcessTimeoutSeconds(int processTimeoutSeconds) {
        this.processTimeoutSeconds = processTimeoutSeconds;
    }
}
