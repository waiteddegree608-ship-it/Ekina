<script setup lang="ts">
import { nextTick, watch, ref, computed } from "vue";
import { useSessionStore } from "@/stores/session";
import { useConnectionStore } from "@/stores/connection";
import { usePrefsStore } from "@/stores/prefs";
import { renderMarkdown } from "@/lib/markdown";
import { maybeStripEmojis } from "@/lib/emoji";
import { copyText, exportMarkdown } from "@/lib/export";
import { roleOfContact } from "@/lib/roles";
import PermissionCard from "@/components/primitives/PermissionCard.vue";
import ReminderCard from "@/components/primitives/ReminderCard.vue";
import FileCard from "@/components/primitives/FileCard.vue";
import SettingsPopover from "@/components/shell/SettingsPopover.vue";
import TopGlowBar from "@/components/shell/TopGlowBar.vue";
import InboxIcon from "@/components/shell/InboxIcon.vue";
import Composer from "./Composer.vue";
import TaskDock from "./TaskDock.vue";
import type { ChatBubble } from "@/types/channel";
import { parseAttachments, sanitizeOwnerText } from "@/lib/attachments";

const session = useSessionStore();
const connection = useConnectionStore();
const prefs = usePrefsStore();
const log = ref<HTMLElement | null>(null);
const base = import.meta.env.BASE_URL;
const menu = ref<{ x: number; y: number; bubble: ChatBubble } | null>(null);
const toast = ref("");

const busyLine = computed(() => {
  if (session.busyText) return session.busyText;
  if (session.activeContactId === "ekina") return connection.busyLabel;
  return "";
});
const isEkina = computed(() => session.activeContactId === "ekina");
const pendingCount = computed(() => session.pending.length);

const visibleBubbles = computed(() =>
  session.bubbles.filter((b) => {
    const raw = b.text || "";
    if (
      /tick\s*#?\d+/i.test(raw) ||
      raw.includes("我刚才做了什么") ||
      raw.includes("姬娜刚才做了什么") ||
      raw.includes("自主巡查")
    ) {
      return false;
    }
    const { body, files } = parseAttachments(raw);
    return Boolean(sanitizeOwnerText(body) || files.length || b.kind === "permission" || b.kind === "reminder");
  }),
);

function viewOf(b: ChatBubble) {
  const { body, files } = parseAttachments(b.text || "");
  let text = sanitizeOwnerText(body);
  if (b.side === "her" || b.side === "sys") {
    text = maybeStripEmojis(text, prefs.noEmoji);
  }
  return { body: text, files };
}

watch(
  () => visibleBubbles.value.length,
  async () => {
    await nextTick();
    if (log.value) log.value.scrollTop = log.value.scrollHeight;
  },
);

function openMenu(e: MouseEvent, b: ChatBubble) {
  if (b.side === "sys" && b.kind === "permission") return;
  e.preventDefault();
  menu.value = { x: e.clientX, y: e.clientY, bubble: b };
}

function closeMenu() {
  menu.value = null;
}

function plainTextOf(b: ChatBubble): string {
  return viewOf(b).body || sanitizeOwnerText(b.text || "") || "";
}

function quoteFromMenu() {
  if (!menu.value) return;
  session.quoteBubble(menu.value.bubble);
  closeMenu();
}

async function copyFromMenu() {
  if (!menu.value) return;
  const ok = await copyText(plainTextOf(menu.value.bubble));
  toast.value = ok ? "已复制" : "复制失败";
  window.setTimeout(() => (toast.value = ""), 1600);
  closeMenu();
}

async function exportFromMenu() {
  if (!menu.value) return;
  const text = plainTextOf(menu.value.bubble);
  const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, "-");
  await exportMarkdown(text, `ekina-${stamp}.md`);
  closeMenu();
}

function onDocClick() {
  if (menu.value) closeMenu();
}
</script>

