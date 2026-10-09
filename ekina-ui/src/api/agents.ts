import { api, USER_ID } from "./client";

export type RoleLive = {
  badge?: string;
  detail?: string;
  intent?: string;
  summary?: string;
  autonomy?: boolean;
  tickRunning?: boolean;
  title?: string;
  brief?: string;
  status?: string;
  taskId?: string;
  stdoutTail?: string;
  error?: string;
  elapsedSec?: number;
};

export type AgentsLive = {
  chief?: RoleLive;
  coder?: RoleLive;
  scribe?: RoleLive;
  claw?: RoleLive;
  running?: number;
  queued?: number;
  [key: string]: unknown;
};

export async function fetchAgentsLive(): Promise<AgentsLive> {
  const { data } = await api.get<AgentsLive>("/api/agents/live", {
    params: { userId: USER_ID },
  });
  return data || {};
}

export async function cancelStuckTasks(olderThanSec = 60): Promise<Record<string, unknown>> {
  const { data } = await api.post<Record<string, unknown>>("/api/agents/tasks/cancel-stuck", {
    userId: USER_ID,
    olderThanSec,
  });
  return data || {};
}
