import { marked } from "marked";
import DOMPurify from "dompurify";

marked.setOptions({ breaks: true, gfm: true });

/** Ensure GFM tables have a blank line before them so marked parses them. */
function preprocessTables(text: string): string {
  const lines = text.replace(/\r\n/g, "\n").split("\n");
  const out: string[] = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const next = lines[i + 1] || "";
    const isTableRow = /^\s*\|.+\|\s*$/.test(line);
    const nextIsSep = /^\s*\|?\s*:?-{3,}.*\|\s*$/.test(next);
    if (isTableRow && nextIsSep) {
      const prev = out[out.length - 1];
      if (prev !== undefined && prev.trim() !== "") out.push("");
    }
    out.push(line);
  }
  return out.join("\n");
}

/** Wrap tables so wide content scrolls without breaking chat layout. */
function wrapTables(html: string): string {
  return html.replace(/<table[\s\S]*?<\/table>/gi, (table) => `<div class="table-wrap">${table}</div>`);
}

export function renderMarkdown(text: string): string {
  if (!text) return "";
  try {
    const html = marked.parse(preprocessTables(text), { async: false }) as string;
    return DOMPurify.sanitize(wrapTables(html), {
      ADD_ATTR: ["target", "rel", "class"],
      ADD_TAGS: ["table", "thead", "tbody", "tr", "th", "td"],
    });
  } catch {
    return text
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll("\n", "<br>");
  }
}
