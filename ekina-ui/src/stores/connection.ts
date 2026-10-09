import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { fetchAgentsLive, type AgentsLive, type RoleLive } from "@/api/agents";
import { fetchMcpServers, type McpServer } from "@/api/mcp";
import { fetchPlaybookSkills, type PlaybookSkill } from "@/api/skills";
import { fetchCapabilities, type CapabilityGrant } from "@/api/permissions";
import { fetchCfgpuStatus, fetchLlmStatus, type CfgpuStatus, type LlmStatus } from "@/api/home";

export type GlowStatus = "ok" | "warn" | "off";

function badgeBusy(badge?: string) {
  if (!badge) return false;
  return /忙|执行|调度|排队|生活中/.test(badge) && !/待命/.test(badge);
}

function roleStatus(role?: RoleLive): "idle" | "running" | "waiting" | "done" | "failed" {
  const b = role?.badge || "";
  const s = (role?.status || "").toLowerCase();
  if (s === "running" || /执行|忙|调度|生活中·忙/.test(b)) return "running";
  if (s === "queued" || /排队/.test(b)) return "waiting";
  if (s === "succeeded" || /刚完成|完成/.test(b)) return "done";
  if (s === "failed" || s === "cancelled" || /失败/.test(b)) return "failed";
  return "idle";
}

export const useConnectionStore = defineStore("connection", () => {
  const live = ref<AgentsLive>({});
  const mcpServers = ref<McpServer[]>([]);
  const playbooks = ref<PlaybookSkill[]>([]);
  const capabilities = ref<CapabilityGrant[]>([]);
  const llm = ref<LlmStatus>({});
  const cfgpu = ref<CfgpuStatus>({});
  const loadedExt = ref(false);
  const error = ref("");

  const glowModel = computed<GlowStatus>(() => {
    if (!llm.value.ready) return "warn";
    const ds = llm.value.balances?.deepseek;
    const oc = llm.value.balances?.opencode;
    if (ds && ds.ok === false) return "warn";
    if (ds && ds.available === false) return "warn";
    const total = Number(ds?.total ?? NaN);
    if (Number.isFinite(total) && total < 8) return "warn";
    const hot = Number(oc?.hottestPercent ?? 0);
    if (hot >= 80) return "warn";
    if (oc && oc.ok === false) return "warn";
    return "ok";
  });

  const balanceSummary = computed(() => {
    const ds = llm.value.balances?.deepseek;
    const oc = llm.value.balances?.opencode;
    const parts: string[] = [];
    if (ds?.ok && ds.total != null) {
      const cur = ds.currency === "USD" ? "$" : "¥";
      parts.push(`DS ${cur}${ds.total}`);
    }
    if (oc?.ok && oc.windows?.monthly) {
      parts.push(`OC 月余${oc.windows.monthly.remainingPercent ?? 100}%`);
    }
    return parts.join(" · ");
  });

  const glowServer = computed<GlowStatus>(() => {
    const ssh = capabilities.value.filter(
      (c) => c.kind === "ssh_remote" && c.status === "approved",
    );
    const withHost = ssh.some((c) => c.scope && (c.scope.host || c.scope.hostname));
    if (cfgpu.value.configured && cfgpu.value.ok) return "ok";
    if (withHost) return "ok";
    if (ssh.length || cfgpu.value.configured) return "warn";
    return "warn";
  });

  const glowApps = computed<GlowStatus>(() => {
    // No dedicated app registry API yet — soft ok when online capabilities load
    return loadedExt.value ? "ok" : "off";
  });

  const glowExt = computed<GlowStatus>(() => {
    const mcpOk = mcpServers.value.some((s) => (s.status || "").toUpperCase() === "RUNNING");
    if (playbooks.value.length || mcpOk) return "ok";
    if (loadedExt.value) return "warn";
    return "off";
  });

  const crewBusy = computed(() => {
    const running = Number(live.value.running || 0);
    const queued = Number(live.value.queued || 0);
    return (
      running + queued > 0 ||
      badgeBusy(live.value.chief?.badge) ||
      badgeBusy(live.value.coder?.badge) ||
      badgeBusy(live.value.claw?.badge)
    );
  });

  const busyLabel = computed(() => {
    const chief = live.value.chief;
    const coder = live.value.coder;
    if (coder && badgeBusy(coder.badge)) {
      return `OpenCode · ${coder.detail || coder.title || coder.badge}`;
    }
    if (chief && badgeBusy(chief.badge)) {
      return `伊姬娜 · ${chief.detail || chief.intent || chief.badge}`;
    }
    if (live.value.claw && badgeBusy(live.value.claw.badge)) {
      return `OpenClaw · ${live.value.claw.detail || live.value.claw.badge}`;
    }
    return "";
  });

  const crewNodes = computed(() => {
    const chief = live.value.chief || {};
    const coder = live.value.coder || {};
    const scribe = live.value.scribe || {};
    const claw = live.value.claw || {};
    return [
      {
        id: "chief",
        role: "ekina",
        job: chief.intent || chief.detail || chief.summary || "调度与生活",
        status: roleStatus(chief),
        badge: chief.badge || "待命",
      },
      {
        id: "coder",
        role: "opencode",
        job: coder.detail || coder.title || coder.brief || "编码帮手",
        status: roleStatus(coder),
        badge: coder.badge || "待命",
      },
      {
        id: "scribe",
        role: "scribe",
        job: scribe.detail || scribe.title || scribe.brief || "排版工人",
        status: roleStatus(scribe),
        badge: scribe.badge || "待命",
      },
      {
        id: "claw",
        role: "openclaw",
        job: claw.detail || claw.title || "通道 / 本机",
        status: roleStatus(claw),
        badge: claw.badge || "待命",
      },
    ];
  });

  async function refreshLive() {
    try {
      live.value = await fetchAgentsLive();
      error.value = "";
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
    }
  }

  async function refreshExtensions() {
    try {
      const [mcp, skills, caps, llmStatus, cfgpuStatus] = await Promise.all([
        fetchMcpServers().catch(() => [] as McpServer[]),
        fetchPlaybookSkills().catch(() => [] as PlaybookSkill[]),
        fetchCapabilities().catch(() => [] as CapabilityGrant[]),
        fetchLlmStatus(false).catch(() => ({}) as LlmStatus),
        fetchCfgpuStatus(false).catch(() => ({}) as CfgpuStatus),
      ]);
      mcpServers.value = mcp;
      playbooks.value = skills;
      capabilities.value = caps;
      llm.value = llmStatus;
      cfgpu.value = cfgpuStatus;
      loadedExt.value = true;
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
    }
  }

  async function refreshAll() {
    await Promise.all([refreshLive(), refreshExtensions()]);
  }

  async function refreshLlm(force = false) {
    try {
      llm.value = await fetchLlmStatus(force);
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
    }
  }

  async function refreshCfgpu(probe = false) {
    try {
      cfgpu.value = await fetchCfgpuStatus(probe);
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
    }
  }

  return {
    live,
    mcpServers,
    playbooks,
    capabilities,
    llm,
    cfgpu,
    loadedExt,
    error,
    glowModel,
    balanceSummary,
    glowServer,
    glowApps,
    glowExt,
    crewBusy,
    busyLabel,
    crewNodes,
    refreshLive,
    refreshExtensions,
    refreshLlm,
    refreshCfgpu,
    refreshAll,
  };
});
