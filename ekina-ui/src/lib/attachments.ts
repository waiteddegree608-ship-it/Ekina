export type ChatAttachment = {
  name: string;
  path: string;
  ext: string;
};

const ATTACH_BLOCK = /【附件】\s*\n([\s\S]*?)(?:\n\n|$)/;
const ATTACH_LINE = /^\s*[-*]\s*(.+?):\s*([A-Za-z]:[\\/].+?|\/.+?)\s*$/gm;

export function parseAttachments(text: string): { body: string; files: ChatAttachment[] } {
  if (!text) return { body: "", files: [] };
  const files: ChatAttachment[] = [];
  let body = text;
  const block = text.match(ATTACH_BLOCK);
  if (block) {
    const chunk = block[1] || "";
    let m: RegExpExecArray | null;
    const re = new RegExp(ATTACH_LINE.source, "gm");
    while ((m = re.exec(chunk))) {
      const name = m[1].trim();
      const path = m[2].trim().replace(/\\/g, "/");
      const ext = (name.split(".").pop() || "").toLowerCase();
      files.push({ name, path, ext });
    }
    body = text.replace(block[0], "").trim();
  }
  return { body, files };
}

export function fileKindLabel(ext: string): string {
  if (ext === "pdf") return "PDF";
  if (ext === "docx" || ext === "doc") return "Word";
  if (ext === "xlsx" || ext === "xls") return "Excel";
  if (ext === "pptx" || ext === "ppt") return "PPT";
  if (ext === "png" || ext === "jpg" || ext === "jpeg" || ext === "gif" || ext === "webp") return "图片";
  if (ext === "md" || ext === "txt") return "文本";
  return (ext || "文件").toUpperCase();
}

/** Strip autonomy tick slogans from bubbles shown to the owner. Preserve newlines for Markdown. */
export function sanitizeOwnerText(text: string): string {
  if (!text) return "";
  return text
    .replace(/#*\s*tick\s*#?\d+\s*[·•\-:：]?\s*/gi, "")
    .replace(/我刚才做了什么/g, "")
    .replace(/姬娜刚才做了什么/g, "")
    .replace(/[^\S\n]{2,}/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

/** One-line preview for contact rail: no MD noise, no autonomy digests. */
export function previewPlainText(text: string, max = 48): string {
  if (!text) return "";
  let t = text.replace(/\r\n/g, "\n");
  if (/姬娜刚才做了什么|我刚才做了什么|自主巡查|tick\s*#?\d+/i.test(t)) {
    return "";
  }
  if (t.includes("【附件】")) {
    t = t.replace(/【附件】[\s\S]*/, "").trim() || "发送了附件";
  }
  t = t
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/`([^`]+)`/g, "$1")
    .replace(/!\[[^\]]*]\([^)]*\)/g, " ")
    .replace(/\[([^\]]*)]\([^)]*\)/g, "$1")
    .replace(/^#{1,6}\s+/gm, "")
    .replace(/^\s*[-*+]\s+/gm, "")
    .replace(/^\s*\d+\.\s+/gm, "")
    .replace(/\|/g, " ")
    .replace(/\*{1,3}([^*]+)\*{1,3}/g, "$1")
    .replace(/_{1,3}([^_]+)_{1,3}/g, "$1")
    .replace(/~~([^~]+)~~/g, "$1")
    .replace(/^\s*>+\s?/gm, "")
    .replace(/[-*]{3,}/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!t) return "";
  return t.length > max ? t.slice(0, max) + "…" : t;
}
