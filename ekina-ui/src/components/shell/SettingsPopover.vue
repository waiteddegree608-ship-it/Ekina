<script setup lang="ts">
import { onMounted, onUnmounted } from "vue";
import { THEMES, usePrefsStore } from "@/stores/prefs";
import { useSessionStore } from "@/stores/session";
import { useConnectionStore } from "@/stores/connection";

const prefs = usePrefsStore();
const session = useSessionStore();
const connection = useConnectionStore();

function onKey(e: KeyboardEvent) {
  if (e.key === "Escape") prefs.settingsOpen = false;
}

function openCapabilities() {
  session.boardTab = "all";
  session.boardOpen = true;
  prefs.settingsOpen = false;
  void connection.refreshExtensions();
}

onMounted(() => window.addEventListener("keydown", onKey));
onUnmounted(() => window.removeEventListener("keydown", onKey));
</script>

<template>
  <div class="fixed inset-0 z-50" @click.self="prefs.settingsOpen = false">
    <div
      class="absolute right-4 top-14 max-h-[min(80vh,640px)] w-[min(360px,calc(100vw-2rem))] overflow-auto rounded-2xl border border-black/10 bg-[var(--color-elev)] p-4 shadow-2xl"
      @click.stop
    >
      <div class="mb-3 flex items-center justify-between">
        <h2 class="text-sm font-semibold tracking-wide">Settings</h2>
        <button
          type="button"
          class="grid h-7 w-7 place-items-center rounded-lg text-[var(--color-ink-faint)] hover:bg-black/5"
          @click="prefs.settingsOpen = false"
        >
          ✕
        </button>
      </div>

      <div class="mb-4">
        <div class="mb-2 text-xs font-medium text-[var(--color-ink-faint)]">Theme</div>
        <div class="grid grid-cols-5 gap-2">
          <button
            v-for="t in THEMES"
            :key="t.id"
            type="button"
            class="group flex flex-col items-center gap-1.5 rounded-xl border p-2 transition"
            :class="
              prefs.theme === t.id
                ? 'border-[var(--color-brain)] bg-[var(--color-soft)]'
                : 'border-black/8 hover:border-black/20'
            "
            :title="t.label"
            @click="prefs.setTheme(t.id)"
          >
            <span
              class="h-7 w-7 rounded-full border border-black/15 shadow-inner"
              :style="{ background: t.swatch }"
            />
            <span class="text-[0.65rem] text-[var(--color-ink-soft)]">{{ t.label }}</span>
          </button>
        </div>
      </div>

      <label class="mb-3 flex cursor-pointer items-center justify-between gap-3 rounded-xl border border-black/8 px-3 py-2.5">
        <div>
          <div class="text-sm font-medium">禁用 AI 回复 emoji</div>
          <div class="text-xs text-[var(--color-ink-faint)]">显示与新回复中去掉表情符号</div>
        </div>
        <input
          type="checkbox"
          class="h-4 w-4 accent-[var(--color-brain)]"
          :checked="prefs.noEmoji"
          @change="prefs.setNoEmoji(($event.target as HTMLInputElement).checked)"
        />
      </label>

      <button
        type="button"
        class="flex w-full items-center justify-between rounded-xl border border-black/8 px-3 py-2.5 text-left text-sm hover:bg-black/5"
        @click="openCapabilities"
      >
        <span>
          <span class="block font-medium">Capabilities</span>
          <span class="block text-xs text-[var(--color-ink-faint)]">模型 / 本机 / 扩展</span>
        </span>
        <span class="text-[var(--color-ink-faint)]">→</span>
      </button>
    </div>
  </div>
</template>
