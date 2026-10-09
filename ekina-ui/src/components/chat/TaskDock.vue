<script setup lang="ts">
import { computed, ref } from 'vue';
import { useSessionStore } from '@/stores/session';
import { useConnectionStore } from '@/stores/connection';
import CrewMap from '@/components/canvas/CrewMap.vue';
import ResearchBoard from './ResearchBoard.vue';
import ReviewQueue from './ReviewQueue.vue';
import NewResearchProject from './NewResearchProject.vue';
import type { TaskCardState } from '@/types/project';
const session=useSessionStore();
const connection=useConnectionStore();
const cards=computed(()=>session.taskCards);
const isEkina=computed(()=>session.activeContactId==='ekina');
const expanded=ref(false);
const advanced=ref(false);
const legacyGoal=ref('');
const legacyKind=ref<'mission'|'research'|'reproduce'|'smoke'>('mission');
const starting=ref(false), error=ref('');
function status(card:TaskCardState){
  if(card.board?.error)return '记录读取失败';
  if(card.board?.decisions?.some(d=>d.kind==='authorization'))return '有待授权事项';
  if(card.board?.research?.attentionCount)return '结论待审查';
  if(card.boardStatus==='done')return '已完成';
  if(card.blocked>0 || card.boardStatus==='blocked')return '有阻碍';
  if(card.running>0 || card.boardStatus==='running')return '进行中';
  if(card.boardStatus==='planning')return '规划中';
  return '待推进';
}
async function startLegacy(){
  if(starting.value || !legacyGoal.value.trim())return;
  starting.value=true;error.value='';
  try{if(legacyKind.value==='mission')await session.startMission(legacyGoal.value);else await session.startPaper({titleHint:legacyGoal.value,domain:legacyGoal.value,mode:legacyKind.value,autoRun:true});legacyGoal.value='';advanced.value=false;}
  catch(e){error.value=e instanceof Error?e.message:'创建失败';}finally{starting.value=false;}
}
</script>
<template>
  <aside class="task-dock" :class="{ expanded }" aria-label="项目工作区">
    <header class="dock-header"><div class="header-line"><h2>{{ isEkina ? '研究项目' : '工作区' }}</h2><div class="header-actions"><button @click="expanded=!expanded">{{ expanded ? '收起宽度' : '展开工作区' }}</button><button v-if="isEkina" class="primary" @click="session.newMissionOpen=!session.newMissionOpen">{{ session.newMissionOpen ? '返回项目' : '＋ 新课题' }}</button></div></div>
      <div v-if="cards.length && !session.newMissionOpen" class="project-picker"><label for="research-project-select">当前项目</label><select id="research-project-select" :value="session.focusedTaskId || ''" @change="session.focusTask(($event.target as HTMLSelectElement).value)"><option v-for="card in cards" :key="card.id" :value="card.id">{{ card.title }} · {{ status(card) }}</option></select><button :disabled="!session.focusedTaskId" @click="session.focusedTaskId && session.mentionTask(session.focusedTaskId)">@ 讨论</button></div>
    </header>
    <ReviewQueue v-if="isEkina" />
    <div v-if="isEkina && session.newMissionOpen" class="creation-scroll"><NewResearchProject /><details class="legacy" :open="advanced" @toggle="advanced=($event.target as HTMLDetailsElement).open"><summary>已有执行流程（高级）</summary><p>直接派任务或运行旧论文管线；会立即进入执行，不经过新研究入口的前期讨论。</p><form @submit.prevent="startLegacy"><label>执行方式<select v-model="legacyKind"><option value="mission">通用任务</option><option value="research">论文实验流程</option><option value="reproduce">上游复现流程</option><option value="smoke">链路验证</option></select></label><label>目标<textarea v-model="legacyGoal" rows="3" required /></label><p v-if="error" role="alert">{{ error }}</p><button :disabled="starting || !legacyGoal.trim()">{{ starting ? '启动中…' : '启动已有流程' }}</button></form></details></div>
    <div v-else class="workspace-body"><ResearchBoard v-if="session.focusedTaskId" :key="session.focusedTaskId" :board="session.focusedBoard" :timeline="session.timeline" :project-id="session.focusedTaskId" /><div v-else class="empty"><p>{{ isEkina ? '从一个想法或感兴趣的技术开始。' : '这里会显示当前联系人的工作项目。' }}</p><button v-if="isEkina" @click="session.newMissionOpen=true">创建第一个研究课题</button></div></div>
    <details v-if="isEkina" class="crew-details"><summary>执行小队 {{ connection.crewBusy ? '· 正在工作' : '' }}</summary><div class="crew-body"><CrewMap embedded /></div></details>
  </aside>
</template>
<style scoped>
.task-dock{width:460px;max-width:100%;min-width:0;flex-shrink:0;display:flex;flex-direction:column;height:100%;background:var(--color-paper);border-left:1px solid color-mix(in srgb,var(--color-ink) 10%,transparent);color:var(--color-ink)}.task-dock.expanded{width:660px}.dock-header{padding:14px;border-bottom:1px solid color-mix(in srgb,var(--color-ink) 10%,transparent);flex-shrink:0}.header-line,.header-actions{display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap}h2{font:600 16px var(--font-display);margin:0}.project-picker{display:flex;gap:8px;align-items:center;margin-top:12px}.project-picker label{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%)}.project-picker select{flex:1;min-width:0}.workspace-body{min-height:0;flex:1;display:flex;flex-direction:column;padding:12px}.creation-scroll{min-height:0;overflow:auto;padding:12px;flex:1}.empty{padding:24px 10px;color:var(--color-ink-soft);font-size:13px}.empty p{margin-bottom:12px}button,select,textarea{font:inherit;font-size:12px;border:1px solid color-mix(in srgb,var(--color-ink) 15%,transparent);border-radius:9px;padding:8px;background:var(--color-elev);color:var(--color-ink);min-height:36px}button,summary{cursor:pointer}button:disabled{opacity:.5;cursor:default}button:focus-visible,select:focus-visible,textarea:focus-visible,summary:focus-visible{outline:2px solid var(--color-brain);outline-offset:2px}.primary{background:var(--color-ink);color:var(--color-paper)}.legacy{margin-top:14px;font-size:12px;color:var(--color-ink-soft)}.legacy p{margin:10px 0;line-height:1.6}.legacy form,.legacy label{display:grid;gap:8px}.legacy textarea{resize:vertical}.crew-details{border-top:1px solid color-mix(in srgb,var(--color-ink) 10%,transparent);padding:10px 14px;flex-shrink:0;font-size:12px;color:var(--color-ink-soft)}.crew-body{max-height:200px;overflow:auto;margin-top:10px}
@media(max-width:1200px){.task-dock{width:400px}.task-dock.expanded{width:520px}}
@media(max-width:960px){.task-dock,.task-dock.expanded{width:360px}}
@media(max-width:720px){.task-dock,.task-dock.expanded{width:100%;height:75vh;border-left:0;border-top:1px solid color-mix(in srgb,var(--color-ink) 10%,transparent)}}
</style>
