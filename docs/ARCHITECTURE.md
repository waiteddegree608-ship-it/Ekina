# 架构与边界

Ekina 是 Java 21 + Spring Boot 3.3 的模块化单体，前端采用 Vue 与 TypeScript。

```text
浏览器 / ekina-ui
        │ HTTP
ekina-bootstrap ── ekina-application ── ekina-domain
        │                   │                 ↑
        └──────── ekina-infrastructure ────────┘
                        │
        PostgreSQL / 模型服务 / 执行器 / 外部 MCP

外部 MCP 客户端 ── mcps/research ── HTTP GET ── 后端
```

`ekina-common` 提供公共类型。`plugins/` 是已实现扩展的分发目录；实际能力白名单在 `CapabilityPackCatalog`，并由 Agent 配置选择。`skills/` 提供方法指令，`SkillService` 负责发现与注入。Skills 本身不是工具权限。

## 科研领域

- `application/project`：项目板、研究计划、证据、审查、结论和检查点。
- `application/review`：综述任务、来源/全文、图文产物、修订与恢复。
- `application/paper`：论文流程、实验、模板和审查。
- `application/multiagent`：执行器任务、Mission 与产物回读。
- `infrastructure/agent`：模型适配、工具执行与 MCP 客户端。

## 数据落点

PostgreSQL 保存数据库实体；`sandbox/` 保存上传文件和执行器工作文件；`AgentHome/` 保存项目状态与研究产物。它们均不属于源码分发。不要把用户数据直接作为示例提交。

## 本轮产品化决定

对外品牌统一为 Ekina，模块统一 `ekina-*`，Java 包保留 `com.ekina`。删除桌宠服务、接口、模型字段和 UI 设置；默认关闭生活循环、屏幕观察、OpenClaw 通道与系统通知。科研角色使用显式工具白名单。

为避免无关的破坏性重写，一些历史兼容服务和 ID 仍留在模块内，例如 `life` profile ID、会话服务名与旧工具实现。它们不属于当前产品宣传面；下一轮可通过独立兼容迁移进一步拆除。默认科研角色不获得生活工具。

当前是本地单用户产品。`userId` 参数和工具白名单不能替代身份认证、租户隔离或安全沙箱。公网托管和插件热加载尚未实现。
