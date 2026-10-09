import { defineStore } from "pinia";
import { ref, computed } from "vue";
import type { ChatBubble, ChannelMessage, Contact, ComposerQuote } from "@/types/channel";
import type { ProjectBoard, ProjectTimeline, ProjectView, TaskCardState, TimelineStep } from "@/types/project";
import { fetchContacts } from "@/api/contacts";
import { fetchChannelSince, fetchChannelRecent, fetchPending } from "@/api/channel";
import { sendChat } from "@/api/chat";
import { fetchAutonomy, setAutonomy } from "@/api/home";
import { decidePermission } from "@/api/permissions";
import { ackReminder } from "@/api/reminders";
import { fetchDockProjects, fetchProjectTimeline } from "@/api/projects";
import { startPaperPipeline, type StartPaperBody } from "@/api/papers";
import { createMission } from "@/api/missions";
import { roleOfContact } from "@/lib/roles";
import { maybeStripEmojis } from "@/lib/emoji";
import { previewPlainText } from "@/lib/attachments";
import { usePrefsStore } from "@/stores/prefs";

function latestStepLabel(
  steps: TimelineStep[] | undefined,
  opts?: { note?: string; latestHint?: string; done?: number; total?: number; running?: number },
): string {
  const done = opts?.done ?? 0;
  const total = opts?.total ?? 0;
  const running = opts?.running ?? 0;
  if (opts?.latestHint) {
    if (running > 0) return `推进中 · ${String(opts.latestHint).slice(0, 42)}`;
    return `近况 · ${String(opts.latestHint).slice(0, 42)}`;
  }
  if (!steps?.length) {
    if (total > 0) return `进度 ${done}/${total}`;
    return opts?.note || "正在组织研究任务…";
  }
  const runningStep = [...steps].reverse().find((x) => {
    const s = (x.status || "").toLowerCase();
    return s === "running" || s === "queued";
  });
  if (runningStep) return `推进中 · ${String(runningStep.title || runningStep.id).slice(0, 42)}`;
  const doneStep = [...steps].reverse().find((x) => {
    const s = (x.status || "").toLowerCase();
    return s === "succeeded" || s === "done";
  });
  if (doneStep) return `刚完成 · ${String(doneStep.title || doneStep.id).slice(0, 42)}`;
  if (total > 0) return `进度 ${done}/${total}`;
  return "等待下一步";
}

