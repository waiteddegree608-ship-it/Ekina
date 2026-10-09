# 第三方来源与待核查项

本清单记录来源边界，不替代依赖许可证文件或法律审查。项目自有源码采用 MIT；不能将第三方依赖统一声明为 MIT。授权尚未核清的期刊模板未随本次源码发布。

| 内容 | 清单来源 | 当前处理 |
| --- | --- | --- |
| Java / Spring / AI / 文档依赖 | 根与各模块 `pom.xml` | 由 Maven 获取，需生成完整依赖及许可证清单 |
| iText PDF 相关组件 | `ekina-infrastructure/pom.xml` | 正式发布前重点确认授权条件与分发方案 |
| Vue / Vite / TypeScript 等 | `ekina-ui/package-lock.json` | 由 npm 获取，不分发 node_modules |
| MCP SDK / Zod | `mcps/research/package-lock.json` | 固定版本，由 npm 获取，不分发 node_modules |
| 期刊与会议模板 | [安装说明](templates/README.md) | 未捆绑；使用者需从官方来源获取并核查许可 |
| OpenCode 等执行器 | 使用者自行安装 | 不捆绑二进制，也不在此保证上游许可证或服务价格 |
| 产品 E 字标 | `ekina-ui/public/assets/ekina-mark.svg` | 本次用 SVG 绘制，无角色或图片素材依赖 |

没有保留旧 Live2D 角色、人物图像和私人研究 PDF。不要将论文图、模板、模型权重或第三方代码仅因本地可访问就当作可公开分发。
