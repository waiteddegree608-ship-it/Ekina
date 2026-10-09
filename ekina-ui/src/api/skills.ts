import { api } from "./client";

export type PlaybookSkill = {
  id: string;
  title: string;
  path?: string;
  tools?: string[];
  triggers?: string[];
};

export async function fetchPlaybookSkills(): Promise<PlaybookSkill[]> {
  const { data } = await api.get<PlaybookSkill[]>("/api/skills");
  return Array.isArray(data) ? data : [];
}
