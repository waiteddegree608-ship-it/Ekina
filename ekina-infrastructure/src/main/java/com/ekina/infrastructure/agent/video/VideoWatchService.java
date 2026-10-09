package com.ekina.infrastructure.agent.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Multimodal video watching pipeline:
 * <ul>
 *   <li>L1 meta: title/desc/CC subtitles/comments (Bilibili API or yt-dlp metadata)</li>
 *   <li>L2 audio: download audio → ASR</li>
 *   <li>L3 vision: keyframe sampling → vision model descriptions</li>
 * </ul>
 */
@Service
public class VideoWatchService {

    private static final Pattern BVID = Pattern.compile("(BV[a-zA-Z0-9]{10})", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final VideoWatchProperties properties;
    private final VisionClient visionClient;
    private final RestClient restClient = RestClient.builder().build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.siliconflow.cn}")
    private String baseUrl;

    @Value("${ekina.sandbox-dir:./sandbox}")
    private String sandboxDir;

    public VideoWatchService(VideoWatchProperties properties, VisionClient visionClient) {
        this.properties = properties;
        this.visionClient = visionClient;
    }

    /**
     * @param urlOrPath Bilibili URL/BVID, other http(s) video URL, or local video file path
     * @param depth     auto | meta | audio | full
     */
    public String watch(String urlOrPath, String depth) {
        String mode = normalizeDepth(depth);
        String jobId = "vw_" + TS.format(LocalDateTime.now());
        Path workDir = Path.of(sandboxDir, "video_cache", jobId);
        StringBuilder report = new StringBuilder();
        report.append("=== Video Watch Report ===\n");
        report.append("job: ").append(jobId).append('\n');
        report.append("input: ").append(urlOrPath).append('\n');
        report.append("depth: ").append(mode).append('\n');
        report.append("layers: L1-meta");
        if (!"meta".equals(mode)) {
            report.append(" + L2-audio");
        }
        if ("full".equals(mode) || "auto".equals(mode)) {
            report.append(" + L3-vision");
        }
        report.append("\n\n");

        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            return "Failed to create work dir: " + e.getMessage();
        }

        boolean needAudio = !"meta".equals(mode);
        boolean needVision = "full".equals(mode) || "auto".equals(mode);

