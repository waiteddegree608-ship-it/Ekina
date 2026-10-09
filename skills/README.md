# Ekina Research Skills

每个子目录包含可独立复制的 `SKILL.md`。它描述科研方法与交付要求，不携带密钥、模型调用权限或私人论文。

| Skill | 适用任务 |
| --- | --- |
| `research-planning` | 定义研究问题、证据标准、检查点与资源边界 |
| `literature-review` | 来源检索、全文阅读、分类比较和综述修订 |
| `paper-pipeline` | 从文献与复现到实验、写作和产物核验 |
| `paper-writing` | 基于已有证据组织论文论证与结论 |
| `paper-typesetting` | 按目标 venue 排版、编译与视觉检查 |
| `research-audit` | 核对引用、数字、图表和结论的证据强度 |

Ekina 的 `SkillService` 会扫描 `skills/<name>/SKILL.md`；为兼容论文执行器，`paper-*` 在内部映射为 `paper.*` ID。旧式根目录 Markdown 仍可读取，但目录说明 README 不作为技能注入。

其他支持 SKILL.md 的宿主可以复制所需子目录到自己的技能目录。文中 Ekina 工具名只在相应工具可用时使用；其他宿主使用等价操作。安装技能不会自动安装后端或 MCP，也不意味着授权云算力、SSH 或发布。
