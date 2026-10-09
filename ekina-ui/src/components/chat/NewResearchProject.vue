<script setup lang="ts">
import { ref, watch } from 'vue';
import { createResearchProject } from '@/api/projects';
import { useSessionStore } from '@/stores/session';
import type { CollaborationSettings } from '@/types/project';
import CollaborationFields from './CollaborationFields.vue';
const session=useSessionStore();
const entry=ref<'idea'|'explore'>('idea');
const brief=ref('');
const settings=ref<CollaborationSettings>({mode:'checkpoints',budget:null,currency:'CNY',priorities:''});
const busy=ref(false), error=ref('');
const requestId=ref(crypto.randomUUID());
watch([entry,brief],()=>{requestId.value=crypto.randomUUID()});
async function submit(){
  if(busy.value || !brief.value.trim()) return;
  busy.value=true;error.value='';
  const contact=session.activeContactId;
  try{
    const project=await createResearchProject({brief:brief.value.trim(),entry:entry.value,collaboration:{...settings.value},requestId:requestId.value});
    await session.refreshTaskCards();
    if(session.activeContactId!==contact) return;
    if(!session.taskCards.some(c=>c.id===project.id)) throw new Error('课题已保存，但列表暂未刷新成功；可重试，不会重复创建。');
    session.focusTask(project.id);
    session.newMissionOpen=false;
    // Creation is distinct from executing research; let the owner review before the first model call.
  }catch(e){const v=e as {response?:{data?:{error?:string}},message?:string};error.value=v.response?.data?.error || v.message || '创建失败';}
  finally{busy.value=false;}
}
</script>
<template>
  <form class="new-research" @submit.prevent="submit">
    <fieldset :disabled="busy">
      <div class="entry-types"><button type="button" :aria-pressed="entry==='idea'" @click="entry='idea'">从方向或 idea 开始</button><button type="button" :aria-pressed="entry==='explore'" @click="entry='explore'">探索感兴趣的技术</button></div>
      <label for="research-brief">{{ entry==='idea' ? '你的方向或初步想法' : '技术、来源链接，或吸引你的地方' }}</label>
      <textarea id="research-brief" v-model="brief" maxlength="2000" rows="4" required :placeholder="entry==='idea' ? '想做什么、希望解决什么问题？不需要先准备完整研究方案。' : '把你看到的内容或链接放在这里，先查证与比较，再决定是否实现。'" />
      <p>{{ entry==='idea' ? '第一份交付：候选选题、相近工作与最小验证方案。' : '第一份交付：原始来源核验、研究现状与技术比较报告。' }}</p>
      <CollaborationFields v-model="settings" :disabled="busy" />
    </fieldset>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <div class="actions"><button type="button" :disabled="busy" @click="session.newMissionOpen=false">取消</button><button class="primary" :disabled="busy || !brief.trim()">{{ busy ? '创建中…' : '创建研究课题' }}</button></div>
    <p>先保存目标与约定，再从课题板开始调研；不会立即启动付费算力。</p>
  </form>
</template>
<style scoped>
.new-research{padding:14px;background:var(--color-elev);border:1px solid color-mix(in srgb,var(--color-ink) 12%,transparent);border-radius:14px;font-size:13px}fieldset{border:0;padding:0;min-width:0;display:grid;gap:10px}.entry-types,.actions{display:flex;gap:8px;flex-wrap:wrap}.entry-types button{flex:1}.actions{justify-content:flex-end;margin-top:12px}button{background:var(--color-paper);color:var(--color-ink);border:1px solid color-mix(in srgb,var(--color-ink) 15%,transparent);border-radius:9px;padding:9px;font:inherit;font-size:12px;cursor:pointer}button[aria-pressed=true],.primary{background:var(--color-ink);color:var(--color-paper)}button:disabled{opacity:.5;cursor:default}button:focus-visible,textarea:focus-visible{outline:2px solid var(--color-brain);outline-offset:2px}textarea{width:100%;padding:10px;border:1px solid color-mix(in srgb,var(--color-ink) 15%,transparent);border-radius:10px;background:var(--color-paper);color:var(--color-ink);font:inherit;resize:vertical}p{font-size:12px;color:var(--color-ink-soft);line-height:1.6;margin:8px 0}label{font-size:12px;color:var(--color-ink-soft)}.error{color:var(--color-warn)}
</style>
