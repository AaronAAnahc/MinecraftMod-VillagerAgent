# Long-term memory and believable LLM villagers: research and repository recommendations

Reviewed: 2026-10-24. This research uses the current workspace and primary papers/projects; it is not an implementation specification. Source code and [LLM and world interaction](llm_and_world_interaction.md) / [Events and knowledge sharing](events_and_knowledge_sharing.md) define current behavior.

## Main findings

Believable villagers need behavior consistently shaped by personal experience, stable personality, changing relationships, current needs, and real world feedback. Randomly adding LLM behaviors is insufficient. Stanford Generative Agents combines observation records, on-demand retrieval, reflection, plans across time, and reactions to unexpected events. Its ablations show contributions from observation, reflection, and planning. [Paper](https://arxiv.org/abs/2304.03442)

For this repository, retain deterministic execution and world validation. Use the LLM for memory summaries, reflection, selection among legal candidates, and character expression. Each new behavior should explain why it happens now, which experiences support it, and whether the resulting world state supports its conclusion.

## Long-term memory in LLM agents

Long-term memory is more than inserting the entire chat history into a prompt. Common categories serve different purposes:

| Memory | Stored content | Purpose |
|---|---|---|
| Stable identity | Personality, preferences, profession, values | Consistent choices and expression across contexts |
| Episodes | Events, participants, locations, outcomes, times | Recall personal experiences |
| Semantic beliefs | Facts/judgments generalized from experiences, with evidence | Stable beliefs such as trusting someone or enjoying farming |
| Plans/commitments | Goals, deadlines, counterparties, progress, cancellation/completion | Intent that persists across steps |
| Shared knowledge | Heard information with source, confidence, and freshness | Rumors, dissemination, and correction |

Build a retrieval query from current observations, then retrieve a small relevant set. Generative Agents scores recency, importance, and semantic relevance, normalizes them, and combines rankings. Recency decays from the last access; the model rates importance on insertion; relevance uses embedding cosine similarity. [Section 4.1](https://arxiv.org/abs/2304.03442#section.4)

Raw episodes also need periodic higher-level reflection stored as retrievable memory. Generative Agents links reflections to supporting original memories, enabling recursive evidence-based generalization. Plans are memories too, allowing new observations to retrieve and continue or revise them. [Sections 4.2–4.3](https://arxiv.org/abs/2304.03442#section.4)

The useful principle is a traceable chain: experience → evidence-based belief → goal/behavior → observable outcome. There is no need to copy a particular vector database or threshold. In a survival game, actual inventory, blocks, POIs, relationship scores, and skill eligibility remain authoritative; generated narrative cannot overwrite them.

## Reference projects

### Stanford Generative Agents / Smallville

Experiences enter a memory stream, retrieved through recency/importance/relevance. Periodic reflections retain evidence references. Coarse daily plans are recursively refined into hourly and minute-level activities, with new observations prompting interruption/replanning. Conversations use both parties' memories and relationships. [Paper](https://arxiv.org/abs/2304.03442)

The project demonstrates invitation spreading, relationship formation, and coordination. Its stated goal is believable simulation, not proof of human-like agency. Evaluation mainly uses short simulations and human rankings of interview plausibility; it cannot guarantee correct open-world behavior. [Abstract and evaluation](https://arxiv.org/abs/2304.03442)

### Google DeepMind Concordia

Concordia supports composable social simulations. Components orchestrate LLM calls and associative memory retrieval; agents express intentions, and a Game Master checks physical feasibility, maps actions into the environment, and describes outcomes. [Paper](https://arxiv.org/abs/2312.03664), [official repository](https://github.com/google-deepmind/concordia)

For Minecraft, separate intention from permission: the LLM proposes; execution validates profession, eligibility, distance, inventory, and paths; world execution returns outcomes. Concordia is a general simulation library, not a finished Minecraft villager system.

### Voyager (Minecraft)

Voyager targets open-ended exploration and skill accumulation rather than socially connected village life. An automatic curriculum proposes tasks; successful executable code enters a skill library retrieved by description embeddings and combined into new skills. Execution errors, environment feedback, and self-verification guide iterations; only successful skills enter the library. [Paper](https://arxiv.org/abs/2305.16291), [project](https://voyager.minedojo.org/)

Reusable learning should map to executable skills, preconditions, and completion criteria, not just a sentence claiming learning. VillagerAgent already has skill catalogs and result states suited to constrained selection/composition instead of arbitrary operation sequences.

## Current VillagerAgent foundation and gaps

`VillagerAgentData` stores text memories, relationships, conversation history, and long-term agendas, with agent data persisted in NBT. `ChunkMemory` stores spatial observations. `VillagerSocialSystem` writes conversations into both participants' memories and updates relationships. `GossipChannel` can share one resource-location feature. Text memory thresholds trigger LLM compression; failure truncates older records. See agent, social, memory, and harness entries in the [code index](CODE_INDEX.md).

Missing connections:

- Ordinary inner thoughts mainly use the latest text memory rather than relevance/importance/time retrieval. Harness `DecisionRequest` contains no retrieved long-term episodes.
- `HarnessDecisionPlanner.decideDailyGoal` parses JSON, validates actions, and enqueues goals, but has no main-loop caller. The setting alone does not enable daily decisions.
- `LongTermAgenda` persists commitments and `AgendaParser` parses related player-chat instructions, without generic reflection generation and proactive agenda-to-goal decomposition.
- Personality is mainly free text used by some prompts and a few rule conditions, not a stable computable personality model.
- Gossip primarily shares resource anchors. Relationships and social memories use separate mechanisms rather than a general provenance/confidence-aware rumor system.
- Tighten asynchronous memory compression/write boundaries: reads/writes and entity access should use the server thread or immutable snapshots to avoid callbacks mutating collections concurrently with ticks.

These limits match current topics. Treat workspace source as authoritative rather than interpreting aspirational `LongTermAgenda` comments as connected capabilities. [Integration topic](llm_and_world_interaction.md)

## Making new behavior more believable

Behavior count alone does not establish believability. Each addition should have a checkable chain:

1. Distinct motivations: personalities, needs, and relationships change preferences in the same environment without bypassing eligibility or physics.
2. Consequential experience: important events affect later choices/dialogue; retrieved records identify source, time, and participants instead of inserting random old text.
3. Continuity over time: goals retain progress, waiting reasons, and completion conditions, with interruption/replanning for danger, changed needs, or invalid targets.
4. Real consequences: choices map to implemented skills and execute in the world. World state verifies success; specific failures inform later decisions.
5. Accumulating social history: remember who did what, change relationships gradually, and record speaker/confidence/time for rumors while distinguishing direct experience from hearsay.
6. Individual variation and routine: stable habits with modest preference differences can portray individuals better than constant dramatic novelty.

Use small regression scenarios: two personalities respond differently and explainably to the same event; a helped villager remembers a player and changes later behavior; an invitation is remembered and attended on time; missing items/eligibility never produce fabricated success; danger/conflicts interrupt and recover. Measure success, invalid/repeated goals, retrieval accuracy, commitment fulfillment, and blinded human ratings of consistency/individuality.

Stanford evaluates self-knowledge, event recall, planning, reactions, and reflection, comparing behavior with observation/planning/reflection removed, beyond whether one line sounds natural. [Evaluation design](https://arxiv.org/abs/2304.03442#section.6)

## Suggested implementation order

### Stage one: retrieve existing memories

Give records explicit type, tick, subject/object, importance, provenance, and optional evidence IDs; retain readable text for debugging. Start with bounded Top-K keyword/entity matching and recency for the current decision context. Add semantic similarity if an embedding service is available. Establish retrieval and observation tools before expanding LLM decision authority.

### Stage two: turn reflection into stable state

At low frequency, such as after important events accumulate or on a Minecraft day boundary, derive a few structured beliefs from retrieved episodes: preferences, relationship hypotheses, recent commitments, repeated-failure causes. Include evidence references, confidence, and update time. Preserve conflicting facts rather than overwriting them with a summary. Keep authoritative structured player relationships and world state.

### Stage three: constrained candidate selection

The rules provide currently executable goal/skill candidates, eligibility, utility, and completion criteria. Model inputs include compact state, personality, retrieved memories, plan progress, and candidates; outputs allow only candidate IDs, short reasons, or bounded parameters. Revalidate feasibility before enqueueing. Rejection, timeout, or malformed JSON leaves rule scheduling in control. Free text must not become arbitrary goals.

### Stage four: observable closed-loop evaluation

Run short scripts with fixed seeds/scenarios and record input snapshots, retrieved memories, candidate sets, choices, validation, and execution results. Compare repeated/failed goals, commitments, and human believability against a rule-only baseline, enabling memory, reflection, and LLM selection individually. Expand LLM behavior only when metrics improve without crossing eligibility boundaries.

This follows `GoalScheduler`, `BehaviorExecutor`, `ActionValidator`, and `DecisionJournal` boundaries and the fact that LLM daily planning is not in the current main loop. Maintain topic documentation during implementation and follow [verification guidance](DEBUG_VERIFICATION.md).

## Primary sources

- Park et al., [Generative Agents: Interactive Simulacra of Human Behavior](https://arxiv.org/abs/2304.03442), UIST 2023.
- Vezhnevets et al., [Generative agent-based modeling with actions grounded in physical, social, or digital space using Concordia](https://arxiv.org/abs/2312.03664), 2023; [Google DeepMind Concordia source](https://github.com/google-deepmind/concordia).
- Wang et al., [Voyager: An Open-Ended Embodied Agent with Large Language Models](https://arxiv.org/abs/2305.16291), TMLR 2024; [Voyager project](https://voyager.minedojo.org/).
