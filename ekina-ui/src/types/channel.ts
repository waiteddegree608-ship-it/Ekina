export type Contact = {
  id: string;
  displayName?: string;
  signature?: string;
  threadId?: string;
  avatarUrl?: string;
  toolProfile?: string;
  personaProfile?: string;
  lastPreview?: string;
  lastTs?: string;
};

export type ChannelMessage = {
  id: string;
  type?: string;
  sender?: string;
  text?: string;
  ts?: string;
  status?: string;
  threadId?: string;
  payload?: Record<string, unknown>;
};

export type ChatBubble = {
  id: string;
  side: "me" | "her" | "sys";
  text: string;
  ts?: string;
  roleId?: string;
  kind?: "chat" | "permission" | "reminder" | "plan" | "system" | "notice" | "report";
  quotable?: boolean;
  raw?: ChannelMessage;
};

export type ComposerQuote = {
  id: string;
  text: string;
  ts?: string;
};