        try {
            // ---------- L1 ----------
            report.append("--- L1 Metadata / Subtitles ---\n");
            String bvid = extractBvid(urlOrPath);
            boolean hasSolidSubtitles = false;
            if (bvid != null) {
                L1Result l1 = fetchBilibiliL1(bvid);
                report.append(l1.text).append('\n');
                hasSolidSubtitles = l1.hasSolidSubtitles;
            } else if (isHttp(urlOrPath)) {
                report.append(fetchGenericMeta(urlOrPath)).append('\n');
            } else {
                File local = resolveLocal(urlOrPath);
                if (local != null && local.isFile()) {
                    report.append("Local file: ").append(local.getAbsolutePath())
                            .append(" (").append(local.length()).append(" bytes)\n");
                } else {
                    report.append("Note: could not resolve BVID/local file; will still try media download if depth requires.\n");
                }
            }

            // For auto mode without solid subtitles, force audio; vision still on for auto/full
            if ("auto".equals(mode) && !hasSolidSubtitles) {
                needAudio = true;
            }

            File mediaFile = null;
            File audioFile = null;

            // ---------- Download media if L2/L3 needed ----------
            if (needAudio || needVision) {
                report.append("\n--- Media acquisition ---\n");
                String toolCheck = checkTools();
                if (toolCheck != null) {
                    report.append("ERROR: ").append(toolCheck).append('\n');
                    report.append("Install: pip install -U yt-dlp ; and ensure ffmpeg is on PATH.\n");
                    return report.toString();
                }

                File local = resolveLocal(urlOrPath);
                if (local != null && local.isFile()) {
                    mediaFile = local;
                    report.append("Using local media: ").append(local.getAbsolutePath()).append('\n');
                } else {
                    String sourceUrl = normalizeWatchUrl(urlOrPath, bvid);
                    mediaFile = downloadMedia(sourceUrl, workDir, needVision, report);
                }

                if (mediaFile == null || !mediaFile.isFile()) {
                    report.append("Failed to obtain media file; L2/L3 skipped.\n");
                    return finalizeReport(report, workDir);
                }

                double duration = probeDurationSeconds(mediaFile);
                report.append(String.format(Locale.ROOT, "duration_sec: %.1f\n", duration));

                // ---------- L2 Audio ASR ----------
                if (needAudio) {
                    report.append("\n--- L2 Audio (ASR) ---\n");
                    audioFile = extractAudio(mediaFile, workDir, report);
                    if (audioFile != null && audioFile.isFile()) {
                        String transcript = transcribeAudioFile(audioFile);
                        report.append(truncate(transcript, properties.getMaxTranscriptChars())).append('\n');
                    } else {
                        report.append("Audio extract failed; ASR skipped.\n");
                    }
                }

                // ---------- L3 Keyframe vision ----------
                if (needVision) {
                    report.append("\n--- L3 Vision (keyframes) ---\n");
                    List<File> frames = extractKeyframes(mediaFile, workDir, duration, report);
                    if (frames.isEmpty()) {
                        report.append("No keyframes extracted.\n");
                    } else {
                        int i = 1;
                        for (File frame : frames) {
                            String approxTime = frame.getName().replaceAll("\\D+", "");
                            String prompt = "这是视频的第 " + i + "/" + frames.size()
                                    + " 个关键帧（文件 " + frame.getName() + "）。"
                                    + "请用中文描述：画面内容、可见文字/UI、人物或演示动作、图表公式。 concisely。";
                            String desc = visionClient.describeImageFile(frame, prompt);
                            report.append("[Frame ").append(i).append(" | ").append(frame.getName()).append("]\n");
                            report.append(desc).append("\n\n");
                            i++;
                        }
                    }
                }
            }

            report.append("\n--- Guidance for the agent ---\n");
            report.append("Combine L1 (metadata/subtitles), L2 (spoken audio), and L3 (on-screen visuals). ");
            report.append("Prefer concrete evidence from these layers; do not invent scenes not supported above.\n");

            return finalizeReport(report, workDir);
        } catch (Exception e) {
            report.append("\nFATAL: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
            return finalizeReport(report, workDir);
        } finally {
            if (!properties.isKeepRawMedia()) {
                // Keep the report-friendly small frames? User may want disk free — clean large media only
                cleanupHeavy(workDir);
            }
        }
    }

    private String finalizeReport(StringBuilder report, Path workDir) {
        report.append("\nwork_dir: ").append(workDir.toAbsolutePath()).append('\n');
        // Persist a copy for later skills / diary
        try {
            Path out = workDir.resolve("watch_report.md");
            Files.writeString(out, report.toString());
            report.append("report_file: ").append(out.toAbsolutePath()).append('\n');
        } catch (Exception ignored) {
        }
        return report.toString();
    }

