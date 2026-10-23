# Goal lifecycle audit (2026-10-23)

The dispatcher owns cancellation, retry budgets and retirement for all 16 registered goal types. FAIL and non-material BLOCKED results yield immediately, retain their failure count across cooldowns, and remove the goal on the third failure. MISSING_ITEM dependencies yield for 200 ticks without consuming the path/failure budget, so production remains queued while suppliers work. SUCCESS removes one-shot/expiring goals and resets repeatable routines. DELETE always removes the goal. WAITING yields without consuming the failure budget: waiting for supplies is not proof that a route is unreachable.

Retired repeatable routines cannot be reseeded for 1,200 ticks. This is a temporary environment retry, not a permanent assertion that every destination in a chunk is unreachable. Failure counters and reseed delays are transient and reset on reload.

| Goals | Exit/cleanup reviewed |
| --- | --- |
| wander | No reachable candidate or failed walk reports FAIL instead of infinite WAITING; third failure retires the current routine. |
| move, goto | Shared walk arrival/failure; navigation is stopped at exit. |
| gather | Inventory satisfaction succeeds; failed walking is propagated; external supply wait releases the body. |
| find_job_site | Existing JOB_SITE/resource profession removes all pending searches; successful claim deletes even repeatable searches. POI capacity is reserved on arrival. |
| craft | Arrival transitions to work; walking timeout is failure, not success. |
| acquire_item, restock | Waiting for inventory/materials releases the executor. |
| farm | Unreachable step reports failure rather than repeatedly selecting a failed step inside the same run. |
| sleep, rest | Bed walking has bounded failure; actual sleep is exempt from the general execution time limit. |
| socialize, converse, trade | Walking failures count toward retirement; ordinary waiting releases the executor. |
| fulfill_order, deliver_trade | Existing order/message validity checks remain; delivery actions expose their moving destination to the navigation watchdog. Cancellation releases work-order reservations. |

Unsupported or currently unclaimable pending goals also receive bounded retries, instead of being silently excluded forever. Orphaned suspended goals resume once the current goal leaves. Any non-sleeping RUNNING execution has a 2,400-tick upper bound, including legacy skills without their own timeout. The navigation watchdog gives a newly created action 20 ticks to initialize navigation, rejects missing/insufficient partial paths, and respects interaction-specific arrival distances.

## Movement

Verified against the local mapped Forge 1.16.5-36.2.42 jar using javap: VillagerEntity registers ordinary WORK/CORE/MEET/REST/IDLE activity packages with 0.5F. Ordinary goal navigation, farm approaches and world-interaction walks now use VillagerNavigationSupport.WALK_SPEED = 0.5. This changes the navigation multiplier, not the entity movement attribute. Combat settings remain independent.

## Verification

GoalExitPolicyChecks covers all lifecycle types, three-failure retirement, cooldowns, legacy completed flags, DELETE precedence, successful failure-count reset and supply waiting. Run with Gradle check (also runs existing work-order checks).

World integration still needs in-game verification: enclose a villager with no route; block a route after walking begins; remove or concurrently claim a workstation; provide missing crafting materials while a different goal runs; compare a managed and vanilla villager on a flat unobstructed route. Inspect skill-exit and goal-state logs for failure/cooldown/removal. Tests do not establish that arbitrary terrain is reachable or guarantee equal observed travel time under every activity.

## Follow-up: working versus wandering

The available gamelog/latest.log predates the lifecycle fixes (wander still returns WAITING on no path). Across that log there are 403 actual switches to wander, 137 to fulfill_order and 92 to craft. All 92 craft exits are BLOCKED; 136 order exits are WAITING; nine extraction steps were attempted and no order-delivery records appear. DECIDE records in that build are sampled candidates, not necessarily actual goal switches.

Code causes addressed:

- Wander was allowed to win the weighted lottery even with work ready, and ordinary work could not preempt an active walk. Wander is now a fallback among executable, positive-score candidates; ready purposeful goals can interrupt it. Ordinary work and urgent needs keep weighted selection. DECIDE is logged only when selection actually switches execution.
- Farm opportunity sensing hid empty/reverted fields when supplies were missing and omitted reclaimable land. These opportunities now reach FarmSkill and its recovery path.
- Craft routine eligibility incorrectly required generic CRAFTING, although CraftSkill uses unlocked profession recipes. Routine target selection now skips stock >= 8, prefers recipes with available materials, and refreshes idle craft targets.
- Resource roles/goals were assigned before reserving an order. Offers now reserve first; stale competing goals are deleted when their claim is denied, letting those villagers look for other orders.
- Delivery's fixed 600-tick total timeout could discard progress on long routes. Batch and partial deliveries share a movement-progress timeout and initialize navigation immediately. The dispatcher's overall execution cap still applies.
- Material dependencies are retained while waiting, rather than being confused with three failed paths.

GoalSchedulerChecks covers fallback selection, zero-score/cooling tasks, weighted selection among work, emergencies and preemption. GoalExitPolicyChecks additionally covers long supply waits followed by three genuine path failures. In-game delivery, farming and crafting still require verification after rebuilding/reloading the mod. Resource workers remain demand-driven: with no eligible order or reachable resource, exploration is expected.
