import { api, USER_ID } from "./client";

export type CapabilityGrant = {
  id: string;
  kind?: string;
  userId?: string;
  status?: string;
  grantedAt?: string;
  expiresAt?: string;
  scope?: Record<string, unknown>;
  note?: string;
};

export async function decidePermission(body: {
  messageId: string;
  decision: "approved" | "denied";
  comment?: string;
}): Promise<Record<string, unknown>> {
  const { data } = await api.post<Record<string, unknown>>("/api/permissions/decide", {
    userId: USER_ID,
    messageId: body.messageId,
    decision: body.decision,
    ttlHours: 168,
    scope: body.comment ? { comment: body.comment } : null,
  });
  return data;
}

export async function fetchCapabilities(): Promise<CapabilityGrant[]> {
  const { data } = await api.get<CapabilityGrant[]>("/api/permissions/capabilities", {
    params: { userId: USER_ID },
  });
  return Array.isArray(data) ? data : [];
}
