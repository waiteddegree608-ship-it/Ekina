<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import type { ResearchLedger, ResearchRecord, ResearchWithdrawal } from "@/types/project";
import { appendResearchRecords, fetchProjectBoard } from "@/api/projects";
import { useSessionStore } from "@/stores/session";

const props = defineProps<{ projectId: string; ledger?: ResearchLedger }>();
const emit = defineEmits<{ guide: [target: string] }>();
const session = useSessionStore();
const root = ref<HTMLElement>();
const category = ref("conclusion");
const selectedId = ref("");
const withdrawalId = ref("");
const reason = ref("");
const busy = ref(false);
const error = ref("");
const message = ref("");
const pending = ref<ResearchWithdrawal>();
const limit = ref(20);
const entries = computed(() => props.ledger?.records || []);
const categories = [
  { id: "conclusion", label: "结论" }, { id: "evidence", label: "证据" },
  { id: "attempt", label: "尝试" }, { id: "review", label: "审查" },
  { id: "checkpoint", label: "研究决策" }, { id: "withdrawal", label: "撤回记录" },
];
const filtered = computed(() => entries.value.filter(e => e.type === category.value).slice().reverse());
const visible = computed(() => filtered.value.slice(0, limit.value));
const selected = computed(() => entries.value.find(e => e.id === selectedId.value));
const states: Record<string, string> = {
  unreviewed: "尚待审查", reviewed: "已有支持性审查", contested: "存在审查异议",
  needs_review: "依据变化 · 需复核", withdrawn: "已撤回", planned: "待实施的计划",
};
const methods: Record<string, string> = {
  source_check: "来源核对", execution_check: "运行检查", independent_reproduction: "独立复现记录", model_review: "模型审查",
};
const outcomes: Record<string, string> = { supported: "本次结果支持假设", refuted: "本次结果反对假设", inconclusive: "尚无定论", failed: "尝试失败" };
const verdicts: Record<string, string> = { supports: "支持", concerns: "有疑问", rejects: "不支持" };
function title(entry: ResearchRecord) {
  if (entry.type === "evidence" || entry.type === "conclusion") return entry.title;
  if (entry.type === "attempt" || entry.type === "checkpoint") return entry.question;
  if (entry.type === "review") return `${methods[entry.method]} · ${verdicts[entry.verdict]}`;
  return `撤回 ${label(entry.targetId)}`;
}
function label(id: string): string {
  const entry = entries.value.find(e => e.id === id);
  return entry && entry.type !== "withdrawal" ? title(entry) : id;
}
function source(entry: ResearchRecord) {
  return entry.type === "evidence" ? entry.source : entry.type === "review" ? entry.artifact : "";
}
function webUrl(value: string) {
  try { const url = new URL(value); return ["https:", "http:"].includes(url.protocol) ? url.href : undefined; }
  catch { return undefined; }
}
function refs(entry: ResearchRecord): string[] {
  switch (entry.type) {
    case "evidence": return [...(entry.attemptId ? [entry.attemptId] : []), ...(entry.reviewIds || [])];
    case "review": return [entry.evidenceId];
    case "conclusion": return [...entry.evidenceIds, ...entry.dependsOn];
    case "checkpoint": return entry.basisIds;
    case "withdrawal": return [entry.targetId];
    default: return [];
  }
}
async function inspect(id: string) {
  selectedId.value = id;
  await nextTick();
  root.value?.querySelector<HTMLElement>(".record-detail")?.focus();
}
function startWithdrawal(entry: ResearchRecord) {
  withdrawalId.value = entry.id; reason.value = ""; pending.value = undefined; error.value = ""; message.value = "";
}
async function withdraw() {
  if (busy.value || !reason.value.trim()) return;
  const projectId = props.projectId;
  if (!pending.value || pending.value.reason !== reason.value.trim() || pending.value.targetId !== withdrawalId.value)
    pending.value = { id: crypto.randomUUID(), type: "withdrawal", targetId: withdrawalId.value, reason: reason.value.trim() };
  const record = { ...pending.value };
  busy.value = true; error.value = ""; message.value = "";
  try {
    const updated = await appendResearchRecords(projectId, props.ledger?.revision || 0, [record]);
    session.applyProjectBoard(projectId, updated);
    if (props.projectId === projectId) { withdrawalId.value = ""; pending.value = undefined; message.value = "已撤回；相关结论和计划已重新评估，原记录仍保留。"; }
  } catch (e) {
    const err = e as { response?: { status?: number; data?: { error?: string } }; message?: string };
    if (props.projectId === projectId) error.value = err.response?.data?.error || err.message || "保存失败，请重试";
    if (err.response?.status === 409) {
      try { session.applyProjectBoard(projectId, await fetchProjectBoard(projectId)); }
      catch { if (props.projectId === projectId) error.value += "；刷新也失败，请稍后重试。"; }
    }
  } finally { busy.value = false; }
}
watch(category, () => { limit.value = 20; });
watch(() => props.projectId, () => {
  category.value = "conclusion"; selectedId.value = ""; withdrawalId.value = "";
  reason.value = ""; error.value = ""; message.value = ""; pending.value = undefined;
});
</script>