<template>
  <section class="relative flex min-w-0 flex-1" @click="onDocClick">
    <div
      class="relative flex min-w-0 flex-1 flex-col bg-[radial-gradient(900px_420px_at_50%_-10%,rgba(26,26,26,0.04),transparent_60%),var(--color-paper)]"
    >
      <header class="flex items-center justify-between gap-3 px-5 py-3">
        <div class="flex min-w-0 items-center gap-3">
          <div class="min-w-0">
            <div class="flex min-w-0 items-center gap-2 text-sm font-semibold">
              <span
                class="inline-block h-2 w-2 shrink-0 rounded-full"
                :style="{ background: roleOfContact(session.activeContactId).color }"
              />
              <span class="truncate">{{ session.activeContact.displayName || session.activeContactId }}</span>
            </div>
          </div>
        </div>
        <div class="flex items-center gap-1.5">
          <TopGlowBar />
          <button
            type="button"
            class="relative grid h-9 w-9 place-items-center rounded-full border border-black/10 bg-[var(--color-elev)] text-[var(--color-ink-soft)] hover:bg-black/5"
            title="Inbox"
            @click="session.inboxOpen = !session.inboxOpen"
          >
            <InboxIcon />
            <span
              v-if="pendingCount"
              class="absolute -right-1 -top-1 grid min-w-4 place-items-center rounded-full bg-[var(--color-warn)] px-1 text-[0.65rem] font-bold text-white"
            >
              {{ pendingCount }}
            </span>
          </button>
          <button
            type="button"
            class="grid h-9 w-9 place-items-center rounded-full border border-black/10 bg-[var(--color-elev)] text-[var(--color-ink-soft)] hover:bg-black/5"
            title="Settings"
            @click="prefs.settingsOpen = !prefs.settingsOpen"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
              <circle cx="12" cy="12" r="3" />
              <path
                d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9c.3.6.9 1 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"
              />
            </svg>
          </button>
        </div>
      </header>

      <div
        v-if="busyLine"
        class="mx-auto mb-1 flex max-w-[720px] items-center gap-2 rounded-full border border-black/8 bg-[var(--color-elev)] px-3.5 py-2 text-sm text-[var(--color-ink-soft)]"
      >
        <span class="h-2 w-2 animate-pulse rounded-full bg-[var(--color-brain)]" />
        {{ busyLine }}
      </div>

      <div ref="log" class="min-h-0 flex-1 overflow-auto">
        <div class="mx-auto flex w-full max-w-[720px] flex-col gap-5 px-5 py-4 pb-8">
          <div
            v-if="!visibleBubbles.length && isEkina"
            class="flex flex-1 flex-col items-center justify-center gap-3 py-16 text-center"
          >
            <img :src="`${base}assets/ekina-mark.svg`" alt="" class="brand-logo h-20 w-20 object-contain opacity-90" />
            <h1 class="font-[family-name:var(--font-display)] text-3xl font-semibold tracking-tight">Ekina</h1>
            <p class="max-w-sm text-[var(--color-ink-soft)]">
              从一个研究问题开始，整理文献、核对证据、推进实验，交付可追溯的研究成果。
            </p>
          </div>
          <div
            v-else-if="!visibleBubbles.length"
            class="flex flex-1 flex-col items-center justify-center gap-2 py-16 text-center"
          >
            <p class="text-sm text-[var(--color-ink-soft)]">代理人待命。</p>
          </div>

          <div
            v-for="b in visibleBubbles"
            :key="b.id"
            class="grid gap-2 selectable"
            :class="b.side === 'me' ? 'justify-items-end' : ''"
            :style="
              b.side === 'her'
                ? { borderLeft: `2px solid ${roleOfContact(b.roleId || session.activeContactId).color}`, paddingLeft: '10px' }
                : undefined
            "
            @contextmenu="openMenu($event, b)"
          >
            <div
              v-if="b.side === 'her'"
              class="flex items-center gap-1.5 text-xs font-semibold tracking-wide text-[var(--color-ink-faint)]"
            >
              <span
                class="inline-block h-2 w-2 rounded-full"
                :style="{ background: roleOfContact(b.roleId || session.activeContactId).color }"
              />
              {{ roleOfContact(b.roleId || session.activeContactId).displayName }}
              <span
                v-if="b.kind === 'notice'"
                class="rounded-full bg-amber-500/15 px-1.5 py-0.5 text-[0.65rem] font-medium text-amber-800"
              >通知</span>
              <span
                v-else-if="b.kind === 'report'"
                class="rounded-full bg-sky-500/15 px-1.5 py-0.5 text-[0.65rem] font-medium text-sky-800"
              >汇报</span>
            </div>

            <div
              v-if="b.side === 'me'"
              class="grid max-w-[92%] gap-2 justify-items-end"
            >
              <div
                v-if="viewOf(b).body"
                class="selectable rounded-[18px] rounded-br-md bg-[var(--color-ink)] px-4 py-3 text-[var(--color-paper)] whitespace-pre-wrap"
              >
                {{ viewOf(b).body }}
              </div>
              <FileCard
                v-for="f in viewOf(b).files"
                :key="f.path"
                :file="f"
              />
            </div>
            <div
              v-else-if="b.side === 'sys'"
              class="selectable max-w-[92%] rounded-xl bg-[var(--color-soft)] px-3 py-2 text-sm text-[var(--color-ink-soft)]"
            >
              <template v-if="b.kind !== 'reminder'">{{ sanitizeOwnerText(b.text) }}</template>
              <PermissionCard
                v-if="b.kind === 'permission' && b.raw && (b.raw.status || 'pending') === 'pending'"
                class="mt-2"
                :item="b.raw"
                @resolve="({ approve, comment }) => session.resolvePending(b.raw!.id, approve, comment)"
              />
              <ReminderCard
                v-if="b.kind === 'reminder' && b.raw && (b.raw.status || 'pending') === 'pending'"
                :item="b.raw"
                @act="({ action }) => session.resolveReminder(b.raw!.id, action)"
              />
              <p v-else-if="b.kind === 'reminder'">{{ b.text }}</p>
            </div>
            <div
              v-else
              class="grid max-w-[92%] gap-2 text-[15px] leading-relaxed"
              :class="
                b.kind === 'notice'
                  ? 'rounded-xl border border-amber-500/25 bg-amber-500/8 px-3 py-2.5'
                  : b.kind === 'report'
                    ? 'rounded-xl border border-sky-500/20 bg-sky-500/6 px-3 py-2.5'
                    : ''
              "
            >
              <div v-if="viewOf(b).body" class="md selectable" v-html="renderMarkdown(viewOf(b).body)" />
              <FileCard
                v-for="f in viewOf(b).files"
                :key="f.path"
                :file="f"
              />
            </div>
          </div>

          <PermissionCard
            v-for="p in session.pending.filter(
              (x) =>
                x.type === 'permission_request' &&
                (!x.threadId || x.threadId === session.activeThreadId),
            )"
            :key="'pend-' + p.id"
            :item="p"
            @resolve="({ approve, comment }) => session.resolvePending(p.id, approve, comment)"
          />
        </div>
      </div>

      <div class="px-5 pb-5">
        <Composer />
      </div>

      <aside
        v-if="session.inboxOpen"
        class="absolute inset-y-0 right-0 z-20 w-[min(360px,100%)] border-l border-black/10 bg-[var(--color-elev)] shadow-2xl"
      >
        <div class="flex items-start justify-between border-b border-black/8 px-4 py-4">
          <div>
            <h2 class="font-[family-name:var(--font-display)] text-lg font-semibold">Inbox</h2>
            <p class="text-sm text-[var(--color-ink-soft)]">权限 / 提醒 / 方案</p>
          </div>
          <button type="button" class="grid h-8 w-8 place-items-center rounded-lg hover:bg-black/5" @click="session.inboxOpen = false">
            ✕
          </button>
        </div>
        <div class="grid gap-3 overflow-auto p-4">
          <p v-if="!session.pending.length" class="text-sm text-[var(--color-ink-faint)]">暂无待办</p>
          <div
            v-for="p in session.pending"
            :key="p.id"
            class="rounded-2xl border border-black/8 bg-[var(--color-paper)] p-3"
          >
            <div class="text-xs font-bold uppercase tracking-wide text-[var(--color-warn)]">
              {{ p.type }}
            </div>
            <div v-if="p.type === 'permission_request'" class="mt-2">
              <PermissionCard :item="p" @resolve="({ approve, comment }) => session.resolvePending(p.id, approve, comment)" />
            </div>
            <div v-else-if="p.type === 'reminder'" class="mt-1">
              <ReminderCard :item="p" @act="({ action }) => session.resolveReminder(p.id, action)" />
            </div>
            <p v-else class="mt-1 text-sm">{{ p.text }}</p>
          </div>
        </div>
      </aside>
    </div>

    <TaskDock />
    <SettingsPopover v-if="prefs.settingsOpen" />

    <div
      v-if="menu"
      class="fixed z-50 min-w-[160px] overflow-hidden rounded-xl border border-black/10 bg-[var(--color-elev)] py-1 shadow-xl"
      :style="{ left: menu.x + 'px', top: menu.y + 'px' }"
      @click.stop
    >
      <button type="button" class="block w-full px-3.5 py-2 text-left text-sm hover:bg-black/5" @click="quoteFromMenu">
        引用
      </button>
      <button type="button" class="block w-full px-3.5 py-2 text-left text-sm hover:bg-black/5" @click="copyFromMenu">
        复制文本
      </button>
      <button type="button" class="block w-full px-3.5 py-2 text-left text-sm hover:bg-black/5" @click="exportFromMenu">
        导出为 Markdown
      </button>
    </div>

    <div
      v-if="toast"
      class="pointer-events-none absolute bottom-24 left-1/2 z-50 -translate-x-1/2 rounded-full bg-[var(--color-ink)] px-3 py-1.5 text-xs text-[var(--color-paper)] shadow-lg"
    >
      {{ toast }}
    </div>
  </section>
</template>
