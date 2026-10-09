import { defineStore } from "pinia";
import { ref, watch } from "vue";
import { fetchUiPrefs, saveUiPrefs } from "@/api/prefs";

export type ThemeId = "paper" | "dark" | "ocean" | "forest" | "dusk";

export const THEMES: { id: ThemeId; label: string; swatch: string }[] = [
  { id: "paper", label: "纸感浅色", swatch: "#f6f5f2" },
  { id: "dark", label: "墨色深色", swatch: "#111110" },
  { id: "ocean", label: "海雾", swatch: "#e8f1f5" },
  { id: "forest", label: "苔原", swatch: "#eef3ea" },
  { id: "dusk", label: "暮蓝", swatch: "#1a1f2e" },
];

const LOCAL_KEY = "ekina.prefs.v1";

type PrefsBlob = {
  theme: ThemeId;
  noEmoji: boolean;
  crewCollapsed: boolean;
};

function applyTheme(id: ThemeId) {
  const root = document.documentElement;
  root.setAttribute("data-theme", id);
  root.classList.toggle("dark", id === "dark" || id === "dusk");
}

function readLocal(): PrefsBlob {
  try {
    const raw = localStorage.getItem(LOCAL_KEY);
    if (raw) {
      const j = JSON.parse(raw) as Partial<PrefsBlob>;
      return {
        theme: THEMES.some((x) => x.id === j.theme) ? (j.theme as ThemeId) : "paper",
        noEmoji: Boolean(j.noEmoji),
        crewCollapsed: Boolean(j.crewCollapsed),
      };
    }
  } catch {
    /* ignore */
  }
  // migrate legacy keys
  const legacyTheme = (localStorage.getItem("ekina.theme") || "paper") as ThemeId;
  return {
    theme: THEMES.some((x) => x.id === legacyTheme) ? legacyTheme : "paper",
    noEmoji: localStorage.getItem("ekina.noEmoji") === "1",
    crewCollapsed: localStorage.getItem("ekina.crewCollapsed") === "1",
  };
}

function writeLocal(blob: PrefsBlob) {
  try {
    localStorage.setItem(LOCAL_KEY, JSON.stringify(blob));
  } catch {
    /* ignore */
  }
}

export const usePrefsStore = defineStore("prefs", () => {
  const theme = ref<ThemeId>("paper");
  const noEmoji = ref(false);
  const crewCollapsed = ref(false);
  const settingsOpen = ref(false);
  let syncTimer: number | undefined;
  let ready = false;

  function snapshot(): PrefsBlob {
    return {
      theme: theme.value,
      noEmoji: noEmoji.value,
      crewCollapsed: crewCollapsed.value,
    };
  }

  function persist() {
    const blob = snapshot();
    writeLocal(blob);
    window.clearTimeout(syncTimer);
    syncTimer = window.setTimeout(() => {
      void saveUiPrefs(blob).catch(() => undefined);
    }, 200);
  }

  function applyBlob(blob: PrefsBlob) {
    theme.value = blob.theme;
    noEmoji.value = blob.noEmoji;
    crewCollapsed.value = blob.crewCollapsed;
    applyTheme(blob.theme);
  }

  async function boot() {
    const local = readLocal();
    applyBlob(local);
    ready = true;
    try {
      const remote = await fetchUiPrefs();
      const merged: PrefsBlob = {
        theme: THEMES.some((x) => x.id === remote.theme) ? (remote.theme as ThemeId) : local.theme,
        noEmoji: typeof remote.noEmoji === "boolean" ? remote.noEmoji : local.noEmoji,
        crewCollapsed:
          typeof remote.crewCollapsed === "boolean" ? remote.crewCollapsed : local.crewCollapsed,
      };
      applyBlob(merged);
      writeLocal(merged);
    } catch {
      /* offline — local is enough */
    }
  }

  function setTheme(id: ThemeId) {
    theme.value = id;
    applyTheme(id);
    if (ready) persist();
  }

  function setNoEmoji(v: boolean) {
    noEmoji.value = v;
    if (ready) persist();
  }

  function setCrewCollapsed(v: boolean) {
    crewCollapsed.value = v;
    if (ready) persist();
  }

  watch(theme, (id) => applyTheme(id));

  return {
    theme,
    noEmoji,
    crewCollapsed,
    settingsOpen,
    boot,
    setTheme,
    setNoEmoji,
    setCrewCollapsed,
  };
});
