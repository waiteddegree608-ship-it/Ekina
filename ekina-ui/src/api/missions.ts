import { api, USER_ID } from "./client";

export type MissionDto = {
  id?: string;
  goal?: string;
  status?: string;
  nodes?: unknown[];
};

export async function createMission(goal: string): Promise<MissionDto> {
  const { data } = await api.post<MissionDto>("/api/missions", {
    userId: USER_ID,
    goal,
  });
  return data;
}

export async function createMissions(goals: string[]): Promise<{
  created?: number;
  missions?: MissionDto[];
  queuedHint?: string;
}> {
  const { data } = await api.post("/api/missions", {
    userId: USER_ID,
    goals,
  });
  return data || {};
}