    private void cleanupHeavy(Path workDir) {
        if (workDir == null || !Files.isDirectory(workDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(workDir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm")
                        || name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a")
                        || name.endsWith(".part")) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                }
            });
        } catch (IOException ignored) {
        }
    }

    private String normalizeDepth(String depth) {
        if (depth == null || depth.isBlank()) {
            return "auto";
        }
        String d = depth.trim().toLowerCase(Locale.ROOT);
        return switch (d) {
            case "meta", "l1", "subtitle", "subtitles" -> "meta";
            case "audio", "l2", "asr" -> "audio";
            case "full", "l3", "vision", "all" -> "full";
            default -> "auto";
        };
    }

    private String checkTools() {
        List<String> missing = new ArrayList<>();
        if (!commandWorks(properties.getYtdlpPath(), "--version")) {
            // also try python -m yt_dlp
            if (!commandWorks("python", "-m", "yt_dlp", "--version")) {
                missing.add("yt-dlp");
            }
        }
        if (!commandWorks(properties.getFfmpegPath(), "-version")) {
            missing.add("ffmpeg");
        }
        if (missing.isEmpty()) {
            return null;
        }
        return "missing tools: " + String.join(", ", missing);
    }

    private boolean commandWorks(String... cmd) {
        try {
            ProcessRunner.Result r = ProcessRunner.run(Arrays.asList(cmd), null, 20);
            return r.ok() || r.exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String ytdlpBin() {
        if (commandWorks(properties.getYtdlpPath(), "--version")) {
            return properties.getYtdlpPath();
        }
        return null;
    }

    private List<String> ytdlpPrefix() {
        String bin = ytdlpBin();
        if (bin != null) {
            return List.of(bin);
        }
        return List.of("python", "-m", "yt_dlp");
    }

    private File downloadMedia(String sourceUrl, Path workDir, boolean needVideo, StringBuilder report) {
        try {
            List<String> cmd = new ArrayList<>(ytdlpPrefix());
            cmd.add("--no-playlist");
            cmd.add("--restrict-filenames");
            cmd.add("-o");
            cmd.add(workDir.resolve("media.%(ext)s").toString());
            // Prefer low-res video when vision needed; audio-only otherwise
            int h = Math.max(240, properties.getMaxVideoHeight());
            if (needVideo) {
                cmd.add("-f");
                cmd.add("bv*[height<=" + h + "]+ba/b[height<=" + h + "]/b");
                cmd.add("--merge-output-format");
                cmd.add("mp4");
            } else {
                cmd.add("-f");
                cmd.add("ba/b");
                cmd.add("-x");
                cmd.add("--audio-format");
                cmd.add("mp3");
            }
            cmd.add("--newline");
            cmd.add(sourceUrl);

            report.append("yt-dlp: ").append(String.join(" ", cmd)).append('\n');
            ProcessRunner.Result r = ProcessRunner.run(cmd, workDir.toFile(), properties.getProcessTimeoutSeconds());
            if (!r.ok()) {
                report.append("yt-dlp failed (").append(r.exitCode).append("): ")
                        .append(trim(r.combinedError(), 800)).append('\n');
                // Fallback: try any single format
                List<String> fallback = new ArrayList<>(ytdlpPrefix());
                fallback.addAll(List.of(
                        "--no-playlist", "--restrict-filenames",
                        "-o", workDir.resolve("media.%(ext)s").toString(),
                        sourceUrl
                ));
                ProcessRunner.Result r2 = ProcessRunner.run(fallback, workDir.toFile(), properties.getProcessTimeoutSeconds());
                if (!r2.ok()) {
                    report.append("yt-dlp fallback failed: ").append(trim(r2.combinedError(), 500)).append('\n');
                    return null;
                }
            }

            try (Stream<Path> stream = Files.list(workDir)) {
                return stream
                        .map(Path::toFile)
                        .filter(f -> f.isFile() && isMediaFile(f.getName()))
                        .max(Comparator.comparingLong(File::length))
                        .orElse(null);
            }
        } catch (Exception e) {
            report.append("download exception: ").append(e.getMessage()).append('\n');
            return null;
        }
    }

    private boolean isMediaFile(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm")
                || n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".wav")
                || n.endsWith(".opus") || n.endsWith(".flv");
    }

    private double probeDurationSeconds(File mediaFile) {
        try {
            List<String> cmd = ProcessRunner.cmd(
                    properties.getFfprobePath(),
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    mediaFile.getAbsolutePath()
            );
            ProcessRunner.Result r = ProcessRunner.run(cmd, null, 30);
            if (r.ok()) {
                String t = r.stdout.trim();
                if (!t.isEmpty()) {
                    return Double.parseDouble(t.split("\\s+")[0]);
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private File extractAudio(File mediaFile, Path workDir, StringBuilder report) {
        String name = mediaFile.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a") || name.endsWith(".opus")) {
            return mediaFile;
        }
        File out = workDir.resolve("audio.mp3").toFile();
        try {
            List<String> cmd = ProcessRunner.cmd(
                    properties.getFfmpegPath(),
                    "-y",
                    "-i", mediaFile.getAbsolutePath(),
                    "-vn",
                    "-ac", "1",
                    "-ar", "16000",
                    "-b:a", "64k",
                    out.getAbsolutePath()
            );
            ProcessRunner.Result r = ProcessRunner.run(cmd, workDir.toFile(), Math.max(120, properties.getProcessTimeoutSeconds() / 2));
            if (!r.ok() || !out.isFile()) {
                report.append("ffmpeg audio extract failed: ").append(trim(r.combinedError(), 400)).append('\n');
                return null;
            }
            report.append("audio: ").append(out.getAbsolutePath()).append(" (").append(out.length()).append(" bytes)\n");
            return out;
        } catch (Exception e) {
            report.append("audio extract exception: ").append(e.getMessage()).append('\n');
            return null;
        }
    }

    private List<File> extractKeyframes(File mediaFile, Path workDir, double durationSec, StringBuilder report) {
        List<File> frames = new ArrayList<>();
        String name = mediaFile.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a") || name.endsWith(".opus")) {
            report.append("Media has no video track; vision skipped.\n");
            return frames;
        }

        int maxFrames = Math.max(1, properties.getMaxFrames());
        int interval = Math.max(5, properties.getFrameIntervalSeconds());
        if (durationSec > 0) {
            // Ensure we don't exceed maxFrames
            int byInterval = (int) Math.ceil(durationSec / interval);
            if (byInterval > maxFrames) {
                interval = (int) Math.max(5, Math.ceil(durationSec / maxFrames));
            }
            if (durationSec > properties.getMaxProcessSeconds()) {
                report.append("Note: video longer than maxProcessSeconds; sampling across full length with wider spacing.\n");
                interval = (int) Math.max(interval, Math.ceil(durationSec / maxFrames));
            }
        }

        Path frameDir = workDir.resolve("frames");
        try {
            Files.createDirectories(frameDir);
            // fps = 1/interval
            String fps = String.format(Locale.ROOT, "fps=1/%d", interval);
            List<String> cmd = ProcessRunner.cmd(
                    properties.getFfmpegPath(),
                    "-y",
                    "-i", mediaFile.getAbsolutePath(),
                    "-vf", fps,
                    "-q:v", "5",
                    "-frames:v", String.valueOf(maxFrames),
                    frameDir.resolve("frame_%03d.jpg").toString()
            );
            ProcessRunner.Result r = ProcessRunner.run(cmd, workDir.toFile(), Math.max(120, properties.getProcessTimeoutSeconds() / 2));
            if (!r.ok()) {
                report.append("ffmpeg keyframes failed: ").append(trim(r.combinedError(), 400)).append('\n');
            }
            try (Stream<Path> stream = Files.list(frameDir)) {
                stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jpg"))
                        .sorted()
                        .limit(maxFrames)
                        .forEach(p -> frames.add(p.toFile()));
            }
            report.append("keyframes: ").append(frames.size())
                    .append(" (interval≈").append(interval).append("s)\n");
        } catch (Exception e) {
            report.append("keyframe exception: ").append(e.getMessage()).append('\n');
        }
        return frames;
    }

    @SuppressWarnings("unchecked")
    public String transcribeAudioFile(File file) {
        if (file == null || !file.isFile()) {
            return "ASR error: audio file missing";
        }
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("your-") || apiKey.contains("missing-")) {
            return "ASR error: API key missing";
        }

        String asrBase = properties.getAsrBaseUrl();
        if (asrBase == null || asrBase.isBlank()) {
            // OpenCode Zen has no speech endpoint; without a dedicated ASR base, skip L2 cleanly.
            return "ASR skipped: ekina.video.asr-base-url is empty. "
                    + "OpenCode Zen does not provide /audio/transcriptions. "
                    + "Set EKINA_ASR_BASE_URL to an OpenAI-compatible speech API, or rely on L1 subtitles + L3 vision.";
        }

        // Chunk large files by size (~20MB safety)
        long maxBytes = 20L * 1024 * 1024;
        List<File> chunks = new ArrayList<>();
        try {
            if (file.length() > maxBytes) {
                chunks.addAll(splitAudio(file, file.getParentFile().toPath()));
            } else {
                chunks.add(file);
            }
        } catch (Exception e) {
            chunks.clear();
            chunks.add(file);
        }

        StringBuilder all = new StringBuilder();
        int idx = 1;
        for (File chunk : chunks) {
            try {
                LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                body.add("file", new org.springframework.core.io.FileSystemResource(chunk));
                body.add("model", properties.getAsrModel());

                String root = asrBase.trim();
                if (root.endsWith("/v1")) {
                    root = root.substring(0, root.length() - 3);
                }
                if (root.endsWith("/v1/")) {
                    root = root.substring(0, root.length() - 4);
                }

                Map<String, Object> response = restClient.post()
                        .uri(root + "/v1/audio/transcriptions")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body)
                        .retrieve()
                        .body(Map.class);

                if (response != null && response.get("text") != null) {
                    if (chunks.size() > 1) {
                        all.append("[chunk ").append(idx).append("] ");
                    }
                    all.append(response.get("text").toString().trim()).append('\n');
                } else {
                    all.append("[chunk ").append(idx).append(" empty]\n");
                }
            } catch (Exception e) {
                all.append("[chunk ").append(idx).append(" ASR failed: ").append(e.getMessage()).append("]\n");
            }
            idx++;
        }
        if (all.isEmpty()) {
            return "ASR returned empty text.";
        }
        return "Transcript (ASR):\n" + all.toString().trim();
    }

    private List<File> splitAudio(File audio, Path workDir) throws Exception {
        // Split into ~8 minute segments
        List<File> parts = new ArrayList<>();
        int segmentSec = 480;
        for (int i = 0; i < 20; i++) {
            File part = workDir.resolve(String.format(Locale.ROOT, "audio_part_%02d.mp3", i)).toFile();
            List<String> cmd = ProcessRunner.cmd(
                    properties.getFfmpegPath(),
                    "-y",
                    "-ss", String.valueOf(i * segmentSec),
                    "-t", String.valueOf(segmentSec),
                    "-i", audio.getAbsolutePath(),
                    "-ac", "1",
                    "-ar", "16000",
                    "-b:a", "64k",
                    part.getAbsolutePath()
            );
            ProcessRunner.Result r = ProcessRunner.run(cmd, workDir.toFile(), 180);
            if (!r.ok() || !part.isFile() || part.length() < 1000) {
                if (part.exists()) {
                    // empty last segment
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
                break;
            }
            parts.add(part);
        }
        return parts.isEmpty() ? List.of(audio) : parts;
    }

    private static class L1Result {
        final String text;
        final boolean hasSolidSubtitles;

        L1Result(String text, boolean hasSolidSubtitles) {
            this.text = text;
            this.hasSolidSubtitles = hasSolidSubtitles;
        }
    }

    @SuppressWarnings("unchecked")
    private L1Result fetchBilibiliL1(String bvid) {
        StringBuilder sb = new StringBuilder();
        boolean solidSubs = false;
        try {
            String responseJson = restClient.get()
                    .uri("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.bilibili.com")
                    .retrieve()
                    .body(String.class);

            Map<String, Object> response = mapper.readValue(responseJson, Map.class);
            int code = ((Number) response.get("code")).intValue();
            if (code != 0) {
                return new L1Result("Bilibili API error " + code + ": " + response.get("message"), false);
            }
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            sb.append("Title: ").append(data.get("title")).append('\n');
            if (data.get("owner") instanceof Map<?, ?> owner) {
                sb.append("Uploader: ").append(owner.get("name")).append('\n');
            }
            sb.append("Description: ").append(data.get("desc")).append('\n');
            if (data.get("tname") != null) {
                sb.append("Category: ").append(data.get("tname")).append('\n');
            }
            if (data.get("duration") != null) {
                sb.append("Duration(sec): ").append(data.get("duration")).append('\n');
            }

            Map<String, Object> subtitleMap = (Map<String, Object>) data.get("subtitle");
            List<Map<String, Object>> subtitleList = null;
            if (subtitleMap != null && subtitleMap.get("list") instanceof List<?> list) {
                subtitleList = (List<Map<String, Object>>) list;
            }

            String subtitleUrl = null;
            if (subtitleList != null && !subtitleList.isEmpty()) {
                for (Map<String, Object> sub : subtitleList) {
                    String lan = String.valueOf(sub.get("lan"));
                    if (lan.contains("zh") || lan.contains("hans") || lan.contains("cn")) {
                        subtitleUrl = String.valueOf(sub.get("subtitle_url"));
                        break;
                    }
                }
                if (subtitleUrl == null) {
                    subtitleUrl = String.valueOf(subtitleList.get(0).get("subtitle_url"));
                }
            }

            if (subtitleUrl != null && !subtitleUrl.isEmpty() && !"null".equals(subtitleUrl)) {
                if (subtitleUrl.startsWith("//")) {
                    subtitleUrl = "https:" + subtitleUrl;
                }
                try {
                    String subJson = restClient.get()
                            .uri(subtitleUrl)
                            .header("User-Agent", "Mozilla/5.0")
                            .retrieve()
                            .body(String.class);
                    Map<String, Object> subData = mapper.readValue(subJson, Map.class);
                    List<Map<String, Object>> body = (List<Map<String, Object>>) subData.get("body");
                    if (body != null && !body.isEmpty()) {
                        solidSubs = body.size() >= 8;
                        sb.append("\n--- Closed Captions ---\n");
                        StringBuilder caps = new StringBuilder();
                        for (Map<String, Object> item : body) {
                            double from = ((Number) item.get("from")).doubleValue();
                            String content = String.valueOf(item.get("content"));
                            caps.append("[").append(formatTime(from)).append("] ").append(content).append('\n');
                        }
                        sb.append(truncate(caps.toString(), properties.getMaxTranscriptChars()));
                    } else {
                        sb.append("\nNote: CC list empty.\n");
                    }
                } catch (Exception e) {
                    sb.append("\nWarning: failed to fetch CC: ").append(e.getMessage()).append('\n');
                }
            } else {
                sb.append("\nNote: No closed captions on this video.\n");
            }

            // comments
            if (data.get("aid") != null) {
                long aid = ((Number) data.get("aid")).longValue();
                try {
                    String replyJson = restClient.get()
                            .uri("https://api.bilibili.com/x/v2/reply/main?type=1&oid=" + aid + "&next=0&ps=12&mode=3")
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            .header("Referer", "https://www.bilibili.com")
                            .retrieve()
                            .body(String.class);
                    Map<String, Object> replyResponse = mapper.readValue(replyJson, Map.class);
                    if (replyResponse != null && ((Number) replyResponse.get("code")).intValue() == 0) {
                        Map<String, Object> replyData = (Map<String, Object>) replyResponse.get("data");
                        if (replyData != null && replyData.get("replies") instanceof List<?> replies) {
                            sb.append("\n--- Top Comments ---\n");
                            int count = 0;
                            for (Object o : replies) {
                                if (count >= 10) break;
                                Map<String, Object> reply = (Map<String, Object>) o;
                                if (reply.get("content") instanceof Map<?, ?> contentMap) {
                                    Object msg = contentMap.get("message");
                                    if (msg != null && !msg.toString().isBlank()) {
                                        sb.append("- ").append(msg.toString().trim().replace("\n", " ")).append('\n');
                                        count++;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            return new L1Result("Failed L1 bilibili fetch: " + e.getMessage(), false);
        }
        return new L1Result(sb.toString(), solidSubs);
    }

    private String fetchGenericMeta(String url) {
        try {
            List<String> cmd = new ArrayList<>(ytdlpPrefix());
            cmd.addAll(List.of("--skip-download", "--print", "%(title)s\n%(uploader)s\n%(duration)s\n%(description).500s", url));
            ProcessRunner.Result r = ProcessRunner.run(cmd, null, 60);
            if (r.ok()) {
                return "yt-dlp metadata:\n" + r.stdout.trim();
            }
            return "metadata unavailable: " + trim(r.combinedError(), 300);
        } catch (Exception e) {
            return "metadata error: " + e.getMessage();
        }
    }

    public static String extractBvid(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = BVID.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (url.trim().matches("(?i)BV[a-zA-Z0-9]{10}")) {
            return url.trim();
        }
        return null;
    }

    private String normalizeWatchUrl(String urlOrPath, String bvid) {
        if (bvid != null) {
            return "https://www.bilibili.com/video/" + bvid;
        }
        return urlOrPath;
    }

    private boolean isHttp(String s) {
        if (s == null) return false;
        String t = s.toLowerCase(Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://");
    }

    private File resolveLocal(String path) {
        if (path == null || isHttp(path) || extractBvid(path) != null) {
            return null;
        }
        File f = new File(path);
        if (f.isFile()) {
            return f;
        }
        File inSandbox = new File(sandboxDir, path);
        if (inSandbox.isFile()) {
            return inSandbox;
        }
        return null;
    }

    private static String formatTime(double seconds) {
        int s = (int) seconds;
        int h = s / 3600;
        int m = (s % 3600) / 60;
        int sec = s % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, sec);
        }
        return String.format(Locale.ROOT, "%02d:%02d", m, sec);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n...[truncated]...\n";
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}
