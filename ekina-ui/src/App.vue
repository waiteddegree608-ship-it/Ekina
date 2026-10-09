<script setup lang="ts">
import { onMounted, onUnmounted } from "vue";
import { useSessionStore } from "@/stores/session";
import { useConnectionStore } from "@/stores/connection";
import { usePrefsStore } from "@/stores/prefs";
import SessionRail from "@/components/shell/SessionRail.vue";
import ChatStage from "@/components/chat/ChatStage.vue";
import CapabilityBoard from "@/components/board/CapabilityBoard.vue";

const session = useSessionStore();
const connection = useConnectionStore();
const prefs = usePrefsStore();

let liveTimer: number | undefined;
let extTimer: number | undefined;

onMounted(() => {
  prefs.boot();
  void session.boot();
  void connection.refreshAll();
  liveTimer = window.setInterval(() => void connection.refreshLive(), 3000);
  extTimer = window.setInterval(() => void connection.refreshExtensions(), 15000);
  // Allow linking directly to settings.
  try {
    if (new URLSearchParams(window.location.search).get("open") === "settings") {
      prefs.settingsOpen = true;
    }
  } catch {
    /* ignore */
  }
});

onUnmounted(() => {
  session.dispose();
  window.clearInterval(liveTimer);
  window.clearInterval(extTimer);
});
</script>

<template>
  <div class="flex h-full min-h-0">
    <SessionRail />
    <ChatStage />
    <CapabilityBoard v-if="session.boardOpen" />
  </div>
</template>
