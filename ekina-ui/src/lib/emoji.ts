/** Strip emoji / pictographs from AI text when user opts out. */
export function stripEmojis(text: string): string {
  if (!text) return "";
  return text
    .replace(/\p{Extended_Pictographic}/gu, "")
    .replace(/\uFE0F/g, "")
    .replace(/[\u{1F3FB}-\u{1F3FF}]/gu, "")
    .replace(/\u200D/g, "")
    .replace(/[ \t]+\n/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .replace(/[ \t]{2,}/g, " ")
    .trim();
}

export function maybeStripEmojis(text: string, enabled: boolean): string {
  return enabled ? stripEmojis(text) : text;
}
