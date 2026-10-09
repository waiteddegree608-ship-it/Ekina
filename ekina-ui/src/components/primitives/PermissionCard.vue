<script setup lang="ts">
import { ref } from "vue";
import type { ChannelMessage } from "@/types/channel";

const props = defineProps<{ item: ChannelMessage }>();
const emit = defineEmits<{
  resolve: [payload: { approve: boolean; comment?: string }];
}>();

const showComment = ref(false);
const comment = ref("");
const done = ref(false);

function finish(approve: boolean) {
  if (done.value) return;
  done.value = true;
  emit("resolve", { approve, comment: comment.value.trim() || undefined });
}
</script>

<template>
  <div
    class="mt-2 w-full max-w-md overflow-hidden rounded-2xl border border-black/10 bg-[var(--color-elev)] shadow-lg"
    :class="{ 'opacity-80': done }"
  >
    <div class="flex items-start justify-between gap-3 border-b border-black/5 px-4 py-3">
      <h3 class="text-[0.95rem] font-semibold">{{ item.text || "需要你的批准" }}</h3>
      <span
        class="rounded-full px-2 py-0.5 text-[0.7rem] font-bold uppercase tracking-wide"
        :class="done ? 'bg-green-500/15 text-[var(--color-ok)]' : 'bg-amber-500/15 text-[var(--color-warn)]'"
      >
        {{ done ? "已处理" : "权限" }}
      </span>
    </div>
    <div class="grid gap-3 px-4 py-3">
      <p class="text-sm text-[var(--color-ink-soft)]">
        可同意、拒绝，或先写附言。这是系统原语面板，接真实 pending 通道。
      </p>
      <div v-if="showComment" class="grid gap-1">
        <label class="text-xs font-medium text-[var(--color-ink-soft)]">附言</label>
        <input
          v-model="comment"
          class="rounded-xl border border-black/10 bg-[var(--color-paper)] px-3 py-2 text-sm outline-none focus:border-black/25"
          placeholder="例如：只许读，不许删"
        />
      </div>
      <div v-if="!done" class="flex flex-wrap justify-end gap-2">
        <button
          type="button"
          class="rounded-xl border border-black/10 px-3 py-2 text-sm font-semibold text-[var(--color-ink-soft)] hover:bg-black/5"
          @click="finish(false)"
        >
          拒绝
        </button>
        <button
          type="button"
          class="rounded-xl px-3 py-2 text-sm font-semibold text-[var(--color-ink-soft)] hover:bg-black/5"
          @click="showComment = !showComment"
        >
          附言
        </button>
        <button
          type="button"
          class="rounded-xl bg-[var(--color-ink)] px-4 py-2 text-sm font-semibold text-[var(--color-paper)]"
          @click="finish(true)"
        >
          同意
        </button>
      </div>
    </div>
  </div>
</template>
