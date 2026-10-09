<script setup lang="ts">
import { computed } from "vue";
import type { ChatAttachment } from "@/lib/attachments";
import { fileKindLabel } from "@/lib/attachments";

const props = defineProps<{
  file?: ChatAttachment;
  name?: string;
  path?: string;
  dark?: boolean;
}>();

const resolvedName = computed(() => props.file?.name || props.name || "附件");
const resolvedPath = computed(() => props.file?.path || props.path || "");
const label = computed(() => {
  const ext = props.file?.ext || (resolvedName.value.includes(".") ? resolvedName.value.split(".").pop() || "" : "");
  return fileKindLabel(ext.toLowerCase());
});

const shortName = computed(() => {
  const n = resolvedName.value;
  return n.length > 42 ? n.slice(0, 38) + "…" : n;
});

async function copyPath() {
  if (!resolvedPath.value) return;
  try {
    await navigator.clipboard.writeText(resolvedPath.value);
  } catch {
    /* ignore */
  }
}
</script>

<template>
  <button
    type="button"
    class="file-card flex max-w-full items-center gap-3 rounded-xl border px-3 py-2.5 text-left transition hover:brightness-[1.03]"
    :title="resolvedPath"
    @click="copyPath"
  >
    <span
      class="grid h-10 w-10 shrink-0 place-items-center rounded-lg text-[0.65rem] font-bold tracking-wide text-white"
      :class="label === 'PDF' ? 'bg-red-600' : 'bg-[var(--color-brain)]'"
    >
      {{ label.slice(0, 4) }}
    </span>
    <span class="min-w-0 flex-1">
      <span class="file-card__name block truncate text-sm font-medium">{{ shortName }}</span>
      <span class="file-card__meta mt-0.5 block text-xs">{{ label }} · 点击复制路径</span>
    </span>
  </button>
</template>

<style scoped>
.file-card {
  background: var(--color-soft);
  border-color: color-mix(in srgb, var(--color-ink) 12%, transparent);
}
.file-card__name {
  color: var(--color-ink);
}
.file-card__meta {
  color: var(--color-ink-soft);
}
</style>
