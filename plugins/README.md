# Ekina 插件与能力包

当前插件形式是**编译进后端的能力包 + 可分发的技能/接入组件**。本目录的 `plugin.json` 是 Ekina 目录清单，供维护和打包使用，不冒充任意宿主的插件安装协议，也不支持上传 Java 代码后热加载。

| 目录 | 实现 | 可复用部分 |
| --- | --- | --- |
| `research-core` | `CapabilityPackCatalog.RESEARCH`，研究工具白名单 | 研究计划、综述、实验流程与审查 Skills |
| `paper-studio` | `CapabilityPackCatalog.TYPESETTING`，写稿、模板、编译和审查 | 写作与排版 Skills，论文模板 |
| `research-connector` | 独立 stdio MCP 服务 | `mcps/research`，无需安装前端 |

后端可通过 `GET /api/capability-packs` 查看实际能力包。默认科研角色使用 `research`，不再使用历史上代表无限制工具的 `life` 包。历史兼容常量保留在源码中，不作为产品入口。

新增能力时：实现领域/应用/工具接口，在能力包中显式列出工具，写明输入与产物，增加必要的行为测试，更新目录清单和相应文档。目录清单中的路径相对于仓库根目录。

三个复用层次：

1. **只使用科研方法**：复制某个 `skills/*` 子目录。
2. **从外部助手读取 Ekina**：运行 `mcps/research`。
3. **扩展科研执行能力**：修改后端能力包并重新构建。
