<script setup lang="ts">
import { computed, ref, watch, nextTick } from "vue";
import type { CollaborationSettings, ProjectBoard, ProjectTimeline, ResearchStep } from "@/types/project";
import { saveCollaboration, submitGuidance } from "@/api/projects";
import { fetchPaperPreview, type PaperPreview } from "@/api/lab";
import { useSessionStore } from "@/stores/session";
import CollaborationFields from "./CollaborationFields.vue";
import ResearchEvidence from "./ResearchEvidence.vue";
const props = defineProps<{ board?: ProjectBoard | null; timeline?: ProjectTimeline | null; projectId?: string | null }>();
const session = useSessionStore();
const board = computed(() => props.board || props.timeline?.board || null);
const projectId = computed(() => props.projectId || board.value?.projectId || '');
const tab = ref<'route' | 'resources' | 'guidance' | 'results'>('route');
const selectedId = ref('');
const settingsOpen = ref(false);
const draft = ref<CollaborationSettings>(defaults());
const busy = ref(false);
const message = ref('');
const error = ref('');
const guidanceText = ref('');
const guidanceTarget = ref('整个课题');
const guidanceInput = ref<HTMLTextAreaElement | null>(null);
const pendingRequest = ref<{ id: string; text: string; target: string; projectId: string } | null>(null);
const preview = ref<PaperPreview | null>(null);
const previewBusy = ref(false);
const previewError = ref('');
const modes = { guided: '共同探索', checkpoints: '关键节点参与', delegated: '委托推进' };
const statuses: Record<string, string> = { planned:'计划中', running:'进行中', done:'已完成', blocked:'受阻', conditional:'条件计划', idle:'待命', planning:'规划中' };
function defaults(): CollaborationSettings { return { mode:'checkpoints', budget:null, currency:'CNY', priorities:'' }; }
const collaboration = computed(() => ({ ...defaults(), ...board.value?.collaboration }));
const explicitRoute = computed(() => !!board.value?.roadmap?.length);
const route = computed<ResearchStep[]>(() => explicitRoute.value ? board.value!.roadmap! : (board.value?.next || []).map((title,i) => ({ id:`legacy-${i}`,title,status:'planned' })));
const selected = computed(() => route.value.find(s => s.id === selectedId.value) || route.value.find(s => s.status === 'running') || route.value[0]);
const decisions = computed(() => board.value?.decisions || []);
const pendingCount = computed(() => (board.value?.guidance || []).filter(g => g.status === 'pending').length);
const checkpoint = computed(() => board.value?.research?.checkpoint);
function money(value?: number | null, currency = 'CNY') { return value == null || !Number.isFinite(value) ? '待估' : `${currency === 'USD' ? '$' : '¥'}${value.toLocaleString('zh-CN', { maximumFractionDigits:2 })}`; }
function cost(step: Pick<ResearchStep, 'costMin' | 'costMax' | 'currency'>) {
  if (step.costMin == null && step.costMax == null) return '费用待估';
  if (step.costMin != null && step.costMax != null && step.costMin === step.costMax) return `估计 ${money(step.costMax,step.currency)}`;
  return `${money(step.costMin,step.currency)} – ${money(step.costMax,step.currency)}`;
}
function when(value?: string) { if (!value) return '尚未更新'; const d = new Date(value); return Number.isNaN(d.getTime()) ? '时间未知' : d.toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'}); }
function apiError(e: unknown) { const v = e as { response?: { data?: { error?: string } }; message?: string }; return v.response?.data?.error || v.message || '操作失败，请重试'; }
watch(projectId, () => { tab.value='route'; selectedId.value=''; settingsOpen.value=false; guidanceText.value=''; guidanceTarget.value='整个课题'; message.value=''; error.value=''; preview.value=null; previewError.value=''; pendingRequest.value=null; });
function editSettings() { draft.value = { ...collaboration.value }; settingsOpen.value=true; error.value=''; message.value=''; }
async function saveSettings() {
  const id = projectId.value;
  if (!id || busy.value) return;
  if (draft.value.budget != null && (!Number.isFinite(draft.value.budget) || draft.value.budget < 0)) { error.value='请输入有效的非负预算，或留空。'; return; }
  busy.value=true; error.value=''; message.value='';
  try { const updated = await saveCollaboration(id, { ...draft.value }); session.applyProjectBoard(id, updated); if (projectId.value === id) { settingsOpen.value=false; message.value='参与约定已保存；后续项目对话会读取。已运行任务不会因此自动停止。'; } }
  catch(e) { if (projectId.value === id) error.value=apiError(e); }
  finally { busy.value=false; }
}
async function guide(target='整个课题') { guidanceTarget.value=target.slice(0,240); tab.value='guidance'; await nextTick(); guidanceInput.value?.focus(); }
async function saveGuidance() {
  const id=projectId.value, text=guidanceText.value.trim(), target=guidanceTarget.value;
  if (!id || !text || busy.value) return;
  if (!pendingRequest.value || pendingRequest.value.text!==text || pendingRequest.value.target!==target || pendingRequest.value.projectId!==id) pendingRequest.value={id:crypto.randomUUID(),text,target,projectId:id};
  const requestId=pendingRequest.value.id;
  busy.value=true; error.value=''; message.value='';
  try { const updated=await submitGuidance(id,target,text,requestId); session.applyProjectBoard(id,updated); if(projectId.value===id) { guidanceText.value=''; pendingRequest.value=null; message.value='指导已保存，等待 Ekina 处理。可以点击“让 Ekina 处理指导”立即开始。'; } }
  catch(e) { if(projectId.value===id) error.value=apiError(e); }
  finally { busy.value=false; }
}
function processGuidance() { if (!projectId.value || session.sending) return; session.focusTask(projectId.value); void session.send('请处理我对这个课题保存的指导，说明会带来哪些调整，并更新研究路线与资源计划。需要额外授权的操作继续等待授权。'); }
function discussPlan() { if (!projectId.value || session.sending) return; session.focusTask(projectId.value); void session.send('请根据这个课题的起点与参与约定，开展必要的资料查证，完善研究路线和下一份交付：说明各步骤的目的、验收标准、依赖、资源需求及费用估算依据。未知费用明确标注。先完善计划，不启动新的付费算力。'); }
async function loadPreview() { const id=projectId.value; if (!id) return; previewBusy.value=true; previewError.value=''; try { const data=await fetchPaperPreview(id); if(projectId.value===id) preview.value=data; } catch { if(projectId.value===id) previewError.value='稿件预览暂时无法读取。'; } finally { previewBusy.value=false; } }
</script>
<template>
  <section class="research-workspace" aria-label="研究项目工作台">
    <p v-if="board?.error" class="error" role="alert">{{ board.error }}</p>
    <header class="project-head">
      <div class="row"><span class="eyebrow">研究项目</span><span class="badge">{{ statuses[board?.status || 'idle'] || board?.status || '待命' }}</span></div>
      <h2>{{ board?.goal || timeline?.goal || '项目资料加载中' }}</h2><p class="muted">当前：{{ board?.now || '尚未记录当前工作' }}</p>
      <div class="deliverable"><span class="eyebrow">下一份交付</span><p>{{ board?.nextDeliverable || '尚未明确；可以请 Ekina 完善路线与验收标准。' }}</p></div>
      <div class="row compact"><span>{{ modes[collaboration.mode] }} · {{ collaboration.budget == null ? '预算未约定' : `规划预算 ${money(collaboration.budget, collaboration.currency)}` }}</span><button @click="editSettings" :disabled="busy">调整约定</button></div>
      <p class="meta">更新于 {{ when(board?.updatedAt) }} · 研究路线可随证据调整</p>
    </header>
    <form v-if="settingsOpen" class="surface" @submit.prevent="saveSettings"><h3>你的参与约定</h3><CollaborationFields v-model="draft" :disabled="busy" /><div class="actions"><button type="button" :disabled="busy" @click="settingsOpen=false">取消</button><button class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存约定' }}</button></div></form>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" class="notice" role="status">{{ message }}</p>
    <section v-if="decisions.length" class="surface decision-list" aria-label="需要参与的事项"><h3>需要你参与 · {{ decisions.length }} 项</h3><article v-for="d in decisions" :key="d.id"><span class="badge">{{ d.kind === 'authorization' ? '等待授权' : '征求意见' }}</span><h4>{{ d.title }}</h4><p v-if="d.recommendation">建议：{{ d.recommendation }}</p><p v-if="d.impact" class="muted">影响：{{ d.impact }}</p><p class="meta">{{ d.kind === 'authorization' ? '未授权时不可执行依赖该授权的操作；在指导区回复不会授予权限。' : '按当前参与约定处理；仅在已有授权范围内可以继续。' }}</p><div class="actions"><button @click="guide(`决策：${d.title}`)">表达意见</button><button v-if="d.kind==='authorization'" @click="session.inboxOpen=true">查看授权待办</button></div></article></section>
    <nav class="workspace-tabs" aria-label="项目内容"><button :aria-pressed="tab==='route'" @click="tab='route'">研究路线</button><button :aria-pressed="tab==='resources'" @click="tab='resources'">资源与费用</button><button :aria-pressed="tab==='guidance'" @click="tab='guidance'">指导{{ pendingCount ? ` · ${pendingCount}` : '' }}</button><button :aria-pressed="tab==='results'" @click="tab='results'">成果与依据</button></nav>
    <div v-if="tab==='route'" class="tab-content">
      <article v-if="checkpoint" class="surface" aria-label="下一轮验证">
        <span class="eyebrow">下一轮要弄清楚</span><h3>{{ checkpoint.question }}</h3>
        <p v-if="checkpoint.assessment==='needs_review' || checkpoint.assessment==='withdrawn'" class="error">{{ checkpoint.assessment==='withdrawn' ? '这份计划已撤回，请重新制定。' : '计划依据发生变化，请先复核再决定是否继续。' }}</p>
        <p>{{ checkpoint.nextAction }}</p>
        <dl><div><dt>何时继续</dt><dd>{{ checkpoint.continueWhen }}</dd></div><div><dt>何时停止或调整</dt><dd>{{ checkpoint.stopWhen }}</dd></div><div><dt>需要的资源</dt><dd>{{ checkpoint.resources }} · {{ cost(checkpoint) }}</dd></div></dl>
        <p class="meta">这是待实施的研究计划；保存计划不会启动任务或预留费用。</p>
        <div class="actions"><button @click="tab='results'">查看研究依据</button><button @click="guide(`下一轮验证：${checkpoint.question}`)">指导下一轮</button></div>
      </article>
      <p v-if="board?.research?.attentionCount" class="notice">{{ board.research.attentionCount }} 项结论尚待审查或需要复核。<button @click="tab='results'">查看结论与依据</button></p>
      <p v-if="!explicitRoute && route.length" class="meta">以下来自旧版“下一步”，依赖、资源和验收尚未补齐。</p>
      <div v-if="route.length" class="route-layout"><div class="step-list"><button v-for="(s,i) in route" :key="s.id" :aria-pressed="selected?.id===s.id" @click="selectedId=s.id"><span class="step-index">{{ s.status==='done' ? '✓' : i+1 }}</span><span>{{ s.title }}<small>{{ statuses[s.status] }}</small></span></button></div>
        <article v-if="selected" class="surface step-detail"><h3>{{ selected.title }}</h3><dl><div><dt>为什么做</dt><dd>{{ selected.purpose || '尚未说明' }}</dd></div><div><dt>交付什么</dt><dd>{{ selected.deliverable || '尚未明确' }}</dd></div><div><dt>如何验收</dt><dd>{{ selected.acceptance || '尚未定义验收标准' }}</dd></div><div><dt>开始条件</dt><dd>{{ selected.condition || (selected.status==='conditional' ? '待明确触发条件' : '尚未记录') }}</dd></div><div v-if="selected.dependsOn?.length"><dt>依赖步骤</dt><dd>{{ selected.dependsOn.map(id=>route.find(s=>s.id===id)?.title || id).join('、') }}</dd></div><div><dt>资源与估算</dt><dd>{{ selected.resources || '资源需求待评估' }} · {{ cost(selected) }}</dd></div></dl><button @click="guide(`步骤：${selected.title}`)">指导这一步</button></article>
      </div><p v-else class="empty">还没有研究路线。先让 Ekina 根据起点提出计划，后续再决定实施规模。</p><button :disabled="session.sending" @click="discussPlan">{{ session.sending ? 'Ekina 正在处理…' : '请 Ekina 完善路线' }}</button>
    </div>
    <div v-else-if="tab==='resources'" class="tab-content">
      <article v-if="checkpoint" class="surface"><h3>下一轮验证 · {{ cost(checkpoint) }}</h3><p>{{ checkpoint.resources }}</p><p class="meta">估算依据：{{ checkpoint.estimateBasis || '尚未提供' }}。此项可能与路线步骤重叠，不合并计算总价。</p><p v-if="checkpoint.assessment!=='planned'" class="error">该计划需要重新确认，暂不作为有效投入计划。</p></article>
      <div class="surface"><h3>规划预算：{{ collaboration.budget == null ? '未约定' : money(collaboration.budget,collaboration.currency) }}</h3><p>实际支出尚未接入；不能据此计算剩余可用额度。</p><p class="meta">当前版本不提供自动超额停机。下方是各步骤的估算，未知项不会按零费用计算。</p><button @click="editSettings">调整预算与偏好</button></div>
      <article v-for="s in route" :key="s.id" class="resource-row"><div class="row"><h4>{{ s.title }}</h4><span class="badge">{{ cost(s) }}</span></div><p>{{ s.resources || '资源需求待评估' }}</p><p class="meta">估算依据：{{ s.estimateBasis || '尚未提供' }}</p><p v-if="s.status==='conditional'" class="meta">条件计划：{{ s.condition || '验证后再决定是否投入' }}</p><button @click="guide(`资源：${s.title}`)">讨论投入或替代方案</button></article><p v-if="!route.length" class="empty">路线尚未明确，资源与费用待评估。</p><button @click="session.boardTab='hand';session.boardOpen=true">查看已有服务器</button>
    </div>
    <div v-else-if="tab==='guidance'" class="tab-content">
      <form class="surface" @submit.prevent="saveGuidance"><h3>指导：{{ guidanceTarget }}</h3><label for="project-guidance">告诉 Ekina 你希望调整什么</label><textarea id="project-guidance" ref="guidanceInput" v-model="guidanceText" :disabled="busy" maxlength="4000" rows="4" placeholder="例如：先别扩大实验，解释当前结果是否支持假设；如果不支持，给出替代路线。" /><p class="meta">指导会保存到本项目。已运行任务不会自动暂停；授权仍通过待办单独处理。</p><div class="actions"><button v-if="guidanceTarget!=='整个课题'" type="button" @click="guidanceTarget='整个课题'">改为指导整个课题</button><button class="primary" :disabled="busy || !guidanceText.trim()">{{ busy ? '保存中…' : '保存指导' }}</button></div></form>
      <button v-if="pendingCount" :disabled="session.sending || busy" @click="processGuidance">{{ session.sending ? 'Ekina 正在处理…' : '让 Ekina 处理指导' }}</button>
      <article v-for="g in board?.guidance || []" :key="g.id" class="surface"><div class="row"><span class="badge">{{ g.status==='responded' ? '已有回应' : '等待处理' }}</span><span class="meta">{{ when(g.at) }}</span></div><h4>{{ g.target || '整个课题' }}</h4><p class="preserve">{{ g.text }}</p><div v-if="g.reply" class="reply"><span class="eyebrow">Ekina 的回应</span><p class="preserve">{{ g.reply }}</p><p class="meta">计划调整和实际执行结果请结合路线与产物核对。</p></div></article>
    </div>
    <div v-else class="tab-content">
      <ResearchEvidence :project-id="projectId" :ledger="board?.research" @guide="guide" />
      <section class="surface"><h3>已记录的成果</h3><p class="meta">完成记录不等于验收通过；以原始产物和证据为准。</p><article v-for="(item,i) in board?.done || []" :key="i" class="result-item"><h4>{{ item.title }}</h4><p class="preserve">{{ item.result }}</p><button @click="guide(`成果：${item.title || '未命名成果'}`)">核对或提出修改</button></article><p v-if="!board?.done?.length" class="empty">尚无完成记录。</p></section>
      <section v-if="board?.findings?.length || board?.questions?.length" class="surface"><h3>发现与待解决问题</h3><p v-for="(f,i) in board?.findings || []" :key="i">{{ f.kind==='fail' ? '受阻：' : '' }}{{ f.text }}</p><p v-for="q in board?.questions || []" :key="q">{{ q }}</p></section>
      <details class="surface" @toggle="($event.target as HTMLDetailsElement).open && !preview && loadPreview()"><summary>稿件预览与检查</summary><p v-if="previewBusy">读取中…</p><p v-if="previewError" role="alert">{{ previewError }}</p><p v-if="preview && !preview.hasPaper && !preview.coverUrl">尚无稿件预览。</p><img v-if="preview?.coverUrl" :src="preview.coverUrl" alt="当前稿件预览" /><p v-if="preview?.audit?.verdict">{{ preview.audit.verdict }}</p><ul><li v-for="c in preview?.audit?.checks || []" :key="c.name">{{ c.ok ? '✓' : '待检查' }} {{ c.name }} · {{ c.detail }}</li></ul><figure v-for="f in preview?.figures || []" :key="f.name"><img :src="f.url" :alt="f.name" /><figcaption>{{ f.name }}</figcaption></figure></details>
      <details class="surface"><summary>调整与执行记录 · {{ board?.timeline?.length || 0 }}</summary><article v-for="(ev,i) in board?.timeline || []" :key="i" class="result-item"><span class="meta">{{ when(ev.at) }}</span><h4>{{ ev.title }}</h4><p class="preserve">{{ ev.detail }}</p></article></details>
    </div>
  </section>
</template>
<style scoped>
.research-workspace{display:flex;flex:1;min-height:0;min-width:0;flex-direction:column;gap:14px;overflow:auto;padding:2px 3px 20px;font-size:13px;color:var(--color-ink)}.project-head{padding:15px;background:var(--color-elev);border-radius:16px;border:1px solid color-mix(in srgb,var(--color-ink) 10%,transparent)}h2{font-family:var(--font-display);font-size:18px;font-weight:600;line-height:1.45;margin:10px 0;overflow-wrap:anywhere}h3{font-size:14px;font-weight:600;margin:0 0 10px}h4{font-size:13px;font-weight:600;margin:9px 0 5px}p{line-height:1.65;margin:6px 0;overflow-wrap:anywhere}.muted,.meta{color:var(--color-ink-soft)}.meta,.eyebrow{font-size:12px;line-height:1.6}.eyebrow{color:var(--color-ink-soft)}.row,.actions{display:flex;align-items:center;justify-content:space-between;gap:9px;flex-wrap:wrap}.compact{font-size:12px}.actions{justify-content:flex-end;margin-top:12px}.badge{font-size:12px;border-radius:20px;padding:4px 8px;background:var(--color-soft)}.deliverable{margin:13px 0;padding:10px 12px;background:var(--color-paper);border-left:3px solid var(--color-brain);border-radius:8px}.surface{padding:14px;border:1px solid color-mix(in srgb,var(--color-ink) 10%,transparent);background:var(--color-elev);border-radius:14px;min-width:0}button{font:inherit;font-size:12px;border:1px solid color-mix(in srgb,var(--color-ink) 14%,transparent);border-radius:9px;padding:8px 10px;background:var(--color-elev);color:var(--color-ink);cursor:pointer;min-height:36px}button:disabled{opacity:.5;cursor:default}button:focus-visible,textarea:focus-visible,summary:focus-visible{outline:2px solid var(--color-brain);outline-offset:2px}.primary,.workspace-tabs button[aria-pressed=true]{background:var(--color-ink);color:var(--color-paper);border-color:var(--color-ink)}.workspace-tabs{display:flex;gap:5px;flex-wrap:wrap}.workspace-tabs button{flex:1;white-space:nowrap}.tab-content{display:grid;gap:13px}.route-layout{display:grid;gap:12px}.step-list{display:grid;gap:5px}.step-list button{display:flex;align-items:flex-start;gap:9px;text-align:left;background:transparent;border-color:transparent}.step-list button[aria-pressed=true]{background:var(--color-soft);border-color:color-mix(in srgb,var(--color-brain) 30%,transparent)}.step-index{color:var(--color-brain);min-width:18px}.step-list small{display:block;font-size:12px;color:var(--color-ink-soft);margin-top:4px}dl{display:grid;gap:12px;margin:13px 0}dt{font-size:12px;color:var(--color-ink-soft);margin-bottom:4px}dd{margin:0;line-height:1.6;overflow-wrap:anywhere}.resource-row,.result-item,.decision-list article+article{border-top:1px solid color-mix(in srgb,var(--color-ink) 12%,transparent);padding:12px 0}.empty{padding:14px 0;color:var(--color-ink-soft)}.error{color:var(--color-warn)}.notice{padding:10px;background:var(--color-soft);border-radius:10px}.preserve{white-space:pre-wrap}.reply{border-left:2px solid var(--color-brain);padding-left:12px;margin-top:13px}textarea{width:100%;padding:10px;margin-top:8px;background:var(--color-paper);color:var(--color-ink);border:1px solid color-mix(in srgb,var(--color-ink) 16%,transparent);border-radius:10px;resize:vertical;font:inherit}label{font-size:12px;color:var(--color-ink-soft)}summary{cursor:pointer;font-size:13px}img{max-width:100%;border-radius:8px;margin-top:12px}figure{margin:12px 0}figcaption{font-size:12px;color:var(--color-ink-soft)}@media(min-width:1500px){.route-layout{grid-template-columns:minmax(120px,.8fr) minmax(0,1.2fr)}}
</style>
