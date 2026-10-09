<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useSessionStore } from "@/stores/session";
import { useConnectionStore } from "@/stores/connection";
import { fetchAgentRoots, saveAgentRoots } from "@/api/agentRoots";
import { controlCfgpuInstance } from "@/api/home";

const session = useSessionStore();
const connection = useConnectionStore();

const isEkina = computed(() => session.activeContactId === "ekina");
const rootsText = ref("");
const rootsBuiltin = ref<{ path: string; exists?: boolean }[]>([]);
const rootsSaving = ref(false);
const rootsMsg = ref("");
const rootsRejected = ref<{ path: string; reason?: string }[]>([]);
const cfgpuBusyId = ref("");
const cfgpuMsg = ref("");

async function loadRoots() {
  try {
    const data = await fetchAgentRoots();
    rootsBuiltin.value = data.builtin || [];
    rootsText.value = (data.custom || []).map((r) => r.path).join("\n");
    rootsMsg.value = "";
    rootsRejected.value = [];
  } catch (e) {
    rootsMsg.value = "读取失败：" + (e instanceof Error ? e.message : String(e));
  }
}

async function persistRoots() {
  rootsSaving.value = true;
  rootsMsg.value = "";
  try {
    const data = await saveAgentRoots(rootsText.value);
    rootsBuiltin.value = data.builtin || [];
    const customPaths = (data.custom || []).map((r) => r.path);
    rootsText.value = (customPaths.length ? customPaths : data.saved || []).join("\n");
    rootsRejected.value = data.rejected || [];
    const n = (data.saved || customPaths).length;
    rootsMsg.value = rootsRejected.value.length
      ? `已保存 ${n} 条；${rootsRejected.value.length} 条无效未写入`
      : `已保存 ${n} 条，立即生效`;
  } catch (e) {
    rootsMsg.value = "保存失败：" + (e instanceof Error ? e.message : String(e));
  } finally {
    rootsSaving.value = false;
  }
}

const tabs = computed(() =>
  isEkina.value
    ? [
        { id: "all" as const, label: "全部" },
        { id: "brain" as const, label: "大脑" },
        { id: "hand" as const, label: "服务器" },
        { id: "room" as const, label: "研究资料" },
        { id: "ext" as const, label: "扩展" },
      ]
    : [
        { id: "all" as const, label: "全部" },
        { id: "brain" as const, label: "大脑" },
        { id: "hand" as const, label: "本机" },
        { id: "ext" as const, label: "扩展" },
      ],
);

onMounted(() => {
  void connection.refreshAll();
  if (!isEkina.value) void loadRoots();
});

watch(
  () => session.boardOpen,
  (open) => {
    if (open) {
      void connection.refreshAll();
      if (!isEkina.value) void loadRoots();
    }
  },
);

watch(
  () => session.boardTab,
  (tab) => {
    if (!isEkina.value && (tab === "hand" || tab === "all")) void loadRoots();
    if (isEkina.value && (tab === "hand" || tab === "all")) void connection.refreshCfgpu(false);
  },
);

watch(isEkina, () => {
  if (!isEkina.value && session.boardTab === "room") {
    session.boardTab = "all";
  }
});

const llmHost = computed(() => {
  const u = connection.llm.baseUrl || "";
  try {
    return u ? new URL(u).host : "未配置";
  } catch {
    return u || "未配置";
  }
});

const dsBalance = computed(() => connection.llm.balances?.deepseek);
const ocBalance = computed(() => connection.llm.balances?.opencode);
const dsSymbol = computed(() => (dsBalance.value?.currency === "USD" ? "$" : "¥"));

function formatReset(iso?: string) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

const ocWindows = computed(() => {
  const w = ocBalance.value?.windows;
  if (!w) return [];
  return [
    { id: "rolling", label: "滚动", percent: w.rolling?.percent ?? 0, reset: formatReset(w.rolling?.resetsAt) },
    { id: "weekly", label: "本周", percent: w.weekly?.percent ?? 0, reset: formatReset(w.weekly?.resetsAt) },
    { id: "monthly", label: "本月", percent: w.monthly?.percent ?? 0, reset: formatReset(w.monthly?.resetsAt) },
  ];
});

