# PR #1 integration

This branch integrates [PR #1](https://github.com/AaronAAnahc/MinecraftMod-VillagerAgent/pull/1) by [Spagles](https://github.com/Spagles). The merge retains all nine original commits and their authorship.

## Conflict resolutions

- `VillagerAgentManager`: keep main's current-dimension entity check, navigation, goal processing, and diagnostic snapshot. Pass the already resolved entity to the slow update. Retain the PR's fix to decrement attack cooldown only in the villager's active dimension.
- `VillagerNeedsSystem`: keep main's per-agent `BrainRuntime` timestamps, elapsed-time hunger scaling, and fatigue integration. Retain the PR's logging placeholder correction. Drop the redundant `VillagerAgentData.LastNeedsTick` field and NBT writes introduced by the PR.
- `VillagerSchedulePlanner`: preserve main's deletion. Its one-line date fix does not justify restoring the obsolete daily planner.
- `TradeRequestPacket`: retain main's deterministic value-table decision and result snapshots. Apply the PR's server-side stock checking and removal of unfunded refunds, with additional inventory planning on copies, exact NBT matching, and capacity checks before any real inventory changes.

The integration also retains the local-coordinate building classification fix, cleanup of all claimed bed anchors when a building is removed, case-insensitive Gemini API selection, and closure of HTTP response streams.

## Player trading

Trade-screen selections are proposal copies. Clearing, canceling, or closing a proposal leaves real player inventory unchanged. Rejected trades do not synthesize refunds. Closing after submission does not revoke a request already sent to the server.

Both request slots share the villager inventory/armor stock limit; each slot respects its stack limit. Server checks remain authoritative if the client snapshot becomes stale. `TradeInventory.plan` debits both parties on copies before crediting either party, and returns failure without mutation when quantities, NBT, or capacity do not match. Successful plans are applied on the server thread and player inventory changes are synchronized.

## Merge of local main (`788922d`)

The newer local main supplies the English documentation, recursive crafting/workbench memory, trade demonstrations, and later behavior fixes. Its two conflicting manager hunks retain workbench observation and dependency unlocking around `updateAgent(world, loaded, agent)`, and preserve the trade-demo exclusion before the dimension-checked attack cooldown. Main's per-tick fatigue sampling is retained alongside elapsed-time hunger checks; the previous slow-update fatigue integration is superseded. The player trade fixes remain unchanged.

## Verification

Before merging the newer local main, `gradlew.bat compileJava check --offline --console=plain` passed: 86 goal-exit, 225 scheduling, 44 task-execution, and 21 order assertions. `scripts/check_trade_ui.py` reproduces the old repeated-click stock overflow and checks the fixed selection and transfer logic with lightweight API doubles. Minecraft interaction and network acceptance tests remain necessary.

After merging local main, the same full offline build/check passed again with all 376 assertions, and the trade regression harness passed. Conflict-marker and whitespace checks passed; all 20 active root/docs Markdown files are English and their local Markdown links resolve. Historical archive content is retained from main rather than rewritten as current documentation.

## Attribution when publishing

Push the integration branch with ordinary `git push`, then open an integration PR targeting main and credit Spagles with a link to #1. Use a merge commit when integrating into main to retain the original contribution commits and author metadata. Squashing replaces that history; if squashing is required, credit the contributor in the resulting commit with their verified commit identity:

```text
Co-authored-by: Spagles <106791090+Spagles@users.noreply.github.com>
```

The identity above comes from the nine PR commits. Publishing or messaging the contributor is a separate action; no remote update is required to prepare this branch locally.
