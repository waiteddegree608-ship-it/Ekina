<script setup lang="ts">
import { ref, watch, nextTick } from "vue";
import { useSessionStore } from "@/stores/session";
import { uploadChatFile } from "@/api/upload";

const session = useSessionStore();
const text = ref("");
const ta = ref<HTMLTextAreaElement | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);
const dragging = ref(false);
const uploading = ref(false);
const attachments = ref<{ name: string; path: string }[]>([]);

watch(text, () => {
  const el = ta.value;
  if (!el) return;
  el.style.height = "auto";
  el.style.height = Math.min(el.scrollHeight, 160) + "px";
});

watch(
  () => session.composerInsert,
  async (token) => {
    if (!token) return;
    const insert = session.consumeComposerInsert();
    if (!insert) return;
    const cur = text.value;
    if (cur.includes(insert)) {
      await nextTick();
      ta.value?.focus();
      return;
    }
    text.value = cur.trim() ? `${cur.replace(/\s+$/, "")} ${insert} ` : `${insert} `;
    await nextTick();
    ta.value?.focus();
  },
);

watch(
  () => session.composerQuote,
  async (q) => {
    if (!q) return;
    await nextTick();
    ta.value?.focus();
  },
);

async function addFiles(files: FileList | File[]) {
  const list = Array.from(files || []);
  if (!list.length) return;
  uploading.value = true;
  try {
    for (const f of list) {
      const res = await uploadChatFile(f, session.activeContactId);
      if (res.status === "SUCCESS" && res.absolutePath) {
        attachments.value.push({
          name: res.originalName || f.name,
          path: res.absolutePath,
        });
      } else {
        window.alert("上传失败：" + (res.message || f.name));
      }
    }
  } catch (e) {
    window.alert("上传失败：" + (e instanceof Error ? e.message : String(e)));
  } finally {
    uploading.value = false;
  }
}

function openFilePicker() {
  fileInput.value?.click();
}

function onFilePicked(e: Event) {
  const input = e.target as HTMLInputElement;
  if (input.files?.length) void addFiles(input.files);
  input.value = "";
}

function onDrop(e: DragEvent) {
  dragging.value = false;
  e.preventDefault();
  if (e.dataTransfer?.files?.length) {
    void addFiles(e.dataTransfer.files);
  }
}

function onPaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items;
  if (!items) return;
  const files: File[] = [];
  for (const it of items) {
    if (it.kind === "file") {
      const f = it.getAsFile();
      if (f) files.push(f);
    }
  }
  if (files.length) {
    e.preventDefault();
    void addFiles(files);
  }
}

function removeAttachment(i: number) {
  attachments.value.splice(i, 1);
}

async function submit() {
  const v = text.value.trim();
  if ((!v && !attachments.value.length) || session.sending || uploading.value) return;
  let message = v;
  if (attachments.value.length) {
    const lines = attachments.value.map((a) => `- ${a.name}: ${a.path}`);
    message = (v ? v + "\n\n" : "") + "【附件】\n" + lines.join("\n");
  }
  text.value = "";
  attachments.value = [];
  await session.send(message);
  ta.value?.focus();
}

function onKey(e: KeyboardEvent) {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    void submit();
  }
}
</script>

<template>
  <div class="mx-auto w-full max-w-[720px]">
    <div
      v-if="session.composerQuote"
      class="mb-2 flex items-start gap-2 rounded-xl border border-black/8 bg-[var(--color-soft)] px-3 py-2"
    >
      <div class="min-w-0 flex-1 border-l-2 border-[var(--color-brain)] pl-2.5">
        <div class="text-[0.7rem] font-semibold text-[var(--color-ink-faint)]">引用</div>
        <p class="mt-0.5 line-clamp-2 text-sm text-[var(--color-ink-soft)]">
          {{ session.composerQuote.text }}
        </p>
      </div>
      <button
        type="button"
        class="grid h-7 w-7 shrink-0 place-items-center rounded-lg text-[var(--color-ink-faint)] hover:bg-black/5"
        title="取消引用"
        @click="session.clearComposerQuote()"
      >
        ✕
      </button>
    </div>

    <div v-if="attachments.length" class="mb-2 flex flex-wrap gap-2">
      <div
        v-for="(a, i) in attachments"
        :key="a.path + i"
        class="flex max-w-full items-center gap-2 rounded-xl border border-black/10 bg-[var(--color-soft)] px-2.5 py-1.5 text-xs text-[var(--color-ink)]"
      >
        <span class="truncate" :title="a.path">{{ a.name }}</span>
        <button type="button" class="text-[var(--color-ink-faint)] hover:text-[var(--color-ink)]" @click="removeAttachment(i)">
          ✕
        </button>
      </div>
    </div>

    <input ref="fileInput" type="file" class="hidden" multiple @change="onFilePicked" />

    <form
      class="flex w-full items-end gap-2 rounded-[22px] border bg-[var(--color-elev)] px-3 py-2.5 shadow-lg transition focus-within:border-black/20 focus-within:shadow-xl"
      :class="dragging ? 'border-[var(--color-brain)] ring-2 ring-[var(--color-brain)]/20' : 'border-black/10'"
      @submit.prevent="submit"
      @dragenter.prevent="dragging = true"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop="onDrop"
    >
      <button
        type="button"
        class="grid h-9 w-9 place-items-center rounded-lg text-[var(--color-ink-soft)] hover:bg-black/5"
        title="添加文件"
        :disabled="uploading"
        @click="openFilePicker"
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <path d="M12 5v14M5 12h14" stroke-linecap="round" />
        </svg>
      </button>
      <textarea
        ref="ta"
        v-model="text"
        rows="1"
        class="max-h-40 min-h-6 flex-1 resize-none bg-transparent py-1.5 text-[15px] outline-none placeholder:text-[var(--color-ink-faint)]"
        :placeholder="uploading ? '正在上传…' : dragging ? '松开以添加文件' : ''"
        @keydown="onKey"
        @paste="onPaste"
      />
      <button
        type="submit"
        class="grid h-9 w-9 place-items-center rounded-full bg-[var(--color-ink)] text-[var(--color-paper)] transition disabled:opacity-35"
        :disabled="(!text.trim() && !attachments.length) || session.sending || uploading"
        aria-label="发送"
      >
        ↑
      </button>
    </form>
  </div>
</template>
