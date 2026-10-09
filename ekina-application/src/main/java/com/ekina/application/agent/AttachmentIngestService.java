package com.ekina.application.agent;

import com.ekina.domain.rag.DocumentParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pull local attachment paths out of chat messages and extract readable text (PDF/DOCX/…).
 */
@Service
public class AttachmentIngestService {

    private static final Pattern ATTACH_LINE = Pattern.compile(
            "(?m)^\\s*[-*]\\s*(.+?):\\s*([A-Za-z]:[/\\\\][^\\n]+|/[^\\n]+)\\s*$");
    private static final Pattern BARE_PATH = Pattern.compile(
            "(?i)([A-Za-z]:[/\\\\][^\\s\"']+\\.(?:pdf|docx|xlsx|txt|md)|/[^\\s\"']+\\.(?:pdf|docx|xlsx|txt|md))");

    @Autowired
    private DocumentParser documentParser;

    public List<Map<String, String>> listAttachments(String message) {
        List<Map<String, String>> out = new ArrayList<>();
        if (message == null || message.isBlank()) return out;
        Matcher m = ATTACH_LINE.matcher(message);
        while (m.find()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("name", m.group(1).trim());
            row.put("path", m.group(2).trim().replace("\\", "/"));
            out.add(row);
        }
        if (out.isEmpty()) {
            Matcher b = BARE_PATH.matcher(message);
            while (b.find()) {
                String path = b.group(1).replace("\\", "/");
                Map<String, String> row = new LinkedHashMap<>();
                row.put("name", Path.of(path).getFileName().toString());
                row.put("path", path);
                out.add(row);
            }
        }
        return out;
    }

    /**
     * Build a model-facing block with extracted text so the agent cannot claim “不能读 PDF”.
     */
    public String buildContextBlock(String message) {
        List<Map<String, String>> files = listAttachments(message);
        if (files.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n[系统已解析主人附件 — 请直接基于下列正文回答；禁止再说无法读 PDF/需要主人转 md]\n");
        for (Map<String, String> f : files) {
            String path = f.get("path");
            String name = f.get("name");
            sb.append("\n--- 附件: ").append(name).append(" ---\n");
            sb.append("路径: ").append(path).append("\n");
            sb.append(extractPreview(path, 14000));
            sb.append("\n");
        }
        return sb.toString();
    }

    public String extractPreview(String filePath, int maxChars) {
        if (filePath == null || filePath.isBlank()) return "(空路径)";
        Path p = Path.of(filePath);
        if (!Files.isRegularFile(p)) {
            return "(文件不存在: " + filePath + ")";
        }
        String lower = filePath.toLowerCase(Locale.ROOT);
        try {
            String text;
            if (lower.endsWith(".pdf")) {
                try (InputStream in = Files.newInputStream(p)) {
                    text = documentParser.parsePdf(in);
                }
            } else if (lower.endsWith(".docx")) {
                try (InputStream in = Files.newInputStream(p)) {
                    text = documentParser.parseDocx(in);
                }
            } else if (lower.endsWith(".xlsx")) {
                try (InputStream in = Files.newInputStream(p)) {
                    text = documentParser.parseXlsx(in);
                }
            } else if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json")
                    || lower.endsWith(".csv") || lower.endsWith(".py") || lower.endsWith(".java")) {
                text = Files.readString(p, StandardCharsets.UTF_8);
            } else {
                return "(暂不自动提取此格式；可用 readDocument 工具再试)";
            }
            if (text == null || text.isBlank()) {
                return "(未能提取到文本：可能是扫描版 PDF。可说明情况并建议 OCR，但仍应尝试 readDocument。)";
            }
            String one = text.replace("\r\n", "\n").trim();
            int cap = Math.max(2000, maxChars);
            if (one.length() > cap) {
                return one.substring(0, cap) + "\n…(已截断，全文可用 readDocument(\"" + filePath.replace("\\", "/") + "\"))\n";
            }
            return one;
        } catch (Exception e) {
            return "(提取失败: " + e.getMessage() + " — 请再调用 readDocument)";
        }
    }
}
