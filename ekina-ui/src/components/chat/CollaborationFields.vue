<script setup lang="ts">
import type { CollaborationSettings } from "@/types/project";
const props = defineProps<{ modelValue: CollaborationSettings; disabled?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [value: CollaborationSettings] }>();
function update(patch: Partial<CollaborationSettings>) { emit('update:modelValue', { ...props.modelValue, ...patch }); }
</script>
<template>
  <fieldset :disabled="disabled" class="collaboration-fields">
    <label>参与方式
      <select :value="modelValue.mode" @change="update({ mode: ($event.target as HTMLSelectElement).value as CollaborationSettings['mode'] })">
        <option value="guided">共同探索 · 重要研究判断先讨论</option>
        <option value="checkpoints">关键节点参与 · 其余在授权内推进</option>
        <option value="delegated">委托推进 · 约定范围内自行决定</option>
      </select>
    </label>
    <div class="budget-fields">
      <label>规划预算（可留空）<input aria-label="规划预算" type="number" min="0" max="100000000" step="0.01" :value="modelValue.budget ?? ''" placeholder="尚未约定" @input="update({ budget: ($event.target as HTMLInputElement).value === '' ? null : ($event.target as HTMLInputElement).valueAsNumber })" /></label>
      <label>币种<select :value="modelValue.currency" @change="update({ currency: ($event.target as HTMLSelectElement).value as 'CNY' | 'USD' })"><option value="CNY">人民币</option><option value="USD">美元</option></select></label>
    </div>
    <label>偏好与边界<textarea :value="modelValue.priorities" maxlength="2000" rows="3" placeholder="例如：先验证可行性；昂贵实验先讨论；关键步骤解释给我听。" @input="update({ priorities: ($event.target as HTMLTextAreaElement).value })" /></label>
    <p>预算用于研究规划，尚未接入自动计费与超额停机。参与方式不会扩大资源授权。</p>
  </fieldset>
</template>
<style scoped>
.collaboration-fields{border:0;padding:0;margin:0;display:grid;gap:12px;min-width:0}label{display:grid;gap:6px;font-size:12px;color:var(--color-ink-soft);min-width:0}input,select,textarea{width:100%;min-width:0;background:var(--color-paper);color:var(--color-ink);border:1px solid color-mix(in srgb,var(--color-ink) 16%,transparent);border-radius:10px;padding:9px;font:inherit;font-size:13px}input:focus-visible,select:focus-visible,textarea:focus-visible{outline:2px solid var(--color-brain);outline-offset:2px}.budget-fields{display:grid;grid-template-columns:minmax(0,1fr) 95px;gap:10px}p{font-size:12px;line-height:1.6;color:var(--color-ink-soft);margin:0}
</style>
