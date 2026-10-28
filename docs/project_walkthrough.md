# Project walkthrough

Reviewed: 2026-10-27. Technical facts come from [Execution pipeline and resource orders](execution_pipeline_and_resource_orders.md); see [Implementation status](implementation_status.md) for verification boundaries. Describe personal responsibilities according to actual involvement.

## Thirty-second introduction

This is a Minecraft villager agent mod whose decisions execute on the server. It also includes client UI/rendering, custom blocks and textures; it is not a server-only installation. Villagers choose work, conversation, and rest using needs, environmental opportunities, and Utility weights; skills advance actions every tick. Resource orders connect timber and mineral supply: workers claim orders, gather real blocks, collect drops, and deliver, with completion based on the requester's actual inventory. Rules run independently, with optional LLM content for conversations and other interactions.

## Explanation sequence

Explain perception, candidates, scheduling, skills, actions, and results, then illustrate cooperation with an order. A has two logs and wants ten, leaving a shortage of eight. B obtains a lease before its supply candidate is queued, then renews it when scheduled, checks inventory/tools, finds locally reachable resources, gathers, and delivers. If A can accept only three, only three transfer and the order remains short. It closes when actual stock reaches the target.

Failure handling covers missing-tool recovery, waiting for resources, releasing leases on interruption, cooldowns, and bounded retries. Reload preserves ordinary orders and entity professions; transient method-owned demand is recreated from objectives. Execution plans rebuild from world and inventory state.

## Common questions

| Question | Implementation basis |
|---|---|
| Why Utility? | Current state adjusts scores; ordinary candidates use direct weighted randomness, with urgent goals first |
| Is this HTN/GOAP? | Item acquisition discovers registered methods, estimates prerequisite costs, and decomposes recursively; resource delivery still uses handwritten steps. This bounded HTN-style implementation does not search arbitrary world-state effects or implement GOAP |
| How are competing claims prevented? | Sequential server eligibility/lease checks; one valid claimant per order and one valid lease per worker |
| What proves completion? | World changes, drops, and real inventory; batch success and order satisfaction are checked separately |
| Does restart resume exact execution? | Ordinary demand persists; owned requests and method attempts do not. Leases are reacquired; world/entity saves are not cross-file atomic commits |
| What is the performance evidence? | Perception/query budgets exist; scale, TPS, and behavior distributions require measured reports |
| What if the LLM is offline? | Rule-based work continues and villager dialogue has local fallback; inspect protection on each LLM path |

Distinguish compilation, rule checks, and Minecraft acceptance. Architecture and expected effects are not measured results.
