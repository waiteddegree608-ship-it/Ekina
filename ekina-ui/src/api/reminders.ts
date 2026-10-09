import { api, USER_ID } from "./client";
import type { ChannelMessage } from "@/types/channel";

export async function ackReminder(messageId: string, action = "ack"): Promise<ChannelMessage> {
  const { data } = await api.post<ChannelMessage>("/api/reminders/ack", {
    userId: USER_ID,
    messageId,
    action,
  });
  return data;
}
