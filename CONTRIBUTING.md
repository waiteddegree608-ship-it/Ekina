# 参与贡献

请围绕可核验的科研过程提交改进。问题报告应包含复现步骤、脱敏配置、预期与实际行为；不要提交 API key、用户对话或未经授权的论文全文。

推荐流程：选择一个具体问题，修改最小相关模块，运行对应测试，并说明影响的证据/产物与限制。文献适配器应说明来源和获取失败时的行为；实验功能应提供配置、日志和结果对应关系。

## 检查命令

```sh
python scripts/validate_source.py
npm --prefix ekina-ui ci
npm --prefix ekina-ui run build
npm --prefix mcps/research ci
npm --prefix mcps/research test
python -m unittest discover -s scripts -p 'test_review_*.py'
mvn test '-Dtest=SkillDirectoryTest,ResearchCapabilityTest,ResearchPlanTest,ResearchLedgerTest,ResearchProjectServiceTest,ProjectBoardServiceTest,ChatMemoryServiceTest,Review*Test' '-Dsurefire.failIfNoSpecifiedTests=false'
```

上述 Maven 选择科研相关的隔离测试；其他集成测试可能需要数据库与服务，不等于全部测试已覆盖。对源码和文档的一般改动不要调用真实付费模型来代替本地验证。

插件清单遵循 `plugins/README.md`；Skill 描述应准确限定任务范围，不包含私人案例的固定结论。项目自有源码采用 MIT；贡献时请保留第三方来源与许可证声明。
