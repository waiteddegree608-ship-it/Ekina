<script setup lang="ts">
import { ref } from "vue";
import type { ChannelMessage } from "@/types/channel";

const props = defineProps<{ item: ChannelMessage }>();
const emit = defineEmits<{
  act: [payload: { action: string }];
}>();

const done = ref(false);
const acting = ref(false);

async function act(action: string) {
  if (done.value || acting.value) return;
  acting.value = true;
  emit("act", { action });
  done.value = true;
  acting.value = false;
}
</script>

<template>
  <div
    class="mt-2 w-full max-w-md overflow-hidden rounded-2xl border border-black/10 bg-[var(--color-elev)] shadow-md"
    :class="{ 'opacity-70': done }"
  >
    <div class="flex items-start justify-between gap-3 border-b border-black/5 px-4 py-3">
      <p class="text-sm leading-relaxed">{{ item.text || "提醒" }}</p>
      <span
        class="shrink-0 rounded-full px-2 py-0.5 text-[0.7rem] font-bold uppercase tracking-wide"
        :class="done ? 'bg-green-500/15 text-[var(--color-ok)]' : 'bg-amber-500/15 text-[var(--color-warn)]'"
      >
        {{ done ? "已处理" : "提醒" }}
      </span>
    </div>
    <div v-if="!done" class="flex flex-wrap gap-2 px-4 py-3">
      <button
        type="button"
        class="rounded-xl bg-[var(--color-ink)] px-3 py-2 text-xs font-semibold text-[var(--color-paper)] disabled:opacity-50"
        :disabled="acting"
        @click="act('ack')"
      >
        知道了 · 今天不再提醒
      </button>
      <button
        type="button"
        class="rounded-xl border border-black/10 px-3 py-2 text-xs font-semibold hover:bg-black/5 disabled:opacity-50"
        :disabled="acting"
        @click="act('snooze_30m')"
      >
        30 分钟后再说
      </button>
      <button
        type="button"
        class="rounded-xl border border-black/10 px-3 py-2 text-xs font-semibold hover:bg-black/5 disabled:opacity-50"
        :disabled="acting"
        @click="act('snooze_1h')"
      >
        1 小时后再说
      </button>
    </div>
  </div>
</template>
