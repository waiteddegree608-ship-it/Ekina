<script setup lang="ts">
import { useSessionStore } from "@/stores/session";
import { roleOfContact } from "@/lib/roles";
import { previewPlainText } from "@/lib/attachments";

const session = useSessionStore();
const base = import.meta.env.BASE_URL;

function fmtTime(ts?: string) {
  if (!ts) return "";
  try {
    const d = new Date(ts);
    if (Number.isNaN(d.getTime())) return String(ts).slice(11, 16);
    return d.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
  } catch {
    return "";
  }
}

/** Prefer the product mark for built-in research roles. */
function avatarOf(c: { id: string; avatarUrl?: string }) {
  if (c.id === "ekina") return "/shell/assets/ekina-mark.svg";
  if (c.id === "agent") return "/shell/assets/ekina-mark.svg";
  const u = c.avatarUrl || "";
  if (u.startsWith("http")) return u;
  if (u.startsWith("/assets/")) return u;
  if (u.startsWith("/shell/")) return u;
  if (u) return u.startsWith("/") ? u : `${base}${u.replace(/^\//, "")}`;
  return `${base}assets/ekina-mark.svg`;
}

function previewOf(c: { id: string; lastPreview?: string; signature?: string }) {
  const cleaned = previewPlainText(c.lastPreview || "");
  if (cleaned) return cleaned;
  return c.signature || roleOfContact(c.id).signature;
}


function onAvatarError(e: Event, _c: { id: string }) {
  const img = e.target as HTMLImageElement;
  img.onerror = null;
  img.src = `${base}assets/ekina-mark.svg`;
}
</script>

<template>
  <aside class="flex h-full w-[260px] shrink-0 flex-col border-r border-black/8 bg-[var(--color-paper)]">
    <div class="flex items-center gap-2.5 px-4 pb-2 pt-4">
      <img :src="`${base}assets/ekina-mark.svg`" alt="Ekina" class="brand-logo h-8 w-8 object-contain" />
      <span class="font-[family-name:var(--font-display)] text-lg font-semibold tracking-wide">Ekina</span>
    </div>

    <nav class="flex min-h-0 flex-1 flex-col gap-0.5 overflow-auto px-2 pb-4 pt-1">

      <button
        v-for="c in session.contacts"
        :key="c.id"
        type="button"
        class="rounded-[12px] px-2.5 py-2 text-left transition"
        :class="
          c.id === session.activeContactId
            ? 'bg-[var(--color-elev)] text-[var(--color-ink)] shadow-sm'
            : 'text-[var(--color-ink-soft)] hover:bg-black/5 hover:text-[var(--color-ink)]'
        "
        @click="session.switchContact(c.id)"
      >
        <div class="flex items-center gap-2.5">
          <img
            :src="avatarOf(c)"
            alt=""
            class="h-9 w-9 shrink-0 rounded-full object-cover ring-1 ring-black/8 bg-[var(--color-soft)]"
            @error="onAvatarError($event, c)"
          />
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-2 text-sm font-medium">
              <span class="truncate">{{ c.displayName || c.id }}</span>
              <span
                v-if="session.unread[c.id]"
                class="ml-auto rounded-full bg-[var(--color-warn)] px-1.5 text-[0.65rem] font-bold text-white"
              >
                {{ session.unread[c.id] }}
              </span>
            </div>
            <small class="mt-0.5 block truncate text-xs text-[var(--color-ink-faint)]">
              {{ previewOf(c) }}
              <template v-if="c.lastTs"> · {{ fmtTime(c.lastTs) }}</template>
            </small>
          </div>
        </div>
      </button>
    </nav>
  </aside>
</template>
