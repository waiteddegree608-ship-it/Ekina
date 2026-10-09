export type RoleDef = {
  id: string;
  displayName: string;
  signature: string;
  color: string;
};

export const ROLES: Record<string, RoleDef> = {
  ekina: {
    id: "ekina",
    displayName: "Ekina",
    signature: "课题 · 证据 · 交付",
    color: "#e8a0a8",
  },
  agent: {
    id: "agent",
    displayName: "代理人",
    signature: "资料整理 · 文件助手",
    color: "#7a9bb8",
  },
  opencode: {
    id: "opencode",
    displayName: "OpenCode",
    signature: "编码帮手",
    color: "#3d5a80",
  },
  openclaw: {
    id: "openclaw",
    displayName: "OpenClaw",
    signature: "通道 / 本机",
    color: "#6b4f3a",
  },
  scribe: {
    id: "scribe",
    displayName: "排版",
    signature: "学术写作 · 模板 · 图表",
    color: "#8b7355",
  },
};

export function roleOfContact(contactId: string): RoleDef {
  return ROLES[contactId] || ROLES.ekina;
}

export function roleLabel(id: string): string {
  return ROLES[id]?.displayName || id;
}

export function roleColor(id: string): string {
  return ROLES[id]?.color || "#8a8780";
}
