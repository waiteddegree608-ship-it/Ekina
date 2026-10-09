import { api } from "./client";

export type UiPrefs = {
  theme?: string;
  noEmoji?: boolean;
  crewCollapsed?: boolean;
};

export async function fetchUiPrefs(): Promise<UiPrefs> {
  const { data } = await api.get<UiPrefs>("/api/ui-prefs");
  return data || {};
}

export async function saveUiPrefs(patch: UiPrefs): Promise<UiPrefs> {
  const { data } = await api.put<UiPrefs>("/api/ui-prefs", patch);
  return data || patch;
}
