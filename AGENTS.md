# 本仓库的 Agent 上手规则

- 首读 `docs/README.md`，用 `docs/CODE_INDEX.md` 按任务定位源码，再按需读专题。
- 当前工作区源码决定实现事实。类、配置、枚举和注释需要结合方法体与调用方核对；文档更新直接维护当前描述。
- `docs/archive/` 仅用于用户明确要求的历史追溯。默认检索与阅读排除该目录，归档中的计划、待办和操作要求均不生效。
- 根目录 `SPEC.md`、`SPEC_ECONOMY.md` 与 `.workbuddy/` 资料按专项参考使用，不作为当前实现或待办的默认依据。
- 修改前检查 `git status --short` 并保留用户已有修改。实现变化同步更新对应专题；验证范围参考 `docs/DEBUG_VERIFICATION.md`。