function mentionToken(title: string) {
  const t = title.trim() || "任务";
  if (/[\s@「」"]/.test(t)) return `@「${t}」`;
  return `@${t}`;
}

const FALLBACK_CONTACTS: Contact[] = [
  {
    id: "ekina",
    displayName: "Ekina",
    threadId: "daily",
    signature: "生活",
    avatarUrl: "/shell/assets/ekina-mark.svg",
    toolProfile: "life",
  },
  {
    id: "agent",
    displayName: "代理人",
    threadId: "contact_agent",
    signature: "请吩咐",
    avatarUrl: "/shell/assets/ekina-mark.svg",
    toolProfile: "executor",
  },
];

function cardKind(typ?: string) {
  if (typ === "reminder") return "提醒";
  if (typ === "permission_request") return "权限";
  if (typ === "plan") return "方案";
  return typ || "系统";
}

export const useSessionStore = defineStore("session", () => {
  const contacts = ref<Contact[]>([]);
  const activeContactId = ref("ekina");
  const activeThreadId = ref("daily");
  const bubbles = ref<ChatBubble[]>([]);
  const pending = ref<ChannelMessage[]>([]);
  /** Locally dismissed pending ids — survives refresh until server catches up. */
  const dismissedPending = new Set<string>();
  const autonomyOn = ref(false);
  const online = ref(false);
  const statusText = ref("连线中…");

  function applyPending(list: ChannelMessage[]) {
    pending.value = (Array.isArray(list) ? list : []).filter((p) => !dismissedPending.has(p.id));
  }
  const sending = ref(false);
  const busyText = ref("");
  const unread = ref<Record<string, number>>({});
  const boardOpen = ref(false);
  const boardTab = ref<"all" | "brain" | "hand" | "room" | "ext">("all");
  const inboxOpen = ref(false);
  const showCrewMap = ref(true);
  const projects = ref<ProjectView[]>([]);
  const taskCards = ref<TaskCardState[]>([]);
  const focusedTaskId = ref<string | null>(null);
  const composerInsert = ref("");
  const composerQuote = ref<ComposerQuote | null>(null);
  const newMissionOpen = ref(false);
  /** @deprecated kept for CapabilityBoard-era callers; dock replaces switchProject */
  const activeProjectId = ref<string | null>(null);
  const timeline = ref<ProjectTimeline | null>(null);
  const focusedBoard = ref<ProjectBoard | null>(null);
  const projectSummaries = ref<Record<string, { done: number; total: number; running: number }>>({});

  let channelAfterTs = "";
  const seenMsgIds = new Set<string>();
  /** Bumped on contact switch so in-flight polls/sends cannot paint into the wrong chat. */
  let sessionEpoch = 0;
  let projectPollGeneration = 0;
  /** Per-contact watermark: only ts newer than this can raise unread (never compare preview text). */
  const notifiedTsByContact: Record<string, string> = {};
  let pollTimer: number | undefined;
  let statusTimer: number | undefined;
  let timelineTimer: number | undefined;

  function messageThreadOf(m: ChannelMessage): string {
    const pl = m.payload || {};
    const raw = m.threadId || pl.threadId || pl.projectId || "";
    return String(raw || "").trim();
  }

  function threadBelongsHere(tid: string, activeThread: string, activeContact: string): boolean {
    if (!tid || tid === "null") {
      // Legacy rows without thread → only show in 伊姬娜/daily
      return activeThread === "daily" || activeContact === "ekina";
    }
    if (tid === activeThread) return true;
    if (activeThread === "contact_agent" && (tid === "agent" || tid.startsWith("contact_agent"))) return true;
    if (activeThread === "daily" && (tid === "ekina" || tid === "daily")) return true;
    return false;
  }

  function roleIdFromMessage(m: ChannelMessage): string {
    const pl = m.payload || {};
    const cid = String(pl.contactId || "").toLowerCase();
    if (cid === "agent" || cid === "ekina") return cid;
    const tid = messageThreadOf(m);
    if (tid === "contact_agent" || tid.startsWith("contact_agent") || tid === "agent") return "agent";
    return "ekina";
  }

  const activeContact = computed(() => {
    return (
      contacts.value.find((c) => c.id === activeContactId.value) || {
        id: activeContactId.value,
        displayName: roleOfContact(activeContactId.value).displayName,
        threadId: activeThreadId.value,
        toolProfile: activeContactId.value === "agent" ? "executor" : "life",
      }
    );
  });

  const isLife = computed(() => {
    const c = activeContact.value;
    return (c.toolProfile || c.personaProfile || "") === "life" || c.id === "ekina";
  });

  const workProjects = computed(() =>
    projects.value.filter(
      (p) =>
        p.sourceType === "portfolio" ||
        p.sourceType === "paper" ||
        (p.sourceType === "mission" && !(p.meta && p.meta.internal === true)),
    ),
  );

  const activeProject = computed(
    () => workProjects.value.find((p) => p.id === focusedTaskId.value) || null,
  );

  function pushBubble(b: ChatBubble) {
    bubbles.value.push(b);
  }

  function mapChannelMessage(m: ChannelMessage, history = false) {
    const typ = m.type || "";
    const sender = m.sender || "";
    const pl = m.payload || {};
    // Hard isolation: never paint 伊姬娜/daily into 代理人 or vice versa
    if (!threadBelongsHere(messageThreadOf(m), activeThreadId.value, activeContactId.value)) {
      return;
    }
    // silent = 不弹托盘；聊天历史仍要显示。仅过滤非 chat 的静默内部事件。
    if (pl.silent === true && typ !== "chat") return;
    // Internal digests stay off-chat; owner-facing reports/notices must show
    if (pl.kind === "project_digest") return;
    // Live poll: HTTP 回合已在本地展示，跳过 echo 镜像；历史加载必须保留
    if (!history && (pl.echo === true || pl.source === "chat_api")) return;

    const roleId = roleIdFromMessage(m);

    if (typ === "chat" && sender === "user") {
      pushBubble({ id: m.id, side: "me", text: m.text || "", ts: m.ts, kind: "chat", raw: m });
    } else if (typ === "chat" && (sender === "agent" || sender === "system")) {
      const prefix = pl.surprise ? "🎁 " : "";
      const kind =
        pl.kind === "notice" || pl.ephemeral === true
          ? "notice"
          : pl.kind === "report" || pl.report === true
            ? "report"
            : "chat";
      {
        const prefs = usePrefsStore();
        const body = maybeStripEmojis(prefix + (m.text || ""), prefs.noEmoji);
        pushBubble({
          id: m.id,
          side: "her",
          text: body,
          ts: m.ts,
          roleId,
          kind,
          quotable: pl.quotable !== false,
          raw: m,
        });
      }
    } else if (typ === "reminder" || typ === "permission_request" || typ === "plan") {
      // Reminders/permissions belong to 伊姬娜 life inbox — only show in her thread
      if (activeContactId.value === "agent" && typ === "reminder") return;
      pushBubble({
        id: m.id,
        side: "sys",
        text: `【${cardKind(typ)}】${m.text || ""}`,
        ts: m.ts,
        kind: typ === "permission_request" ? "permission" : typ === "plan" ? "plan" : "reminder",
        raw: m,
      });
    } else if (typ === "job_status") {
      // SSH 开始/结束等细碎状态：只进内部账本，永不进老板聊天
      return;
    } else if (typ === "system") {
      if (activeContactId.value === "agent") return;
      const text = m.text || "";
      if (/^远程任务(开始|结束)/.test(text)) return;
      pushBubble({ id: m.id, side: "sys", text, ts: m.ts, kind: "system", raw: m });
    }
  }

  async function loadContacts() {
    try {
      const list = await fetchContacts();
      contacts.value = list.length ? list : FALLBACK_CONTACTS;
      // Only seed missing watermarks — never advance existing ones here (that would swallow real unread)
      for (const c of contacts.value) {
        if (c.id && c.lastTs && !notifiedTsByContact[c.id]) {
          notifiedTsByContact[c.id] = c.lastTs;
        }
      }
      online.value = true;
    } catch {
      contacts.value = FALLBACK_CONTACTS;
      throw new Error("无法加载联系人");
    }
  }

  async function loadHistory() {
    const epoch = sessionEpoch;
    const thread = activeThreadId.value;
    const contact = activeContactId.value;
    bubbles.value = [];
    seenMsgIds.clear();
    channelAfterTs = "";
    try {
      const list = await fetchChannelSince({ threadId: thread, limit: 50 });
      if (epoch !== sessionEpoch || activeThreadId.value !== thread) return;
      if (!list.length) {
        pushBubble({
          id: "sys-empty",
          side: "sys",
          text:
            contact === "agent"
              ? "还没有和代理人说过话。直接下命令即可。"
              : "描述你的研究问题，或在右侧新建课题。",
          kind: "system",
        });
        return;
      }
      for (const m of list) {
        if (epoch !== sessionEpoch || activeThreadId.value !== thread) return;
        if (!m?.id) continue;
        seenMsgIds.add(m.id);
        if (m.ts && (!channelAfterTs || m.ts > channelAfterTs)) channelAfterTs = m.ts;
        mapChannelMessage(m, true);
      }
      if (epoch !== sessionEpoch) return;
      if (!bubbles.value.length) {
        pushBubble({
          id: "sys-empty-filtered",
          side: "sys",
          text:
            contact === "agent"
              ? "历史已加载但暂无可显示气泡。再发一条即可继续。"
              : "历史已加载。可以继续聊。",
          kind: "system",
        });
      }
    } catch {
      if (epoch !== sessionEpoch) return;
      pushBubble({
        id: "sys-hist-err",
        side: "sys",
        text: "历史暂时读不到，但不妨碍现在聊。",
        kind: "system",
      });
    }
  }

  async function refreshTaskCards() {
    const generation = ++projectPollGeneration;
    const contact = activeContactId.value;
    const fetchedTimelines = new Map<string, ProjectTimeline>();
    try {
      const list = await fetchDockProjects(contact);
      const candidates = list.slice(0, 16);
      const cards: TaskCardState[] = await Promise.all(
        candidates.map(async (p) => {
          try {
            const tl = await fetchProjectTimeline(p.id);
            fetchedTimelines.set(p.id, tl);
            const s = tl.summary || {};
            const done = Number(s.done || 0);
            const total = Number(s.total || 0);
            const running = Number(s.running || 0);
            const blocked = Number(s.blocked || 0);
            projectSummaries.value[p.id] = { done, total, running };
            const hint =
              typeof (tl as { latestHint?: string }).latestHint === "string"
                ? (tl as { latestHint?: string }).latestHint
                : undefined;
            const board = tl.board;
            const boardNow = typeof board?.now === "string" ? board.now : undefined;
            const boardNext = Array.isArray(board?.next) ? board.next.map(String) : [];
            return {
              id: p.id,
              title: p.title || p.id,
              sourceType: p.sourceType,
              done,
              total,
              running,
              blocked,
              latestLabel: latestStepLabel(tl.steps, {
                note: tl.note,
                latestHint: boardNow || hint,
                done,
                total,
                running,
              }),
              missionStatus: tl.missionStatus,
              updatedAt: p.updatedAt,
              now: boardNow,
              next: boardNext,
              boardStatus: typeof board?.status === "string" ? board.status : undefined,
              board,
            } satisfies TaskCardState;
          } catch {
            return {
              id: p.id,
              title: p.title || p.id,
              sourceType: p.sourceType,
              done: 0,
              total: 0,
              running: 0,
              blocked: 0,
              latestLabel: "进度暂读不到",
              updatedAt: p.updatedAt,
            } satisfies TaskCardState;
          }
        }),
      );
      if (generation !== projectPollGeneration || contact !== activeContactId.value) return;
      projects.value = list;
      // Prefer running / incomplete first, then recent
      cards.sort((a, b) => {
        const score = (c: TaskCardState) =>
          (c.running > 0 ? 100 : 0) + (c.total > c.done ? 10 : 0);
        return score(b) - score(a);
      });
      taskCards.value = cards;
      if (!focusedTaskId.value && cards.length) {
        focusedTaskId.value = cards[0].id;
        activeProjectId.value = cards[0].id;
      }
      if (focusedTaskId.value) {
        const focused = cards.find((c) => c.id === focusedTaskId.value) || cards[0];
        focusedBoard.value = focused?.board || null;
        if (focused) {
          focusedTaskId.value = focused.id;
          activeProjectId.value = focused.id;
          timeline.value = fetchedTimelines.get(focused.id) || {
            project: list.find((p) => p.id === focused.id),
            goal: focused.board?.goal || focused.title,
            latestHint: focused.now || focused.latestLabel,
            steps: [],
            summary: { done: focused.done, total: focused.total, running: focused.running, blocked: focused.blocked },
            board: focused.board,
          };
        } else {
          focusedTaskId.value = null;
          activeProjectId.value = null;
          timeline.value = null;
        }
      }
    } catch {
      /* keep previous cards */
    }
  }

  async function loadProjects() {
    await refreshTaskCards();
  }

  async function refreshTimeline() {
    await refreshTaskCards();
  }

  async function switchContact(id: string) {
    sessionEpoch += 1;
    busyText.value = "";
    sending.value = false;
    activeContactId.value = id;
    focusedTaskId.value = null;
    activeProjectId.value = null;
    focusedBoard.value = null;
    timeline.value = null;
    const c = contacts.value.find((x) => x.id === id);
    activeThreadId.value = c?.threadId || (id === "ekina" ? "daily" : "contact_" + id);
    unread.value[id] = 0;
    // Drop any painted bubbles immediately so a late poll cannot append onto the old transcript
    bubbles.value = [];
    seenMsgIds.clear();
    channelAfterTs = "";
    await loadHistory();
    if (channelAfterTs) {
      notifiedTsByContact[id] = channelAfterTs;
    }
    // Projects are per-contact — never leave the previous contact's dock painted
    taskCards.value = [];
    projects.value = [];
    await refreshTaskCards().catch(() => undefined);
  }

  function focusTask(id: string) {
    const card = taskCards.value.find((c) => c.id === id);
    if (!card) return;
    focusedTaskId.value = id;
    activeProjectId.value = id;
    focusedBoard.value = card.board || null;
    timeline.value = null;
    void refreshTaskCards();
  }

  /** Apply confirmed writes without allowing an older poll to overwrite them. */
  function applyProjectBoard(id: string, updated: ProjectBoard) {
    projectPollGeneration += 1;
    const card = taskCards.value.find(c => c.id === id);
    if (card) { card.board = updated; card.now = updated.now; card.next = updated.next; card.boardStatus = updated.status; }
    if (focusedTaskId.value === id) {
      focusedBoard.value = updated;
      if (timeline.value) timeline.value = { ...timeline.value, board: updated };
    }
  }

  /** Stay in this dialogue, insert @任务 into composer so 姬娜续作该课题. */
  function mentionTask(id: string) {
    const card = taskCards.value.find((c) => c.id === id);
    if (!card) return;
    focusTask(id);
    composerInsert.value = mentionToken(card.title);
  }

  /** @deprecated — use mentionTask; kept so old rail clicks don't switch threads */
  async function switchProject(id: string) {
    mentionTask(id);
  }

  async function startMission(goal: string) {
    const g = goal.trim();
    if (!g) return;
    // Always keep the organizer dialogue (ekina/daily)
    if (activeContactId.value !== "ekina") {
      await switchContact("ekina");
    }
    const m = await createMission(g);
    await refreshTaskCards();
    const match = projects.value.find((p) => p.sourceId === m.id);
    if (match) {
      focusedTaskId.value = match.id;
      activeProjectId.value = match.id;
    }
    const oneShot =
      /惊喜|短笺|资讯|日报|握手|令牌|磨合|贺卡|便条|小纸条/.test(g) &&
      !/hypelora|lora|训练|实验|复现/i.test(g);
    pushBubble({
      id: "sys-msn-" + Date.now(),
      side: "sys",
      text: oneShot
        ? `已记下「${g.slice(0, 60)}」——一次性产出会以聊天通知出现，可右键引用续聊。`
        : `已派活「${g.slice(0, 60)}」——进度在右侧项目卡更新，点卡片可 @续作。`,
      kind: "system",
    });
    newMissionOpen.value = false;
  }

  /** Token→论文：启动 PaperOrchestrator 全阶段流水线。 */
  async function startPaper(body: StartPaperBody) {
    const hint = (body.titleHint || body.domain || "").trim();
    if (!hint) return;
    if (activeContactId.value !== "ekina") {
      await switchContact("ekina");
    }
    const p = await startPaperPipeline({
      ...body,
      titleHint: hint,
      domain: body.domain || hint,
      mode: body.mode || "research",
      autoRun: body.autoRun !== false,
    });
    await refreshTaskCards();
    const match =
      projects.value.find((x) => x.id === p.id || x.sourceId === p.id) ||
      projects.value.find((x) => x.sourceType === "paper" && (x.title || "").includes(hint.slice(0, 12)));
    if (match) {
      focusedTaskId.value = match.id;
      activeProjectId.value = match.id;
    }
    pushBubble({
      id: "sys-paper-" + Date.now(),
      side: "sys",
      text: `已开论文管线「${(p.titleWorking || hint).slice(0, 48)}」· mode=${p.mode || body.mode || "research"} —— 右侧项目卡看 SCOUT→GATE 进度。`,
      kind: "system",
    });
    newMissionOpen.value = false;
  }

  function consumeComposerInsert(): string {
    const v = composerInsert.value;
    composerInsert.value = "";
    return v;
  }

  /** WeChat-style quote: pin a bubble into the composer to continue that thread of talk. */
  function quoteBubble(b: ChatBubble) {
    const text = (b.text || "").replace(/\s+/g, " ").trim();
    if (!text) return;
    composerQuote.value = {
      id: b.id,
      text: text.slice(0, 240),
      ts: b.ts,
    };
  }

  function clearComposerQuote() {
    composerQuote.value = null;
  }

  function consumeComposerQuote(): ComposerQuote | null {
    const q = composerQuote.value;
    composerQuote.value = null;
    return q;
  }

  async function pollChannel() {
    const epoch = sessionEpoch;
    const thread = activeThreadId.value;
    const contact = activeContactId.value;
    const after = channelAfterTs;
    try {
      const list = await fetchChannelSince({
        threadId: thread,
        afterTs: after,
        limit: 40,
      });
      // Contact switched while request was in flight — drop the whole batch
      if (epoch !== sessionEpoch || activeThreadId.value !== thread) return;

      for (const m of list) {
        if (epoch !== sessionEpoch || activeThreadId.value !== thread) return;
        if (!m?.id || seenMsgIds.has(m.id)) {
          if (m?.ts && (!channelAfterTs || m.ts > channelAfterTs)) channelAfterTs = m.ts;
          continue;
        }
        if (!threadBelongsHere(messageThreadOf(m), thread, contact)) continue;
        seenMsgIds.add(m.id);
        if (m.ts && (!channelAfterTs || m.ts > channelAfterTs)) channelAfterTs = m.ts;
        const pl = m.payload || {};
        if (pl.echo === true || pl.source === "chat_api") continue;
        mapChannelMessage(m);
        if (m.type === "chat" && m.sender === "agent") {
          busyText.value = "";
        }
        const pl2 = m.payload || {};
        if (
          contact === "ekina" &&
          (pl2.report === true ||
            pl2.kind === "report" ||
            pl2.kind === "notice" ||
            pl2.kind === "project_progress")
        ) {
          void refreshTaskCards();
        }
      }

      if (epoch !== sessionEpoch) return;

      for (const c of contacts.value) {
        if (c.id === activeContactId.value) continue;
        const expectedThread = c.threadId || (c.id === "ekina" ? "daily" : "contact_" + c.id);
        const rows = await fetchChannelRecent(expectedThread, 12).catch(() => [] as ChannelMessage[]);
        if (epoch !== sessionEpoch) return;
        if (!rows.length) continue;
        // API returns newest-first — only this contact's own thread
        const last = rows.find((m) => {
          if (m?.type !== "chat" || !m.text) return false;
          if (!threadBelongsHere(messageThreadOf(m), expectedThread, c.id)) return false;
          if (m.sender !== "agent" && m.sender !== "user") return false;
          const t = m.text;
          if (/tick\s*#?\d+/i.test(t)) return false;
          if (/姬娜刚才做了什么|我刚才做了什么|自主巡查/.test(t)) return false;
          if ((m.payload || {}).kind === "project_digest") return false;
          if ((m.payload || {}).silent === true) return false;
          // Need a usable preview after stripping MD / digests
          if (!previewPlainText(t)) return false;
          return true;
        });
        if (!last?.ts) continue;
        c.lastPreview = previewPlainText(last.text || "") || roleOfContact(c.id).signature;
        c.lastTs = last.ts;
        const watermark = notifiedTsByContact[c.id] || "";
        if (!watermark) {
          // First sighting: remember, do not badge
          notifiedTsByContact[c.id] = last.ts;
          continue;
        }
        // Only AI-side chat on THAT contact's thread can raise unread
        if (last.sender === "agent" && last.ts > watermark) {
          notifiedTsByContact[c.id] = last.ts;
          unread.value[c.id] = (unread.value[c.id] || 0) + 1;
        }
      }
      online.value = true;
      statusText.value = "在线";
    } catch {
      /* quiet poll */
    }
  }

  async function refreshStatus() {
    try {
      const [autonomy, pend] = await Promise.all([
        fetchAutonomy().catch(() => autonomyOn.value),
        fetchPending().catch(() => [] as ChannelMessage[]),
      ]);
      autonomyOn.value = Boolean(autonomy);
      applyPending(pend);
      online.value = true;
      statusText.value = "在线";
    } catch {
      online.value = false;
      statusText.value = "连不上后端";
    }
  }

  async function send(message: string) {
    const text = message.trim();
    if (!text || sending.value) return;
    const epoch = sessionEpoch;
    const contactAtSend = activeContactId.value;
    const threadAtSend = activeThreadId.value;
    const projectAtSend = focusedTaskId.value || undefined;
    sending.value = true;
    busyText.value = `${activeContact.value.displayName || "Agent"} 处理中…`;
    const quoted = consumeComposerQuote();
    const display = quoted ? `引用：${quoted.text}\n${text}` : text;
    const outbound = quoted
      ? `【引用上文】${quoted.text}\n\n${text}`
      : text;
    pushBubble({
      id: "local-" + Date.now(),
      side: "me",
      text: display,
      ts: new Date().toISOString(),
      kind: "chat",
    });
    try {
      const answer = await sendChat({
        message: outbound,
        contactId: contactAtSend,
        threadId: threadAtSend,
        projectId: projectAtSend,
      });
      // If user switched contacts while waiting, do not dump the reply into the wrong chat
      if (epoch !== sessionEpoch || activeContactId.value !== contactAtSend) {
        unread.value[contactAtSend] = (unread.value[contactAtSend] || 0) + 1;
        const c = contacts.value.find((x) => x.id === contactAtSend);
        if (c) c.lastPreview = previewPlainText(answer) || roleOfContact(contactAtSend).signature;
        return;
      }
      {
        const prefs = usePrefsStore();
        pushBubble({
          id: "ans-" + Date.now(),
          side: "her",
          text: maybeStripEmojis(answer, prefs.noEmoji),
          ts: new Date().toISOString(),
          roleId: contactAtSend,
          kind: "chat",
        });
      }
      await pollChannel();
      await refreshStatus();
      await loadContacts().catch(() => undefined);
      if (contactAtSend === "ekina") {
        await refreshTaskCards().catch(() => undefined);
      }
    } catch (e) {
      if (epoch === sessionEpoch && activeContactId.value === contactAtSend) {
        pushBubble({
          id: "err-" + Date.now(),
          side: "sys",
          text: "发送失败：" + (e instanceof Error ? e.message : String(e)),
          kind: "system",
        });
      }
    } finally {
      if (epoch === sessionEpoch) {
        sending.value = false;
        busyText.value = "";
      }
    }
  }

  async function toggleAutonomy() {
    if (!isLife.value) {
      pushBubble({
        id: "sys-auto",
        side: "sys",
        text: "自主生活属于生活型 Agent；请先切到伊姬娜会话。",
        kind: "system",
      });
      return;
    }
    try {
      const next = !autonomyOn.value;
      await setAutonomy(next);
      await refreshStatus();
      pushBubble({
        id: "sys-auto2",
        side: "sys",
        text: next ? "已开启姬娜自主。" : "已关闭姬娜自主。",
        kind: "system",
      });
    } catch (e) {
      pushBubble({
        id: "sys-auto-err",
        side: "sys",
        text: "切换自主失败：" + (e instanceof Error ? e.message : String(e)),
        kind: "system",
      });
    }
  }

  async function resolveReminder(id: string, action: string) {
    try {
      dismissedPending.add(id);
      pending.value = pending.value.filter((p) => p.id !== id);
      await ackReminder(id, action);
      const label =
        action === "ack"
          ? "已确认提醒。"
          : action.startsWith("snooze")
            ? `已延后提醒（${action.replace("snooze_", "").replace("m", " 分钟").replace("1h", "1 小时")}）。`
            : "已处理提醒。";
      bubbles.value = bubbles.value.map((b) => {
        if (b.raw?.id === id && b.kind === "reminder") {
          return {
            ...b,
            kind: "system" as const,
            text: label,
            raw: b.raw ? { ...b.raw, status: action.startsWith("snooze") ? "snoozed" : "acked" } : b.raw,
          };
        }
        return b;
      });
      await refreshStatus();
    } catch (e) {
      dismissedPending.delete(id);
      pushBubble({
        id: "sys-rem-err-" + Date.now(),
        side: "sys",
        text: "处理提醒失败：" + (e instanceof Error ? e.message : String(e)),
        kind: "system",
      });
      await refreshStatus().catch(() => undefined);
    }
  }

  async function resolvePending(id: string, approve: boolean, comment?: string) {
    try {
      const res = await decidePermission({
        messageId: id,
        decision: approve ? "approved" : "denied",
        comment,
      });
      if (res && res.ok === false) {
        pushBubble({
          id: "sys-pend-err-" + Date.now(),
          side: "sys",
          text: "处理失败：" + String(res.error || "未知错误"),
          kind: "system",
        });
        await refreshStatus();
        return;
      }
      dismissedPending.add(id);
      pending.value = pending.value.filter((p) => p.id !== id);
      // Drop interactive permission cards from the transcript once decided
      bubbles.value = bubbles.value.map((b) => {
        if (b.raw?.id === id && b.kind === "permission") {
          return {
            ...b,
            kind: "system" as const,
            text: approve ? "已同意该请求。" : "已拒绝该请求。",
            raw: b.raw ? { ...b.raw, status: approve ? "done" : "cancelled" } : b.raw,
          };
        }
        return b;
      });
      pushBubble({
        id: "sys-pend-" + Date.now(),
        side: "sys",
        text: approve ? "已同意该请求。" : "已拒绝该请求。",
        kind: "system",
      });
      await refreshStatus();
    } catch (e) {
      pushBubble({
        id: "sys-pend-err",
        side: "sys",
        text: "处理失败：" + (e instanceof Error ? e.message : String(e)),
        kind: "system",
      });
    }
  }

  async function boot() {
    try {
      await loadContacts();
      await loadProjects().catch(() => undefined);
      const start = contacts.value[0]?.id || "ekina";
      await switchContact(start);
      await refreshStatus();
    } catch (e) {
      online.value = false;
      statusText.value = "连不上后端";
      pushBubble({
        id: "boot-err",
        side: "sys",
        text: "后端未就绪：" + (e instanceof Error ? e.message : String(e)),
        kind: "system",
      });
    }
    window.clearInterval(pollTimer);
    window.clearInterval(statusTimer);
    window.clearInterval(timelineTimer);
    pollTimer = window.setInterval(() => void pollChannel(), 2500);
    statusTimer = window.setInterval(() => {
      void refreshStatus();
    }, 8000);
    timelineTimer = window.setInterval(() => void refreshTaskCards(), 4000);
  }

  function dispose() {
    window.clearInterval(pollTimer);
    window.clearInterval(statusTimer);
    window.clearInterval(timelineTimer);
  }

  return {
    contacts,
    activeContactId,
    activeThreadId,
    activeContact,
    isLife,
    bubbles,
    pending,
    autonomyOn,
    online,
    statusText,
    sending,
    busyText,
    unread,
    boardOpen,
    boardTab,
    inboxOpen,
    showCrewMap,
    projects,
    workProjects,
    taskCards,
    focusedTaskId,
    composerInsert,
    composerQuote,
    activeProjectId,
    activeProject,
    timeline,
    focusedBoard,
    projectSummaries,
    newMissionOpen,
    boot,
    dispose,
    switchContact,
    switchProject,
    focusTask,
    applyProjectBoard,
    mentionTask,
    consumeComposerInsert,
    quoteBubble,
    clearComposerQuote,
    consumeComposerQuote,
    startMission,
    startPaper,
    loadProjects,
    refreshTaskCards,
    refreshTimeline,
    send,
    toggleAutonomy,
    resolvePending,
    resolveReminder,
    refreshStatus,
    pollChannel,
  };
});