<template>
  <section ref="root" class="research-evidence" aria-label="研究证据记录">
    <div class="heading"><h3>结论从哪里来</h3><span>{{ entries.length }} 条记录</span></div>
    <p class="subtle">审查方式由提交者记录，系统校验引用与状态，不自动核实材料真实性。模型审查不等于独立复现。</p>
    <p v-if="!entries.length" class="empty">还没有结构化研究记录。后续尝试、证据和审查会在这里关联；旧成果保留在下方。</p>
    <template v-else>
      <nav aria-label="研究记录分类">
        <button v-for="c in categories" :key="c.id" :aria-pressed="category === c.id" @click="category = c.id">
          {{ c.label }} · {{ entries.filter(e => e.type === c.id).length }}
        </button>
      </nav>
      <p v-if="!visible.length" class="empty">尚无{{ categories.find(c => c.id === category)?.label }}记录。</p>
      <button v-for="entry in visible" :key="entry.id" class="record-row" :aria-pressed="selectedId === entry.id" @click="inspect(entry.id)">
        <strong>{{ title(entry) }}</strong>
        <span :class="['assessment', entry.assessment]">{{ states[entry.assessment || ''] || (entry.type === 'attempt' ? outcomes[entry.outcome] : '过程记录') }}</span>
      </button>
      <button v-if="filtered.length > limit" @click="limit += 20">再显示 20 条</button>
      <article v-if="selected" class="record-detail" tabindex="-1" aria-label="研究记录详情">
        <div class="heading"><span class="subtle">{{ selected.id }}</span><button @click="selectedId = ''">收起详情</button></div>
        <h4>{{ title(selected) }}</h4>
        <p v-if="selected.assessment" :class="['assessment', selected.assessment]">{{ states[selected.assessment] }}</p>
        <p v-if="selected.withdrawalReason">撤回原因：{{ selected.withdrawalReason }}</p>
        <template v-if="selected.type === 'conclusion'">
          <p>{{ selected.statement }}</p><p class="subtle">适用条件：{{ selected.conditions }}</p>
          <p v-if="selected.limitations" class="subtle">局限：{{ selected.limitations }}</p>
        </template>
        <template v-else-if="selected.type === 'evidence'">
          <p>{{ selected.summary }}</p><p class="subtle">适用条件：{{ selected.conditions }}</p>
          <p class="subtle">{{ { literature: '文献材料', experiment: '实验材料', analysis: '分析材料' }[selected.kind] }} · 提供者：{{ selected.producer }}</p>
        </template>
        <template v-else-if="selected.type === 'review'">
          <p>{{ selected.summary }}</p><p class="subtle">审查者：{{ selected.reviewer }}</p>
        </template>
        <template v-else-if="selected.type === 'attempt'">
          <p>方法：{{ selected.method }}</p><p>{{ selected.result }}</p>
          <p v-if="selected.limitations" class="subtle">局限：{{ selected.limitations }}</p>
          <p v-for="artifact in selected.artifacts" :key="artifact" class="reference">产物：{{ artifact }}</p>
        </template>
        <template v-else-if="selected.type === 'checkpoint'">
          <p>下一步：{{ selected.nextAction }}</p><p>继续条件：{{ selected.continueWhen }}</p>
          <p>停止或调整：{{ selected.stopWhen }}</p><p>资源：{{ selected.resources }}</p>
        </template>
        <p v-else>{{ selected.reason }}</p>
        <p v-if="source(selected)" class="reference">原始材料：
          <a v-if="webUrl(source(selected))" :href="webUrl(source(selected))" target="_blank" rel="noopener noreferrer">{{ source(selected) }}</a>
          <span v-else>{{ source(selected) }}</span>
        </p>
        <div v-if="refs(selected).length" class="references"><span class="subtle">关联依据与记录</span>
          <button v-for="id in refs(selected)" :key="id" @click="inspect(id)">{{ label(id) }}</button>
        </div>
        <div class="actions">
          <button @click="emit('guide', `研究记录 ${selected.id}：${title(selected)}`)">对这条记录提出指导</button>
          <button v-if="!['attempt', 'withdrawal'].includes(selected.type) && selected.assessment !== 'withdrawn'" :disabled="busy" @click="startWithdrawal(selected)">撤回这条记录</button>
        </div>
      </article>
      <form v-if="withdrawalId" class="withdraw-form" @submit.prevent="withdraw">
        <label for="research-withdrawal">撤回原因 · {{ label(withdrawalId) }}</label>
        <textarea id="research-withdrawal" v-model="reason" :disabled="busy" maxlength="3000" rows="3" required />
        <p class="subtle">保留历史并标记相关结论重新检查。修正内容需要新增记录；正在运行的任务不会自动停止。</p>
        <div class="actions"><button type="button" :disabled="busy" @click="withdrawalId = ''">取消</button><button :disabled="busy || !reason.trim()">{{ busy ? '保存中…' : '保存撤回原因' }}</button></div>
      </form>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <p v-if="message" role="status">{{ message }}</p>
    </template>
  </section>
