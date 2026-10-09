<script setup lang="ts">
import { computed } from "vue";
import { useConnectionStore, type GlowStatus } from "@/stores/connection";
import { useSessionStore } from "@/stores/session";

const connection = useConnectionStore();
const session = useSessionStore();

const isEkina = computed(() => session.activeContactId === "ekina");

function openBoard(tab: "all" | "brain" | "hand" | "room" | "ext" = "all") {
  session.boardTab = tab;
  session.boardOpen = true;
  void connection.refreshExtensions();
}

function dotClass(status: GlowStatus) {
  if (status === "ok") return "bg-[var(--color-ok)] shadow-[0_0_0_3px_rgba(47,125,74,0.12)]";
  if (status === "warn") return "bg-[var(--color-warn)] shadow-[0_0_0_3px_rgba(184,106,18,0.15)]";
  return "bg-[var(--color-ink-faint)] shadow-[0_0_0_3px_rgba(160,156,148,0.15)]";
}
</script>

<template>
  <div class="flex items-center gap-1.5 rounded-full border border-black/8 bg-[var(--color-elev)] p-1 shadow-sm">
    <button
      type="button"
      class="flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium text-[var(--color-ink-soft)] hover:bg-black/5 hover:text-[var(--color-ink)]"
      :title="isEkina ? '大脑 · 模型与 API' : '大脑 · 代理人模型'"
      @click="openBoard('brain')"
    >
      <span class="h-2 w-2 rounded-full" :class="dotClass(connection.glowModel)" />
      <span class="hidden sm:inline">{{ connection.balanceSummary || "模型" }}</span>
    </button>
    <button
      v-if="isEkina"
      type="button"
      class="flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium text-[var(--color-ink-soft)] hover:bg-black/5 hover:text-[var(--color-ink)]"
      title="手 · SSH 算力服务器"
      @click="openBoard('hand')"
    >
      <span class="h-2 w-2 rounded-full" :class="dotClass(connection.glowServer)" />
      <span class="hidden sm:inline">服务器</span>
    </button>
    <button
      v-else
      type="button"
      class="flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium text-[var(--color-ink-soft)] hover:bg-black/5 hover:text-[var(--color-ink)]"
      title="本机 · 允许文件夹与索引"
      @click="openBoard('hand')"
    >
      <span class="h-2 w-2 rounded-full" :class="dotClass(connection.glowExt)" />
      <span class="hidden sm:inline">本机</span>
    </button>
    <button
      type="button"
      class="flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium text-[var(--color-ink-soft)] hover:bg-black/5 hover:text-[var(--color-ink)]"
      :title="isEkina ? '扩展 · MCP / Skills' : '扩展 · 本机办公技能'"
      @click="openBoard('ext')"
    >
      <span class="h-2 w-2 rounded-full" :class="dotClass(connection.glowExt)" />
      <span class="hidden sm:inline">扩展</span>
    </button>
  </div>
</template>
