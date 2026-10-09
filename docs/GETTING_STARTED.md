# 安装与配置

所有命令在 Ekina 根目录执行。当前支持本地单用户部署；没有完成面向公网的账号认证和租户隔离。默认仅监听 `127.0.0.1:8081`。

## 环境

| 依赖 | 用途 |
| --- | --- |
| Java 21、Maven 3.9+ | 构建与运行 Spring Boot 后端 |
| Node.js 22.12+、npm | Vue 构建、可选 Research MCP |
| PostgreSQL 16、pgvector | 用户、记忆和向量存储 |
| Python 3.10+ | 启动器、文档处理和检查脚本；建议单独虚拟环境 |
| LaTeX 发行版 | 可选，实际编译论文时需要所用模板、字体与工具 |
| OpenCode CLI | 可选，后台编码和部分长流程任务需要 |

## 配置

复制 `.env.example` 为 `.env`，填写网关的 `OPENCODE_BASE_URL`、`OPENCODE_API_KEY`、`OPENCODE_CHAT_MODEL` 与 `EKINA_RESEARCH_MODEL`。变量沿用既有名称，实际地址和模型由服务商提供；不要照抄旧项目的模型可用性假设。网关必须与后端的 OpenAI-compatible 协议实现匹配，接入后先做一次短请求验证。

`python scripts/run.py` 加载根目录 `.env`；进程环境变量优先。直接运行 `java -jar` 不会自动读取 `.env`。启动器以当前项目根目录配置 sandbox、AgentHome 与笔记路径，不读取旧工作区的私人目录。

数据库可用 `docker compose up -d db` 启动，端口为 `15432`；首次初始化会安装 vector 扩展。默认应用用户名是 `ekina`。修改已有数据库卷的密码不会自动改变数据库账号，需自行维护原数据库或使用新的空卷。

## 构建与启动

```sh
npm --prefix ekina-ui ci
npm --prefix ekina-ui run build
python -m pip install -r scripts/requirements-review-documents.txt
mvn package -DskipTests
python scripts/run.py
```

前端产物进入 `ekina-bootstrap/src/main/resources/static/shell/`，所以先构建前端，再打包 JAR。浏览器访问 `http://127.0.0.1:8081/shell/`。后端 API 位于 `/api/`。

开发前端可运行 `npm --prefix ekina-ui run dev`；Vite 将 API 请求转发到本地 `8081`。后端仍需运行。

## 可选组件

- 执行器：自行安装 CLI，配置 `EKINA_OPENCODE_CMD` 与该 CLI 可用的 `EKINA_OPENCODE_MODEL`，并按执行器要求配置认证。本项目不打包第三方 CLI 二进制。
- 远程实验：配置 SSH 主机、用户、密钥或密码，再按任务权限使用；没有算力时应报告未执行实验。
- Research MCP：见 [MCP 文档](../mcps/README.md)，它只读取后端状态。
- 外部 MCP：默认空列表，需在后端配置中显式加入所安装服务。
- 全文、图像与 PDF：需要相应 Python 包及其系统依赖；缺失时查看具体文档处理错误，不将空文件标记为成功。

## 常见问题

**页面 404**：确认前端已构建，而且后端 JAR 在前端构建后重新打包。

**数据库连接失败**：检查容器健康状态、`15432` 端口、账号密码和 vector 扩展。应用不会启动原项目的便携数据库。

**任务没有运行**：检查模型、执行器和工具依赖。仅能打开 UI 不代表远程研究链路已就绪。

**结果只有脚本没有实验数字**：确认实际实验是否运行；不以模板或 synthetic smoke 输出替代真实评估。

**全文与渲染失败**：检查 Python 命令、依赖、字体和 LaTeX 工具链。维护者的本地测试通过不代表所有平台均已验证。
