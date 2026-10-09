<script setup lang="ts">
import { computed, ref } from "vue";
import { useConnectionStore } from "@/stores/connection";
import { cancelStuckTasks } from "@/api/agents";
import { roleColor, roleLabel } from "@/lib/roles";

defineProps<{
  /** When true, hide outer chrome (parent provides title / collapse). */
  embedded?: boolean;
}>();

const connection = useConnectionStore();
const clearing = ref(false);

async function clearStuck() {
  if (clearing.value) return;
  clearing.value = true;
  try {
    await cancelStuckTasks(60);
    await connection.refreshLive();
  } finally {
    clearing.value = false;
  }
}

const chief = computed(() => connection.crewNodes[0]);
const workers = computed(() => connection.crewNodes.slice(1));

const statusLabel: Record<string, string> = {
  idle: "待命",
  running: "进行中",
  waiting: "排队",
  done: "刚完成",
  failed: "失败",
};

function chipClass(status: string) {
  if (status === "running") return "bg-[rgba(61,90,128,0.12)] text-[var(--color-brain)]";
  if (status === "waiting") return "bg-amber-500/15 text-[var(--color-warn)]";
  if (status === "done") return "bg-green-500/15 text-[var(--color-ok)]";
  return "bg-black/5 text-[var(--color-ink-faint)]";
}
</script>

<template>
  <div :class="embedded ? '' : 'overflow-hidden rounded-2xl border border-black/10 bg-[var(--color-elev)] shadow-sm'">
    <div
      v-if="!embedded"
      class="flex items-center justify-between gap-2 border-b border-black/5 px-3 py-2.5"
    >
      <h3 class="text-sm font-semibold">小队分工</h3>
      <button
        v-if="connection.crewBusy"
        type="button"
        class="rounded-full border border-black/10 px-2.5 py-1 text-[0.7rem] font-semibold text-[var(--color-ink-soft)] hover:bg-black/5 disabled:opacity-40"
        :disabled="clearing"
        @click="clearStuck"
      >
        {{ clearing ? "清理中…" : "清卡住任务" }}
      </button>
      <span v-else class="text-[0.72rem] text-[var(--color-ink-faint)]">空闲</span>
    </div>
    <div v-else class="flex justify-end px-3 pt-2">
      <button
        v-if="connection.crewBusy"
        type="button"
        class="rounded-full border border-black/10 px-2.5 py-1 text-[0.7rem] font-semibold text-[var(--color-ink-soft)] hover:bg-black/5 disabled:opacity-40"
        :disabled="clearing"
        @click="clearStuck"
      >
        {{ clearing ? "清理中…" : "清卡住任务" }}
      </button>
    </div>

    <div class="grid justify-items-center gap-2 px-2.5 py-3">
      <button
        v-if="chief"
        type="button"
        class="w-full rounded-[12px] border border-black/10 bg-[var(--color-paper)] p-2.5 text-left transition hover:-translate-y-px hover:shadow-md"
        :class="chief.status === 'running' ? 'border-[rgba(61,90,128,0.45)] shadow-[0_0_0_3px_rgba(61,90,128,0.08)]' : ''"
      >
        <div class="flex items-center gap-2 text-sm font-semibold">
          <span class="h-2.5 w-2.5 rounded-full" :style="{ background: roleColor(chief.role) }" />
          {{ roleLabel(chief.role) }}
        </div>
        <p class="mt-1.5 line-clamp-2 text-xs text-[var(--color-ink-soft)]">{{ chief.job }}</p>
        <span class="mt-2 inline-flex rounded-full px-2 py-0.5 text-[0.7rem] font-bold" :class="chipClass(chief.status)">
          {{ chief.badge || statusLabel[chief.status] }}
        </span>
      </button>

      <div class="h-4 w-0.5 bg-gradient-to-b from-black/15 to-[var(--color-ink-faint)]" aria-hidden="true" />

      <div class="flex w-full flex-col gap-2">
        <button
          v-for="n in workers"
          :key="n.id"
          type="button"
          class="w-full rounded-[12px] border border-black/10 bg-[var(--color-paper)] p-2.5 text-left transition hover:-translate-y-px hover:shadow-md"
          :class="n.status === 'running' ? 'border-[rgba(61,90,128,0.45)] shadow-[0_0_0_3px_rgba(61,90,128,0.08)]' : ''"
        >
          <div class="flex items-center gap-2 text-sm font-semibold">
            <span class="h-2.5 w-2.5 rounded-full" :style="{ background: roleColor(n.role) }" />
            {{ roleLabel(n.role) }}
          </div>
          <p class="mt-1.5 line-clamp-2 text-xs text-[var(--color-ink-soft)]">{{ n.job }}</p>
          <span class="mt-2 inline-flex rounded-full px-2 py-0.5 text-[0.7rem] font-bold" :class="chipClass(n.status)">
            {{ n.badge || statusLabel[n.status] }}
          </span>
        </button>
      </div>
    </div>
  </div>
</template>
