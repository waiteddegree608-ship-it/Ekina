<script setup lang="ts">
import { computed } from "vue";
import type { ProjectTimeline, TimelineStep } from "@/types/project";

const props = defineProps<{
  timeline: ProjectTimeline | null;
  compact?: boolean;
}>();

const steps = computed(() => props.timeline?.steps || []);
const summary = computed(() => props.timeline?.summary || {});
const title = computed(
  () => props.timeline?.project?.title || props.timeline?.goal || "项目进度",
);

function tone(status?: string) {
  const s = (status || "pending").toLowerCase();
  if (s === "succeeded" || s === "done") return "ok";
  if (s === "running" || s === "queued") return "run";
  if (s === "failed" || s === "blocked" || s === "cancelled") return "bad";
  return "wait";
}

function label(status?: string) {
  const s = (status || "pending").toLowerCase();
  const map: Record<string, string> = {
    pending: "未开始",
    queued: "排队",
    running: "进行中",
    succeeded: "已完成",
    done: "已完成",
    failed: "失败",
    blocked: "阻塞",
    cancelled: "已取消",
    unknown: "未知",
  };
  return map[s] || s;
}

function mark(step: TimelineStep) {
  const t = tone(step.status);
  if (t === "ok") return "✓";
  if (t === "run") return "●";
  if (t === "bad") return "!";
  return "○";
}
</script>

<template>
  <div
    class="overflow-hidden rounded-2xl border border-black/10 bg-[var(--color-elev)] shadow-sm"
    :class="compact ? 'px-3 py-2.5' : 'px-4 py-3'"
  >
    <div class="mb-2 flex items-start justify-between gap-2">
      <div class="min-w-0">
        <h3 class="truncate text-sm font-semibold">{{ title }}</h3>
        <p v-if="!compact && timeline?.goal" class="mt-0.5 truncate text-xs text-[var(--color-ink-faint)]">
          {{ timeline.goal }}
        </p>
      </div>
      <span class="shrink-0 rounded-full bg-black/5 px-2 py-0.5 text-[0.7rem] font-medium text-[var(--color-ink-soft)]">
        {{ summary.done ?? 0 }}/{{ summary.total ?? steps.length }}
      </span>
    </div>

    <p v-if="timeline?.note && !steps.length" class="text-xs text-[var(--color-ink-faint)]">
      {{ timeline.note }}
    </p>

    <ol v-else class="relative m-0 grid list-none gap-0 p-0">
      <li
        v-for="(step, i) in steps"
        :key="step.id"
        class="relative grid grid-cols-[20px_1fr] gap-2 py-1.5"
      >
        <div class="relative flex justify-center">
          <span
            v-if="i < steps.length - 1"
            class="absolute top-5 bottom-[-6px] w-px bg-black/10"
          />
          <span
            class="relative z-[1] grid h-5 w-5 place-items-center rounded-full text-[0.65rem] font-bold"
            :class="{
              'bg-green-500/15 text-[var(--color-ok)]': tone(step.status) === 'ok',
              'bg-sky-500/15 text-[var(--color-brain)]': tone(step.status) === 'run',
              'bg-red-500/15 text-red-600': tone(step.status) === 'bad',
              'bg-black/5 text-[var(--color-ink-faint)]': tone(step.status) === 'wait',
            }"
          >
            {{ mark(step) }}
          </span>
        </div>
        <div class="min-w-0">
          <div class="flex items-center gap-2 text-sm">
            <span class="truncate font-medium">{{ step.title || step.id }}</span>
            <span class="shrink-0 text-[0.7rem] text-[var(--color-ink-faint)]">{{ label(step.status) }}</span>
          </div>
          <div
            v-if="!compact && (step.assignee || step.error)"
            class="mt-0.5 truncate text-xs text-[var(--color-ink-faint)]"
          >
            <template v-if="step.assignee">{{ step.assignee }}</template>
            <template v-if="step.error"> · {{ step.error }}</template>
          </div>
        </div>
      </li>
    </ol>
  </div>
</template>
