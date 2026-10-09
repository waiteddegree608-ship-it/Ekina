import { api, USER_ID } from "./client";

export type ChatResponse = {
  answer?: string;
  message?: string;
  reply?: string;
  response?: string;
};

export async function sendChat(body: {
  message: string;
  contactId: string;
  threadId: string;
  projectId?: string;
}): Promise<string> {
  const { data } = await api.post<ChatResponse>("/api/chat", {
    userId: USER_ID,
    message: body.message,
    contactId: body.contactId,
    threadId: body.threadId,
    // Keep dialogue thread; optional focused project for @续作
    projectId: body.projectId || body.threadId,
  });
  return data.answer || data.message || data.reply || data.response || String(data);
}
