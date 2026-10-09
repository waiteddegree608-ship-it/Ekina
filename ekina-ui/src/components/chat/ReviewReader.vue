<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { api, USER_ID } from '@/api/client';
import { renderMarkdown } from '@/lib/markdown';
const props=defineProps<{campaignId:string;topicId:string;title:string;status:string;initialView?:'text'|'figures'}>();
const emit=defineEmits<{close:[]}>();
type Figure={id:string;file:string;title:string;caption:string;sourceIds:string[];evidenceBoundary:string;afterSection?:string;panels?:{sourceId:string;originalFigure:string;page:number;sourceUrl:string}[]};
const prose=ref(''),figures=ref<Figure[]>([]),error=ref(''),busy=ref(false);
const view=ref(props.initialView ?? 'text');
function caption(f:Figure){return `${f.panels?'原论文图补充 · ':''}${f.id} · ${f.title}。${f.caption} 来源：${f.sourceIds.join('、')}。${f.evidenceBoundary}${f.panels?' 原图定位：'+f.panels.map(p=>`[${p.sourceId}] ${p.originalFigure}，PDF第${p.page}页，${p.sourceUrl}`).join('；'):''}`;}
function readingProse(){
  const lines=prose.value.split('\n'),insertions=new Map<number,string[]>();
  for(const f of figures.value.filter(x=>x.afterSection)){
    const positions=lines.flatMap((line,i)=>line===f.afterSection?[i]:[]);
    if(positions.length!==1||!/^#{1,6} /.test(f.afterSection!))continue;
    const level=f.afterSection!.split(' ')[0]!.length;let end=lines.length;
    for(let i=positions[0]!+1;i<lines.length;i++){const h=/^(#{1,6}) /.exec(lines[i]!);if(h&&h[1]!.length<=level){end=i;break;}}
    insertions.set(end,[...(insertions.get(end)??[]),`\n![${f.id}](${f.file})\n`]);
  }
  for(const [i,items] of [...insertions].sort((a,b)=>b[0]-a[0]))lines.splice(i,0,...items);
  return lines.join('\n');
}
function url(name:string){return `/api/reviews/${encodeURIComponent(props.campaignId)}/topics/${encodeURIComponent(props.topicId)}/artifacts/${encodeURIComponent(name)}?userId=${encodeURIComponent(USER_ID)}`;}
const rendered=computed(()=>{
  const doc=new DOMParser().parseFromString(renderMarkdown(readingProse()),'text/html');
  for(const img of doc.querySelectorAll('img')){
    const f=figures.value.find(f=>f.file===img.getAttribute('src'));
    if(!f){img.replaceWith(doc.createTextNode('［图片尚未生成］'));continue;}
    img.src=url(f.file.replace(/\.svg$/,'.png'));img.alt=f.title;
    const wrapper=doc.createElement('figure'),cap=doc.createElement('figcaption');
    cap.textContent=caption(f);
    img.replaceWith(wrapper);wrapper.append(img,cap);
  }
  for(const a of doc.querySelectorAll('a')){a.target='_blank';a.rel='noopener noreferrer';}
  return doc.body.innerHTML;
});
const headings=computed(()=>prose.value.split('\n').filter(x=>/^##? /.test(x)).map(x=>x.replace(/^#+ /,'')));
function closeOnEscape(e:KeyboardEvent){if(e.key==='Escape')emit('close');}
onMounted(async()=>{document.addEventListener('keydown',closeOnEscape);try{
  const [text,manifest,papers]=await Promise.allSettled([api.get(url('review.md'),{responseType:'text'}),api.get(url('figures.json'),{responseType:'text'}),api.get(url('paper-figures.json'),{responseType:'text'})]);
  if(text.status==='rejected')throw text.reason;prose.value=String(text.value.data);
  if(manifest.status==='fulfilled')figures.value=JSON.parse(String(manifest.value.data));
  if(papers.status==='fulfilled')figures.value.push(...JSON.parse(String(papers.value.data)));
}catch(e){error.value=String(e);}});
onUnmounted(()=>document.removeEventListener('keydown',closeOnEscape));
async function download(){busy.value=true;try{
  const doc=new DOMParser().parseFromString(rendered.value,'text/html');
  for(const img of doc.querySelectorAll('img')){
    const response=await fetch(img.src);if(!response.ok)throw new Error('图片尚未就绪');
    const blob=await response.blob();img.src=await new Promise<string>((resolve,reject)=>{const r=new FileReader();r.onload=()=>resolve(String(r.result));r.onerror=reject;r.readAsDataURL(blob);});
  }
  const title=document.createElement('span');title.textContent=props.title;
  const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>${title.innerHTML}</title><style>body{font:17px/1.9 'Microsoft YaHei',sans-serif;max-width:940px;margin:48px auto;padding:0 28px;color:#25313d}h1,h2,h3{line-height:1.4}h2{margin-top:2em}img{max-width:100%;height:auto}figure{margin:28px 0;break-inside:avoid}figcaption{font-size:14px;color:#526171}table{border-collapse:collapse;width:100%;font-size:14px}td,th{border:1px solid #d8dfe6;padding:8px}a{color:#316b87}.notice{background:#eef3f6;padding:14px;border-radius:8px}@media print{body{margin:0}h2,h3{break-after:avoid}}</style><body><p class="notice">Ekina 中文图文阅读稿 · ${props.status==='draft_ready'?'已通过模型核查，非专家验收':'尚在制作或核查，非最终稿'}</p>${doc.body.innerHTML}</body></html>`;
  const object=URL.createObjectURL(new Blob([html],{type:'text/html;charset=utf-8'}));const a=document.createElement('a');a.href=object;a.download=`${props.title.replace(/[\\/:*?"<>|]/g,'-')}.html`;a.click();setTimeout(()=>URL.revokeObjectURL(object),1000);
}catch(e){error.value=String(e);}finally{busy.value=false;}}
</script>
<template>
  <Teleport to="body"><div class="reader-overlay" @click.self="emit('close')"><section class="reader-dialog" role="dialog" aria-modal="true" :aria-label="title">
    <header><div><strong>{{title}}</strong><small>中文阅读稿 · {{figures.length}}幅综述图 · {{status==='draft_ready'?'模型核查已通过，非专家验收':'制作或核查中'}}</small></div><div><button :disabled="busy || !prose" @click="download">保存图文稿</button><button aria-label="关闭阅读稿" @click="emit('close')">关闭</button></div></header>
    <nav class="reader-tabs"><button :aria-pressed="view==='text'" @click="view='text'">图文正文</button><button :aria-pressed="view==='figures'" @click="view='figures'">全部图片（{{figures.length}}）</button></nav>
    <p v-if="error" role="alert">{{error}}</p><div v-if="view==='text'" class="reader-content"><aside><p>阅读结构</p><ol><li v-for="h in headings" :key="h">{{h}}</li></ol></aside><article v-html="rendered" /></div>
    <div v-else class="reader-gallery"><p v-if="!figures.length">当前稿还没有完成配图；文字版本会先保存，图片生成后出现在这里。</p><figure v-for="f in figures" :key="f.id"><h3>{{f.id}} · {{f.title}}</h3><a :href="url(f.file.replace(/\.svg$/,'.png'))" target="_blank" rel="noopener">打开原尺寸图片<img :src="url(f.file.replace(/\.svg$/,'.png'))" :alt="f.title" /></a><figcaption>{{caption(f)}}</figcaption></figure></div>
  </section></div></Teleport>
</template>
<style scoped>
.reader-tabs{padding:10px 16px;border-bottom:1px solid #dde4e8}.reader-tabs button[aria-pressed=true]{background:#e1edf2}.reader-gallery{overflow:auto;padding:24px;min-height:0}.reader-gallery figure{max-width:1100px;margin:0 auto 32px}.reader-gallery img{display:block;max-width:100%;height:auto;margin-top:10px}.reader-gallery figcaption{line-height:1.8;font-size:14px;color:#526775}
.reader-overlay{position:fixed;inset:0;background:#17243599;z-index:10000;display:grid;place-items:center;padding:24px}.reader-dialog{background:#fafbfc;color:#273643;width:min(1320px,100%);height:94vh;border-radius:16px;display:flex;flex-direction:column;box-shadow:0 20px 80px #0005;overflow:hidden}.reader-dialog header{padding:18px 24px;border-bottom:1px solid #dde4e8;display:flex;justify-content:space-between;gap:20px;background:white}.reader-dialog small{display:block;color:#657483;margin-top:6px}.reader-dialog button{padding:9px 14px;margin-left:8px;border:1px solid #cad6df;background:white;color:#244a5e;border-radius:7px;cursor:pointer}.reader-content{display:flex;overflow:auto}.reader-content aside{width:210px;flex-shrink:0;padding:24px;font-size:12px;line-height:1.9;color:#697786}.reader-content article{background:white;padding:36px 52px;max-width:940px;min-width:0;line-height:1.95;font-size:16px}.reader-content article :deep(h1){font-size:28px;line-height:1.45}.reader-content article :deep(h2){font-size:22px;margin-top:2em;line-height:1.5;border-bottom:1px solid #dfe8ec;padding-bottom:10px}.reader-content article :deep(figure){margin:28px 0}.reader-content article :deep(img){display:block;width:100%;height:auto}.reader-content article :deep(figcaption){font-size:13px;line-height:1.8;color:#526775;background:#f4f7f9;padding:14px}.reader-content article :deep(table){border-collapse:collapse;font-size:14px;max-width:100%}.reader-content article :deep(td),.reader-content article :deep(th){border:1px solid #dbe3e8;padding:8px}.reader-content article :deep(.table-wrap){overflow:auto}.reader-content article :deep(a){color:#257395}@media(max-width:850px){.reader-content aside{display:none}.reader-content article{padding:22px}.reader-overlay{padding:8px}}
</style>
