import { api } from "./client";

export type PaperAuditCheck = {
  name?: string;
  ok?: boolean;
  detail?: string;
};

export type PaperAudit = {
  pass?: boolean;
  score?: number;
  maxScore?: number;
  verdict?: string;
  checks?: PaperAuditCheck[];
  paperDir?: string;
};

export type PaperFigure = {
  name?: string;
  url?: string;
};

export type PaperPreview = {
  projectId?: string;
  slug?: string;
  paperDir?: string;
  coverUrl?: string;
  coverName?: string;
  figures?: PaperFigure[];
  audit?: PaperAudit;
  hasPaper?: boolean;
};

export async function fetchPaperPreview(projectId?: string, slug?: string): Promise<PaperPreview> {
  const { data } = await api.get<PaperPreview>("/api/lab/papers/preview", {
    params: { projectId, slug },
  });
  return data || {};
}
