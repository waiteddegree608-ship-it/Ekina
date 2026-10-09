# Public source preview

Repository: https://github.com/waiteddegree608-ship-it/Ekina

Project-owned source: MIT. Unreviewed third-party venue templates have been excluded; see `templates/README.md`. This is a source preview, not a packaged desktop release.

# 源码预览与后续开源

本目录是从既有工作区提炼的独立源码树，可复制到新仓库维护。它不是旧工作区的完整镜像；原工作区未被清空、回退或提交。`source-map.json` 记录最初提取的源文件对应关系，后续新增文件不在该历史表中。

## 已排除

- Live2D、桌宠客户端/后端接口、模型与角色图片。
- `.env`、数据库、日志、聊天记录、AgentHome、sandbox、研究产物和本机依赖。
- IM 客户端、便携工具二进制、旧实验论文包和历史文档。
- 三个依赖旧现场或真人格/模型调用的启动测试；科研领域测试保留。

## 生成源码包

```sh
python scripts/validate_source.py
python scripts/package_source.py
```

校验器检查可分发文件、常见敏感内容特征、文档链接、插件入口、Skills 与产品中的桌宠残留。打包脚本只选择允许的源码目录与根文件，跳过运行数据、依赖、构建产物和符号链接；生成带 SHA-256 清单的源码 ZIP。检查通过并不等于完整安全审计或许可证审计。

## 正式公开前仍需完成

1. 已为自有源码加入 MIT；第三方模板未捆绑，依赖仍保留各自许可。
2. 在没有旧工作区依赖的机器上验证数据库初始化、一次真实模型请求、一次综述和一次论文交付。
3. 确定公开仓库地址、维护者署名与联系入口；再补充 README 中的真实链接。
4. 使用经过授权的公开论文和全新演示数据录制演示；演示输出应由实际运行产生。
5. 如果从旧 Git 仓库发布，单独扫描完整历史。`.gitignore` 不会移除已经进入历史的秘密或数据。新源码目录不包含旧 `.git`。

整理阶段未对外发布；本目录现用于公开源码预览发布。产品介绍可以使用，但不要把未完成的发布项写成已经完成。
