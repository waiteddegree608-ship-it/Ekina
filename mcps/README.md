# Ekina MCPs

## 对外：Research MCP

`research/` 是可运行的 stdio MCP 服务，将现有 HTTP 查询接口转换为 5 个只读工具：

| 工具 | 对应 API |
| --- | --- |
| `ekina_list_projects` | `GET /api/projects` |
| `ekina_list_reviews` | `GET /api/reviews` |
| `ekina_list_papers` | `GET /api/papers` |
| `ekina_get_paper` | `GET /api/papers/{id}` |
| `ekina_list_capabilities` | `GET /api/capability-packs` |

先启动 Ekina，再执行 `npm --prefix mcps/research ci`。使用支持 stdio MCP 的客户端，合并下面配置，并将路径改成本机绝对路径：

```json
{
  "mcpServers": {
    "ekina-research": {
      "command": "node",
      "args": ["/absolute/path/Ekina/mcps/research/src/server.mjs"],
      "env": { "EKINA_BASE_URL": "http://127.0.0.1:8081", "EKINA_USER_ID": "user-1" }
    }
  }
}
```

Windows 路径可写为 `E:/projects/Ekina/mcps/research/src/server.mjs`。不同客户端的配置文件位置由客户端决定。该服务不需要模型密钥，不读取本机文件，也不提交研究任务；它能读取后端返回的项目数据。仅连接自己的可信后端，`EKINA_USER_ID` 是数据筛选参数，不是身份认证。测试：`npm --prefix mcps/research test`。

## 对内：连接外部 MCP

后端现有实现位于 `ekina-infrastructure/.../agent/mcp/`，支持配置 stdio 和 SSE 服务。默认 `ekina.mcp.servers: []`，没有随应用启动的外部进程。安装相应 MCP 后再配置 `name/type/command/args` 或 `url`。外部工具权限与授权范围由其服务实现决定。

历史工作区的文件系统、抓取、SQLite 与浏览器 MCP 安装依赖未打包。按需要选择和固定版本；不再附带指向个人资料库的目录授权。
