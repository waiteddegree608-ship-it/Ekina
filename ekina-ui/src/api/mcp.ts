import { api } from "./client";

export type McpServer = {
  name: string;
  type?: string;
  command?: string;
  args?: string[];
  url?: string;
  status?: string;
  tools?: string[];
};

export type ToolSkill = {
  name: string;
  description?: string;
  parameters?: Record<string, unknown> | null;
};

export async function fetchMcpServers(): Promise<McpServer[]> {
  const { data } = await api.get<McpServer[]>("/api/mcp/servers");
  return Array.isArray(data) ? data : [];
}

/** AgentToolbox tool specs (legacy path name /api/mcp/skills). */
export async function fetchToolboxSkills(): Promise<ToolSkill[]> {
  const { data } = await api.get<ToolSkill[]>("/api/mcp/skills");
  return Array.isArray(data) ? data : [];
}
