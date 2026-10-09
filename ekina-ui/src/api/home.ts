import { api, USER_ID } from "./client";

export type UsageWindow = {
  status?: string;
  percent?: number;
  remainingPercent?: number;
  resetsAt?: string;
};

export type ProviderBalance = {
  ok?: boolean;
  provider?: string;
  label?: string;
  error?: string;
  hint?: string;
  available?: boolean;
  currency?: string;
  total?: string;
  granted?: string;
  toppedUp?: string;
  hottestWindow?: string;
  hottestPercent?: number;
  windows?: {
    rolling?: UsageWindow;
    weekly?: UsageWindow;
    monthly?: UsageWindow;
  };
};

export type LlmBalances = {
  fetchedAt?: string;
  deepseek?: ProviderBalance;
  opencode?: ProviderBalance;
};

export type LlmStatus = {
  baseUrl?: string;
  model?: string;
  protocol?: string;
  hasApiKey?: boolean;
  apiKeyTail?: string;
  ready?: boolean;
  chiefProvider?: string;
  researchProvider?: string;
  deepseekChatModel?: string;
  deepseekReasonerModel?: string;
  hasDeepseekKey?: boolean;
  deepseekKeyTail?: string;
  routing?: string;
  balances?: LlmBalances;
  deepseekPeak?: boolean;
  skipDeepseekPeak?: boolean;
  peakAltModel?: string;
  deepseekWindow?: string;
  activeProvider?: string;
};

export async function fetchAutonomy(): Promise<boolean> {
  const { data } = await api.get<Record<string, unknown>>("/api/home/autonomy");
  return Boolean(data?.enabled ?? data?.on);
}

export async function setAutonomy(enable: boolean): Promise<void> {
  await api.post("/api/home/autonomy", {
    userId: USER_ID,
    action: enable ? "enable" : "disable",
  });
}

export async function fetchLlmStatus(refresh = false): Promise<LlmStatus> {
  const { data } = await api.get<LlmStatus>("/api/home/llm", {
    params: refresh ? { refresh: true } : undefined,
  });
  return data || {};
}

export type CfgpuGpuLive = {
  ok?: boolean;
  busy?: boolean;
  unreachable?: boolean;
  maxUtil?: number;
  error?: string;
  hint?: string;
  sshHost?: string;
  gpus?: { index?: string; name?: string; util?: number; memUsedMb?: number; memTotalMb?: number }[];
  apps?: { pid?: string; process?: string; memMb?: string }[];
};

export type CfgpuInstance = {
  instanceId?: string;
  name?: string;
  status?: string;
  gpu?: string;
  gpuNum?: string;
  region?: string;
  priceType?: string;
  sshHost?: string;
  sshPort?: string | number;
  sshUser?: string;
  computeHostId?: string;
  gpuLive?: CfgpuGpuLive;
};

export type CfgpuStatus = {
  provider?: string;
  label?: string;
  console?: string;
  configured?: boolean;
  ok?: boolean;
  hint?: string;
  error?: string;
  fetchedAt?: string;
  running?: number;
  stopped?: number;
  instances?: CfgpuInstance[];
};

export async function fetchCfgpuStatus(probe = false): Promise<CfgpuStatus> {
  const { data } = await api.get<CfgpuStatus>("/api/home/cfgpu", {
    params: probe ? { probe: true } : undefined,
  });
  return data || {};
}

export async function controlCfgpuInstance(
  action: "start" | "stop",
  instanceId: string,
  force = false,
): Promise<Record<string, unknown>> {
  const { data } = await api.post<Record<string, unknown>>("/api/home/cfgpu", {
    action,
    instanceId,
    force,
  });
  return data || {};
}