const sshGrants = computed(() =>
  connection.capabilities.filter((c) => c.kind === "ssh_remote" && c.status === "approved"),
);

const sshSummary = computed(() => {
  const cloud = connection.cfgpu;
  if (cloud.configured && cloud.ok) {
    const n = cloud.instances?.length || 0;
    return `骋风 ${cloud.running ?? 0} 开 / ${cloud.stopped ?? 0} 关 · ${n} 台`;
  }
  if (cloud.configured && !cloud.ok) return cloud.error || "骋风 Token 已配，拉取失败";
  if (!sshGrants.value.length) return "尚无已批准的 ssh_remote 能力";
  const host = sshGrants.value.map((c) => c.scope?.host || c.scope?.hostname).find(Boolean);
  return host ? `已授权 · ${String(host)}` : `已授权 ${sshGrants.value.length} 条（缺 host）`;
});

function instanceRunning(status?: string) {
  const s = (status || "").toUpperCase();
  return s.includes("RUN") || status?.includes("运行") || s.includes("START");
}

async function cfgpuPower(action: "start" | "stop", instanceId?: string) {
  if (!instanceId) return;
  cfgpuBusyId.value = instanceId + action;
  cfgpuMsg.value = "";
  try {
    const r = await controlCfgpuInstance(action, instanceId, false);
    if (r.ok === false) {
      cfgpuMsg.value = String(r.error || r.message || (action === "stop" ? "关机失败" : "开机失败"));
    } else {
      cfgpuMsg.value = action === "start" ? `已请求开机 ${instanceId}` : `已请求关机 ${instanceId}`;
    }
    await connection.refreshCfgpu(false);
  } catch (e) {
    cfgpuMsg.value = e instanceof Error ? e.message : String(e);
  } finally {
    cfgpuBusyId.value = "";
  }
}

const overviewCards = computed(() => {
  if (isEkina.value) {
    return [
      {
        id: "brain",
        title: "模型与 API",
        desc: `${connection.llm.model || "未设置模型"} · ${llmHost.value}`,
        status: connection.balanceSummary || (connection.llm.ready ? "密钥已配置" : "缺少密钥"),
        color: "var(--color-brain)",
        tab: "brain" as const,
      },
      {
        id: "ssh",
        title: "骋风算力 / SSH",
        desc: sshSummary.value,
        status: connection.cfgpu.configured
          ? connection.cfgpu.ok
            ? "云端已连"
            : "Token 异常"
          : connection.glowServer === "ok"
            ? "SSH 已授权"
            : "需补 Token",
        color: "var(--color-hand)",
        tab: "hand" as const,
      },
      {
        id: "room",
        title: "研究资料与产物",
        desc: "文献笔记、实验记录和论文工作流",
        status: "本地工作区",
        color: "#3a5a40",
        tab: "room" as const,
      },
      {
        id: "ext",
        title: "MCP / Skills",
        desc: `${connection.mcpServers.length} MCP · ${connection.playbooks.length} Skills`,
        status: connection.glowExt === "ok" ? "就绪" : "空/部分",
        color: "#5a3d5c",
        tab: "ext" as const,
      },
    ];
  }
  return [
    {
      id: "brain",
      title: "代理人模型",
      desc: `${connection.llm.model || "未设置模型"} · ${llmHost.value}`,
      status: connection.llm.ready ? "可用" : "缺少密钥",
      color: "var(--color-brain)",
      tab: "brain" as const,
    },
    {
      id: "local",
      title: "本机文件夹",
      desc: "允许目录索引、模糊找文件、读附件填表/出报告",
      status: "PC 助理",
      color: "var(--color-hand)",
      tab: "hand" as const,
    },
    {
      id: "ext",
      title: "办公扩展",
      desc: "读文档 / Word / PDF 批注；拖入对话框的附件进 agent-inbox",
      status: connection.glowExt === "ok" ? "就绪" : "基础可用",
      color: "#5a3d5c",
      tab: "ext" as const,
    },
  ];
});

const visibleCards = computed(() => {
  if (session.boardTab === "all") return overviewCards.value;
  return overviewCards.value.filter((c) => c.tab === session.boardTab);
});
</script>

