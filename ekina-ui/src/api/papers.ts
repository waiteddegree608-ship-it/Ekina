import { api, USER_ID } from "./client";

export type PaperProjectDto = {
  id?: string;
  titleWorking?: string;
  domain?: string;
  mode?: string;
  status?: string;
  stage?: string;
  seedRepo?: string;
  venueTier?: string;
};

export type StartPaperBody = {
  domain?: string;
  titleHint?: string;
  mode?: "smoke" | "research" | "reproduce";
  seedRepo?: string;
  venueTier?: string;
  autoRun?: boolean;
};

export async function startPaperPipeline(body: StartPaperBody): Promise<PaperProjectDto> {
  const { data } = await api.post<PaperProjectDto>("/api/papers", {
    userId: USER_ID,
    domain: body.domain || body.titleHint || "general ML",
    titleHint: body.titleHint || body.domain || "",
    mode: body.mode || "research",
    seedRepo: body.seedRepo || undefined,
    venueTier: body.venueTier || "CCF-C",
    autoRun: body.autoRun !== false,
  });
  return data || {};
}

export async function fetchPaper(id: string): Promise<PaperProjectDto> {
  const { data } = await api.get<PaperProjectDto>(`/api/papers/${encodeURIComponent(id)}`);
  return data || {};
}
