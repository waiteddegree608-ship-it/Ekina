import { api, USER_ID } from "./client";
import type { ChannelMessage } from "@/types/channel";

export async function fetchChannelSince(params: {
  threadId: string;
  afterTs?: string;
  limit?: number;
}): Promise<ChannelMessage[]> {
  const { data } = await api.get<ChannelMessage[]>("/api/channel/since", {
    params: {
      userId: USER_ID,
      threadId: params.threadId || "daily",
      afterTs: params.afterTs || "",
      limit: params.limit ?? 50,
    },
  });
  return Array.isArray(data) ? data : [];
}

export async function fetchChannelRecent(threadId: string, limit = 5): Promise<ChannelMessage[]> {
  const { data } = await api.get<ChannelMessage[]>("/api/channel", {
    params: { userId: USER_ID, threadId, limit },
  });
  return Array.isArray(data) ? data : [];
}

export async function fetchPending(): Promise<ChannelMessage[]> {
  const { data } = await api.get<ChannelMessage[]>("/api/channel/pending", {
    params: { userId: USER_ID },
  });
  return Array.isArray(data) ? data : [];
}

export async function channelAction(body: {
  id: string;
  status: string;
  payload?: Record<string, unknown>;
}): Promise<ChannelMessage> {
  const { data } = await api.post<ChannelMessage>("/api/channel/action", {
    userId: USER_ID,
    ...body,
  });
  return data;
}