<template>
  <div
    class="fixed inset-0 z-40 grid place-items-center bg-black/30 p-6 backdrop-blur-sm"
    @click.self="session.boardOpen = false"
  >
    <div class="flex max-h-[86vh] w-full max-w-[960px] flex-col overflow-hidden rounded-[22px] border border-black/10 bg-[var(--color-elev)] shadow-2xl">
      <div class="flex items-start justify-between gap-3 px-6 pb-3 pt-5">
        <div>
          <h2 class="font-[family-name:var(--font-display)] text-xl font-semibold tracking-tight">
            {{ isEkina ? "Ekina 的科研工具" : "代理人连着什么" }}
          </h2>
          <p class="mt-1 text-sm text-[var(--color-ink-soft)]">
            {{
              isEkina
                ? "研究笔记、算力连接、论文流程和扩展工具。"
                : "研究资料助手：授权目录、文件索引与附件整理。"
            }}
          </p>
        </div>
        <button type="button" class="grid h-9 w-9 place-items-center rounded-lg hover:bg-black/5" @click="session.boardOpen = false">
          ✕
        </button>
      </div>

      <div class="flex gap-1 overflow-auto border-b border-black/8 px-5 pb-3">
        <button
          v-for="t in tabs"
          :key="t.id"
          type="button"
          class="rounded-full px-3.5 py-2 text-sm font-medium whitespace-nowrap"
          :class="session.boardTab === t.id ? 'bg-black/5 text-[var(--color-ink)]' : 'text-[var(--color-ink-soft)]'"
          @click="session.boardTab = t.id"
        >
          {{ t.label }}
        </button>
      </div>

      <div class="min-h-0 flex-1 overflow-auto px-5 py-4">
        <div
          v-if="session.boardTab === 'all' || (isEkina && session.boardTab === 'room')"
          class="grid grid-cols-[repeat(auto-fill,minmax(240px,1fr))] gap-3"
        >
          <article
            v-for="c in visibleCards"
            :key="c.id"
            class="relative grid gap-2 overflow-hidden rounded-2xl border border-black/8 bg-[var(--color-paper)] p-4"
          >
            <span class="absolute inset-y-0 left-0 w-[3px]" :style="{ background: c.color }" />
            <div class="flex items-start justify-between gap-2 pl-1">
              <h3 class="font-semibold">{{ c.title }}</h3>
              <span class="rounded-full bg-black/5 px-2 py-0.5 text-[0.7rem] font-semibold text-[var(--color-ink-soft)]">
                {{ c.status }}
              </span>
            </div>
            <p class="pl-1 text-sm text-[var(--color-ink-soft)]">{{ c.desc }}</p>
            <div class="flex flex-wrap gap-2 pl-1">
              <button
                type="button"
                class="rounded-lg border border-black/10 px-3 py-1.5 text-xs font-semibold"
                @click="session.boardTab = c.tab"
              >
                查看
              </button>
            </div>
          </article>
        </div>

        <section v-else-if="session.boardTab === 'brain'" class="grid max-w-xl gap-3">
          <article class="rounded-2xl border border-black/8 bg-[var(--color-paper)] p-4">
            <div class="flex items-start justify-between gap-2">
              <h3 class="font-semibold">额度监控</h3>
              <button
                type="button"
                class="rounded-lg border border-black/10 px-2.5 py-1 text-xs font-semibold"
                @click="connection.refreshLlm(true)"
              >
                刷新
              </button>
            </div>
            <p class="mt-1 text-xs text-[var(--color-ink-faint)]">
              {{ connection.llm.routing || "CHIEF=deepseek · CODER=opencode · SCRIBE=opencode" }}
            </p>
            <p
              v-if="connection.llm.deepseekPeak && connection.llm.skipDeepseekPeak"
              class="mt-1 text-xs text-[var(--color-warn)]"
            >
              DeepSeek 高峰（北京工作日 09–12 / 14–18），CHIEF/RESEARCH 改走 OpenCode
              {{ connection.llm.peakAltModel || "glm-5.3" }}，不打官方 DeepSeek。
            </p>

            <div class="mt-3 grid gap-3">
              <div class="rounded-xl border border-black/8 bg-[var(--color-elev)] px-3 py-3">
                <div class="flex items-baseline justify-between gap-2">
                  <p class="text-sm font-semibold">DeepSeek</p>
                  <p class="text-sm font-semibold">
                    <template v-if="dsBalance?.ok">{{ dsSymbol }}{{ dsBalance.total }}</template>
                    <template v-else-if="dsBalance?.error" class="text-[var(--color-warn)]">{{ dsBalance.error }}</template>
                    <template v-else>—</template>
                  </p>
                </div>
                <p class="mt-1 text-xs text-[var(--color-ink-soft)]">
                  <template v-if="dsBalance?.ok">
                    {{ dsBalance.available ? "可用" : "余额不足" }}
                    · 充值 {{ dsSymbol }}{{ dsBalance.toppedUp }}
                    · 赠送 {{ dsSymbol }}{{ dsBalance.granted }}
                    · 密钥 ·••{{ connection.llm.deepseekKeyTail }}
                  </template>
                  <template v-else>日常对话 / 论文推理走这里</template>
                </p>
              </div>

              <div class="rounded-xl border border-black/8 bg-[var(--color-elev)] px-3 py-3">
                <div class="flex items-baseline justify-between gap-2">
                  <p class="text-sm font-semibold">OpenCode Go</p>
                  <p class="text-sm font-semibold">
                    <template v-if="ocBalance?.ok && ocBalance.windows?.monthly">
                      月余 {{ ocBalance.windows.monthly.remainingPercent }}%
                    </template>
                    <template v-else-if="ocBalance?.error">{{ ocBalance.error }}</template>
                    <template v-else>—</template>
                  </p>
                </div>
                <div v-if="ocBalance?.ok && ocBalance.windows" class="mt-2 grid gap-2">
                  <div v-for="w in ocWindows" :key="w.id">
                    <div class="mb-0.5 flex justify-between text-[0.7rem] text-[var(--color-ink-soft)]">
                      <span>{{ w.label }} · 已用 {{ w.percent }}%</span>
                      <span>{{ w.reset }}</span>
                    </div>
                    <div class="h-1.5 overflow-hidden rounded-full bg-black/8">
                      <div
                        class="h-full rounded-full"
                        :class="w.percent >= 80 ? 'bg-[var(--color-warn)]' : 'bg-[var(--color-ok)]'"
                        :style="{ width: Math.min(100, w.percent) + '%' }"
                      />
                    </div>
                  </div>
                </div>
                <p class="mt-2 text-xs text-[var(--color-ink-faint)]">
                  {{ ocBalance?.hint || "CODER / 看图走这里。Go 套餐显示窗口用量，不是钱包美元。" }}
                </p>
              </div>
            </div>
          </article>
        </section>

        <section v-else-if="session.boardTab === 'hand' && isEkina" class="grid gap-3">
          <article class="rounded-2xl border border-black/8 bg-[var(--color-paper)] p-4">
            <div class="flex items-start justify-between gap-3">
              <div>
                <h3 class="font-semibold">骋风算力</h3>
                <p class="mt-1 text-sm text-[var(--color-ink-soft)]">
                  可选云算力适配器。仅在配置并授权后使用，操作前核对实例与任务状态。
                </p>
              </div>
              <button
                type="button"
                class="rounded-lg px-2.5 py-1 text-xs text-[var(--color-ink-soft)] hover:bg-black/5"
                @click="connection.refreshCfgpu(true)"
              >
                探活 GPU
              </button>
            </div>
            <p v-if="!connection.cfgpu.configured" class="mt-3 text-sm text-[var(--color-ink-faint)]">
              {{ connection.cfgpu.hint || "尚未配置 CFGPU_API_TOKEN。控制台 → API Token，写入 .env 后重启后端。不要把网页账密发到聊天里。" }}
            </p>
            <p v-else-if="connection.cfgpu.error" class="mt-3 text-sm text-[var(--color-warn)]">
              {{ connection.cfgpu.error }}
            </p>
            <div v-else class="mt-3 grid gap-2">
              <div
                v-for="inst in connection.cfgpu.instances || []"
                :key="inst.instanceId"
                class="rounded-xl border border-black/8 bg-[var(--color-elev)] px-3 py-2 text-sm"
              >
                <div class="flex items-start justify-between gap-2">
                  <div>
                    <div class="font-semibold">
                      {{ inst.name || inst.instanceId }}
                      <span
                        class="text-xs font-normal"
                        :class="instanceRunning(inst.status) ? 'text-[var(--color-ok)]' : 'text-[var(--color-ink-faint)]'"
                      >
                        · {{ inst.status || "未知" }}
                      </span>
                    </div>
                    <p class="mt-1 text-xs text-[var(--color-ink-soft)]">
                      {{ inst.instanceId }}
                      <span v-if="inst.gpu"> · {{ inst.gpu }}{{ inst.gpuNum ? " ×" + inst.gpuNum : "" }}</span>
                      <span v-if="inst.computeHostId"> · SSH {{ inst.computeHostId }}</span>
                    </p>
                    <p v-if="inst.gpuLive" class="mt-1 text-xs text-[var(--color-ink-faint)]">
                      GPU
                      {{ inst.gpuLive.busy ? "占用中" : "空闲" }}
                      <span v-if="inst.gpuLive.maxUtil != null"> · util {{ inst.gpuLive.maxUtil }}%</span>
                    </p>
                  </div>
                  <div class="flex shrink-0 gap-1">
                    <button
                      type="button"
                      class="rounded-lg px-2 py-1 text-xs hover:bg-black/5 disabled:opacity-40"
                      :disabled="!!cfgpuBusyId || instanceRunning(inst.status)"
                      @click="cfgpuPower('start', inst.instanceId)"
                    >
                      开机
                    </button>
                    <button
                      type="button"
                      class="rounded-lg px-2 py-1 text-xs hover:bg-black/5 disabled:opacity-40"
                      :disabled="!!cfgpuBusyId || !instanceRunning(inst.status)"
                      @click="cfgpuPower('stop', inst.instanceId)"
                    >
                      关机
                    </button>
                  </div>
                </div>
              </div>
              <p v-if="!(connection.cfgpu.instances || []).length" class="text-sm text-[var(--color-ink-faint)]">
                控制台里还没有实例，或 Token 无权列出。
              </p>
            </div>
            <p v-if="cfgpuMsg" class="mt-2 text-xs text-[var(--color-ink-soft)]">{{ cfgpuMsg }}</p>
          </article>
          <article class="rounded-2xl border border-black/8 bg-[var(--color-paper)] p-4">
            <h3 class="font-semibold">远程服务器（SSH）</h3>
            <p class="mt-1 text-sm text-[var(--color-ink-soft)]">
              复现与实验任务可使用此连接。通过权限卡批准；密码走服务端环境变量。
            </p>
            <p v-if="!sshGrants.length" class="mt-3 text-sm text-[var(--color-ink-faint)]">
              暂无已批准的 ssh_remote。可让她申请，或在待办里处理权限卡。
            </p>
            <div v-else class="mt-3 grid gap-2">
              <div
                v-for="g in sshGrants"
                :key="g.id"
                class="rounded-xl border border-black/8 bg-[var(--color-elev)] px-3 py-2 text-sm"
              >
                <div class="font-semibold">
                  {{ g.scope?.host || g.scope?.hostname || "（未写 host）" }}
                  <span class="text-xs font-normal text-[var(--color-ok)]"> · {{ g.status }}</span>
                </div>
                <p class="mt-1 text-xs text-[var(--color-ink-soft)]">
                  user={{ g.scope?.user || "—" }}
                  · key={{ g.scope?.keyPath || "env/默认" }}
                </p>
              </div>
            </div>
          </article>
        </section>

        <section v-else-if="session.boardTab === 'hand' && !isEkina" class="grid gap-3">
          <article class="rounded-2xl border border-black/8 bg-[var(--color-paper)] p-4">
            <h3 class="font-semibold">本机允许目录</h3>
            <p class="mt-1 text-sm text-[var(--color-ink-soft)]">
              内置工作区始终可用。额外目录直接粘贴绝对路径（每行一条，也可用分号分隔），保存后立即生效，无需改 `.env` 或重启。
            </p>
            <div v-if="rootsBuiltin.length" class="mt-3 grid gap-1">
              <p class="text-xs font-semibold text-[var(--color-ink-faint)]">内置</p>
              <p
                v-for="r in rootsBuiltin"
                :key="r.path"
                class="truncate rounded-lg bg-black/5 px-2.5 py-1.5 text-xs text-[var(--color-ink-soft)]"
                :title="r.path"
              >
                {{ r.path }}
              </p>
            </div>
            <label class="mt-3 block text-xs font-semibold text-[var(--color-ink-faint)]">自定义（可多条）</label>
            <textarea
              v-model="rootsText"
              rows="5"
              class="mt-1 w-full resize-y rounded-xl border border-black/10 bg-[var(--color-elev)] px-3 py-2 text-sm outline-none focus:border-black/25"
              placeholder="E:\Documents&#10;D:\Papers&#10;E:\工作\表格"
            />
            <div class="mt-2 flex flex-wrap items-center gap-2">
              <button
                type="button"
                class="rounded-lg bg-[var(--color-ink)] px-3 py-1.5 text-xs font-semibold text-[var(--color-paper)] disabled:opacity-50"
                :disabled="rootsSaving"
                @click="persistRoots"
              >
                {{ rootsSaving ? "保存中…" : "保存并生效" }}
              </button>
              <button
                type="button"
                class="rounded-lg border border-black/10 px-3 py-1.5 text-xs font-semibold"
                @click="loadRoots"
              >
                刷新
              </button>
              <span v-if="rootsMsg" class="text-xs text-[var(--color-ink-soft)]">{{ rootsMsg }}</span>
            </div>
            <ul v-if="rootsRejected.length" class="mt-2 space-y-1 text-xs text-red-600">
              <li v-for="r in rootsRejected" :key="r.path">{{ r.path }} — {{ r.reason || "无效" }}</li>
            </ul>
            <ul class="mt-3 list-disc space-y-1 pl-5 text-sm text-[var(--color-ink-soft)]">
              <li>保存后可让代理人 <code>indexLocalFolder</code> 建索引，再自然语言找文件</li>
              <li>对话框支持拖入/粘贴文件；附件进 agent-inbox</li>
            </ul>
          </article>
        </section>

        <div v-else-if="session.boardTab === 'ext'" class="grid gap-6">
          <section v-if="!isEkina">
            <h3 class="mb-2 text-sm font-semibold">代理人办公能力</h3>
            <p class="text-sm text-[var(--color-ink-soft)]">
              读文档、本地索引、Markdown/Word 产出、PDF 批注。远程实验由科研角色处理。
            </p>
          </section>
          <section>
            <h3 class="mb-2 text-sm font-semibold">Skills 剧本</h3>
            <p v-if="!connection.playbooks.length" class="text-sm text-[var(--color-ink-faint)]">
              未加载到 skills/
            </p>
            <div class="grid gap-2">
              <div
                v-for="s in connection.playbooks"
                :key="s.id"
                class="rounded-2xl border border-black/8 bg-[var(--color-paper)] px-4 py-3"
              >
                <div class="flex items-center gap-2 text-sm font-semibold">
                  <span class="h-1.5 w-1.5 rounded-full bg-[var(--color-ink)]" />
                  {{ s.title || s.id }}
                </div>
                <p class="mt-1 text-xs text-[var(--color-ink-soft)]">{{ s.id }}</p>
              </div>
            </div>
          </section>
          <section>
            <h3 class="mb-2 text-sm font-semibold">MCP Servers</h3>
            <p v-if="!connection.mcpServers.length" class="text-sm text-[var(--color-ink-faint)]">
              当前无已注册 MCP
            </p>
            <div class="grid gap-2">
              <div
                v-for="m in connection.mcpServers"
                :key="m.name"
                class="rounded-2xl border border-black/8 bg-[var(--color-paper)] px-4 py-3"
              >
                <div class="flex items-center justify-between gap-2">
                  <div class="text-sm font-semibold">{{ m.name }}</div>
                  <span class="rounded-full bg-black/5 px-2 py-0.5 text-[0.7rem] font-bold">
                    {{ m.status || "UNKNOWN" }}
                  </span>
                </div>
              </div>
            </div>
          </section>
        </div>
      </div>
    </div>
  </div>
</template>
