import { api } from "./client";

export type AgentRootsResponse = {
  ok?: boolean;
  builtin?: { path: string; exists?: boolean; builtin?: boolean }[];
  custom?: { path: string; exists?: boolean; builtin?: boolean }[];
  roots?: { path: string; exists?: boolean }[];
  saved?: string[];
  rejected?: { path: string; reason?: string }[];
  hint?: string;
};

export async function fetchAgentRoots(): Promise<AgentRootsResponse> {
  const { data } = await api.get<AgentRootsResponse>("/api/agent/roots");
  return data || {};
}

export async function saveAgentRoots(text: string): Promise<AgentRootsResponse> {
  const { data } = await api.put<AgentRootsResponse>("/api/agent/roots", { text });
  return data || {};
}