</template>

<style scoped>
.research-evidence{padding:14px;border:1px solid color-mix(in srgb,var(--color-ink) 12%,transparent);border-radius:14px;background:var(--color-elev);min-width:0}
.heading,.actions{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap}.heading>span,.subtle{font-size:12px;color:var(--color-ink-soft)}
h3{font-size:14px;margin:0}h4{font-size:14px;margin:10px 0}p{line-height:1.65;white-space:pre-wrap;overflow-wrap:anywhere;margin:8px 0}
nav,.references,.actions{display:flex;flex-wrap:wrap;gap:6px;margin-top:12px}button{font:inherit;font-size:12px;cursor:pointer;padding:8px 10px;min-height:36px;border-radius:9px;border:1px solid color-mix(in srgb,var(--color-ink) 14%,transparent);background:var(--color-paper);color:var(--color-ink);text-align:left;overflow-wrap:anywhere}
button[aria-pressed=true]{border-color:var(--color-brain);background:var(--color-soft)}button:disabled{opacity:.5;cursor:default}.record-row{display:flex;flex-direction:column;width:100%;gap:6px;margin-top:8px}.record-row strong{font-weight:500}
.assessment{font-size:12px;color:var(--color-ink-soft)}.contested,.needs_review,.error{color:var(--color-warn)}.withdrawn{text-decoration:line-through}.empty{padding:12px 0;color:var(--color-ink-soft)}
.record-detail,.withdraw-form{margin-top:14px;padding:12px;border-radius:10px;background:var(--color-paper);border-left:3px solid var(--color-brain)}.reference{font-size:12px}.reference a{color:var(--color-brain)}.references>span{flex-basis:100%}.actions{justify-content:flex-end}
textarea{display:block;width:100%;box-sizing:border-box;margin:8px 0;padding:10px;border:1px solid var(--color-ink-soft);border-radius:8px;background:var(--color-elev);color:var(--color-ink);font:inherit;resize:vertical}label{font-size:12px}
button:focus-visible,textarea:focus-visible,.record-detail:focus{outline:2px solid var(--color-brain);outline-offset:2px}
</style>
