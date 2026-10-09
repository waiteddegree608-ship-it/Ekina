import { api, USER_ID } from "./client";
import type { CollaborationSettings, ProjectBoard, ProjectTimeline, ProjectView, ResearchRecord } from "@/types/project";

export async function fetchProjects(): Promise<ProjectView[]> {
  const { data } = await api.get<ProjectView[]>("/api/projects", {
    params: { userId: USER_ID },
  });
  return Array.isArray(data) ? data : [];
}

/** Owner dock only — portfolios / papers for one contact, not internal micro-tasks. */
export async function fetchDockProjects(contactId = "ekina"): Promise<ProjectView[]> {
  const { data } = await api.get<ProjectView[]>("/api/projects/dock", {
    params: { userId: USER_ID, contactId },
  });
  return Array.isArray(data) ? data : [];
}

export async function fetchProjectTimeline(id: string): Promise<ProjectTimeline> {
  const { data } = await api.get<ProjectTimeline>(`/api/projects/${encodeURIComponent(id)}/timeline`);
  return data || { error: "empty" };
}

export async function fetchProjectBoard(id: string): Promise<ProjectBoard> {
  const { data } = await api.get<ProjectBoard>(`/api/projects/${encodeURIComponent(id)}/board`);
  return data || { error: "empty" };
}

export async function saveCollaboration(id: string, settings: CollaborationSettings): Promise<ProjectBoard> {
  const { data } = await api.put<ProjectBoard>(`/api/projects/${encodeURIComponent(id)}/collaboration`, settings);
  return data;
}

export async function submitGuidance(id: string, target: string, text: string, requestId: string): Promise<ProjectBoard> {
  const { data } = await api.post<ProjectBoard>(`/api/projects/${encodeURIComponent(id)}/guidance`, { target, text, requestId });
  return data;
}

export async function appendResearchRecords(id: string, expectedRevision: number, records: ResearchRecord[]): Promise<ProjectBoard> {
  const { data } = await api.post<ProjectBoard>(`/api/projects/${encodeURIComponent(id)}/research-records`, { expectedRevision, records });
  return data;
}

export async function createResearchProject(body: { brief: string; entry: "idea" | "explore"; collaboration: CollaborationSettings; requestId: string }): Promise<ProjectView> {
  const { data } = await api.post<ProjectView>("/api/projects/research", { ...body, userId: USER_ID });
  return data;
}
