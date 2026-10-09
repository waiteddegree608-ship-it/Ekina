package com.ekina.application.multiagent;

/**
 * Three-agent crew:
 * <ul>
 *   <li>{@link #CHIEF} — 伊姬娜总管：生活、日记、提醒、课题灵感、派活、汇报</li>
 *   <li>{@link #CODER} — OpenCode 编程/SSH/训练执行手</li>
 *   <li>{@link #CLAW} — OpenClaw 通道/本机操作/对外触手</li>
 *   <li>{@link #SCRIBE} — 伊姬娜管理的排版工人：学术英文、官网模板、图表美化</li>
 * </ul>
 */
public enum AgentRole {
    CHIEF,
    CODER,
    CLAW,
    SCRIBE
}
