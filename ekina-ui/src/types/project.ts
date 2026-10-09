export type ProjectView = {
  id: string;
  title?: string;
  sourceType?: string;
  sourceId?: string;
  threadId?: string;
  userId?: string;
  /** Owning contact (ekina | agent | …) — dock is filtered by this */
  contactId?: string;
  createdAt?: string;
  updatedAt?: string;
  linkedTaskIds?: string[];
  meta?: Record<string, unknown>;
};

export type TimelineStep = {
  id: string;
  title?: string;
  assignee?: string;
  status?: string;
  taskId?: string;
  dependsOn?: string[];
  updatedAt?: string | null;
  error?: string;
};

export type BoardFinding = {
  kind?: string;
  text?: string;
  at?: string;
};

export type BoardDoneItem = {
  title?: string;
  result?: string;
  at?: string;
};

export type BoardEvent = {
  at?: string;
  title?: string;
  detail?: string;
  status?: string;
};

export type ProjectBoard = {
  projectId?: string;
  goal?: string;
  status?: string;
  now?: string;
  next?: string[];
  done?: BoardDoneItem[];
  findings?: BoardFinding[];
  timeline?: BoardEvent[];
  questions?: string[];
  updatedAt?: string;
  project?: ProjectView;
  error?: string;
  nextDeliverable?: string;
  roadmap?: ResearchStep[];
  decisions?: ResearchDecision[];
  collaboration?: CollaborationSettings;
  guidance?: ProjectGuidance[];
  research?: ResearchLedger;
};

export type ResearchAssessment = "unreviewed" | "reviewed" | "contested" | "needs_review" | "withdrawn" | "planned";
type ResearchRecordBase = { id: string; at?: string; assessment?: ResearchAssessment; withdrawalReason?: string };
export type ResearchAttempt = ResearchRecordBase & {
  type: "attempt"; question: string; method: string; result: string;
  outcome: "supported" | "refuted" | "inconclusive" | "failed"; artifacts: string[]; limitations?: string; stepId?: string;
};
export type ResearchEvidence = ResearchRecordBase & {
  type: "evidence"; title: string; source: string; summary: string; conditions: string; producer: string;
  kind: "literature" | "experiment" | "analysis"; attemptId?: string; reviewIds?: string[];
};
export type ResearchReview = ResearchRecordBase & {
  type: "review"; evidenceId: string; reviewer: string; summary: string; artifact: string;
  method: "source_check" | "execution_check" | "independent_reproduction" | "model_review";
  verdict: "supports" | "concerns" | "rejects";
};
export type ResearchConclusion = ResearchRecordBase & {
  type: "conclusion"; title: string; statement: string; conditions: string; evidenceIds: string[];
  dependsOn: string[]; limitations?: string; stepId?: string;
};
export type ResearchWithdrawal = ResearchRecordBase & { type: "withdrawal"; targetId: string; reason: string };
export type ResearchCheckpoint = ResearchRecordBase & {
  type: "checkpoint"; question: string; nextAction: string; continueWhen: string; stopWhen: string;
  resources: string; basisIds: string[]; costMin?: number | null; costMax?: number | null; currency?: "CNY" | "USD"; estimateBasis?: string;
};
export type ResearchRecord = ResearchAttempt | ResearchEvidence | ResearchReview | ResearchConclusion | ResearchWithdrawal | ResearchCheckpoint;
export type ResearchLedger = { revision: number; records: ResearchRecord[]; checkpoint?: ResearchCheckpoint | null; attentionCount: number };

export type CollaborationSettings = {
  mode: "guided" | "checkpoints" | "delegated";
  budget: number | null;
  currency: "CNY" | "USD";
  priorities: string;
};

export type ResearchStep = {
  id: string;
  title: string;
  status: "planned" | "running" | "done" | "blocked" | "conditional";
  purpose?: string;
  deliverable?: string;
  acceptance?: string;
  condition?: string;
  resources?: string;
  dependsOn?: string[];
  costMin?: number | null;
  costMax?: number | null;
  currency?: "CNY" | "USD";
  estimateBasis?: string;
};

export type ResearchDecision = {
  id: string;
  kind: "consultation" | "authorization";
  title: string;
  recommendation?: string;
  impact?: string;
  permissionId?: string;
};

export type ProjectGuidance = {
  id: string;
  target: string;
  text: string;
  at: string;
  status: "pending" | "responded";
  reply?: string;
  repliedAt?: string;
};

export type ProjectTimeline = {
  project?: ProjectView;
  missionId?: string;
  missionStatus?: string;
  goal?: string;
  steps?: TimelineStep[];
  summary?: {
    done?: number;
    total?: number;
    running?: number;
    blocked?: number;
  };
  note?: string;
  latestHint?: string;
  internal?: boolean;
  error?: string;
  board?: ProjectBoard;
};

/** Right-dock task card: progress updates in place, never as chat spam. */
export type TaskCardState = {
  id: string;
  title: string;
  sourceType?: string;
  done: number;
  total: number;
  running: number;
  blocked: number;
  latestLabel: string;
  missionStatus?: string;
  updatedAt?: string;
  now?: string;
  next?: string[];
  boardStatus?: string;
  board?: ProjectBoard;
};
