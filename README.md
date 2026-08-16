# 🤖 VillagerAgent — AI-Powered Minecraft Villagers

Transform vanilla Minecraft 1.16.5 villagers into autonomous **Generative Agents** (Stanford "AI Town"-style): each villager has a personality, long-term memory, goals, needs, relationships, a daily schedule — and perceives its surroundings through a **layered spatial-perception stack** before acting, all driven by a configurable LLM.

## ✨ Highlights

- 🧠 **LLM-driven autonomy** — personality, memory, goal generation, dialogue and decision-making all run through a pluggable LLM backend.
- 🗺️ **Layered perception (Far / Mid / Near)** — chunk content memory → building & room index → frustum-culled near-field scan, assembled into structured text for the LLM.
- 🏘️ **Building & room understanding** — automatic detection of buildings and bed→room segmentation via a deterministic distance-field / watershed algorithm (no extra ML).
- ⚙️ **Decision harness** — per-call timeouts, circuit breakers, and a JSONL decision journal keep villagers responsive even when the API is slow or down.
- 🎨 **In-game GUIs** — custom chat screen, trade screen, and a config-driven debug overlay for visualizing perception.
- 🛠️ **World interaction** — farming, combat, crafting, and LLM-designed block building.

---

## 🧠 Architecture: Layered Perception

The core idea: villagers don't scan the whole world every tick. Three layers with different ranges and costs feed a single structured representation.

| Layer | Range | Component | What it does |
|---|---|---|---|
| **Far** | visited chunks | `ChunkMemory` | Remembers *what* a chunk contains (dominant `BlockCategory` + feature tags like `forest`/`farmland`/`water`), not all 2048 blocks. LRU-bounded (~512 chunks). |
| **Mid** | loaded structure | `WorldStructureIndex` + `BuildingLocator` | Event-driven index of buildings. `BuildingLocator` segments each bed into its room using a multi-source geodesic distance field + synchronous dual-class watershed, then classifies rooms by air fraction. |
| **Near** | field of view | `FrustumCuller` + `DetailedViewRecorder` | Frustum-culled scan records only notable blocks and entities actually in view. |

All three are merged by `VillagerVisionSystem` into an environment summary (time, weather, biome, position, chunk memories, visible structures, near-field details) that is injected into every LLM call.

`WorldStructureIndex` is **event-driven and persistent**: each bed is scanned only once, work is queued one flood-fill per tick, and results survive restarts via `StructureIndexSavedData` (a `WorldSavedData`).

## ✅ Implemented Features

### Agent Core
- 🎭 **8 personalities** — friendly, shrewd, cautious, adventurous, wise, cheerful, grumpy, curious
- 📝 **Long-term memory** — ~40 entries with asynchronous LLM compression into summaries
- 🎯 **Goal system** — autonomous goal generation + profession-based goals (`ProfessionGoalGenerator`) and long-term agendas
- 🕐 **Daily schedule** — LLM plans a 4-slot day (morning/afternoon/evening/night) at dawn, reflects in the evening
- 😀 **Mood & needs** — hunger + fatigue drive a 5-tier mood (HAPPY → DISTRESSED) injected into all LLM prompts

### Social & Interaction
- 💬 **Player conversation** — right-click to talk, with a custom chat GUI
- 👋 **Proximity greeting** — villagers notice and greet nearby players (tunable probability, familiarity decay)
- 🗣️ **Villager-to-villager chat** — relationship-tiered dialogue, gossip propagation, opinion nudging
- 💭 **Spontaneous thoughts** — ambient inner-thought bubbles near players (off by default)

### World Interaction
- 🌾 **Farming** — harvest mature crops + replant (with stuck-timeout and seed-wait logic)
- ⚔️ **Combat** — scan for hostiles, equip best weapon, chase and attack
- 🔨 **Crafting** — profession-aware recipe catalog and validation
- 🏗️ **Building** — LLM designs a structure, `BuildOrderPlanner` + `StructureBuilder` place blocks (throttled, retry-limited)
- 💰 **Trading** — dynamic trading with a custom trade screen
- 🎒 **Inventory & equipment** — 27-slot inventory, auto item pickup, armor/held-item rendering

### Reliability
- 🛡️ **Decision harness** — timeout fallback, circuit breaker to rule-only mode, structured decision schema
- 📓 **Decision journal** — JSONL replay/eval log

### Debugging
- 🖥️ **Debug overlay** — HUD panel, building wireframes, watershed-field and seed visualization (all config-gated)

## 📦 Installation

1. Build from source (see below) or grab a release jar.
2. Drop the jar into your Minecraft `mods` folder.
3. Launch Minecraft with **Forge 36.2.42** (Minecraft **1.16.5**).
4. Configure your LLM endpoint (see below).

## ⚙️ Configuration

After first launch, edit `config/villageragent-common.toml`.

```toml
[LLM Settings]
    # Backend type: "openai" | "anthropic" | "ollama" | "gemini"
    llm_api_type = "openai"

    # Your API key from OpenAI (or any OpenAI-compatible provider)
    llm_api_key = "sk-your-api-key-here"

    # API endpoint URL
    llm_api_url = "https://api.openai.com/v1/chat/completions"

    # Model to use (e.g. "gpt-3.5-turbo", "gpt-4")
    llm_model = "gpt-3.5-turbo"

    llm_max_tokens = 150      # 50 - 1000
    llm_temperature = 0.7     # 0.0 - 2.0

[Agent Behavior]
    enable_ai_agents = true
    agent_think_interval = 100        # ticks between AI updates (20 ticks = 1s)
    enable_villager_chat = true       # villager-to-villager dialogue
    enable_world_interaction = true   # farming / crafting / combat
    enable_building = true            # LLM-designed block building
    enable_auto_pickup = true
    enable_daily_schedule = true
    enable_villager_social = true
    enable_villager_thoughts = false  # ambient thought bubbles

    # Debug overlay (visualization)
    enable_debug_overlay = false
    debug_show_buildings = true
    debug_show_seeds = true
    debug_show_field = true

[Decision Harness]
    harness_enabled = true
    harness_timeout_ms = 8000          # fall back to rules on timeout
    harness_circuit_failures = 3       # failures before rule-only mode
    harness_journal_enabled = true     # write JSONL decision journal
```

> 💡 You can also change LLM settings at runtime with the in-game command (see below) instead of editing the file.

## 🎮 How to Use

### Talking to villagers
1. Find a villager.
2. **Right-click** it to open the chat GUI.
3. Villagers respond with their personality, mood, and current activity.

### In-game command (`/villageragent`, permission level 2)

| Command | Purpose |
|---|---|
| `/villageragent config get <option>` | Read a config value |
| `/villageragent config set <option> <true/false>` | Toggle a feature flag |
| `/villageragent config list` | List options |
| `/villageragent llm apitype <type>` / `model <m>` / `apikey <k>` / `apiurl <u>` | Switch LLM backend at runtime |
| `/villageragent info` | Show agent status |
| `/villageragent reload` | Reload config |
| `/villageragent build place/break <x> <y> <z> [block]` | Manual block placement/removal |
| `/villageragent build structure <goal>` | Ask an LLM to design a structure |

### Observing AI behavior
Check the Minecraft log for agent creation, goal generation, memory updates and activity. Example:

```
[VillagerAgent] Creating new AI agent for villager: 12345678-…
[VillagerAgent] Generated new goal for Beatrice: Gather wheat (Priority: 7)
[VillagerAgent] Beatrice: New memory - Talked with player Steve
```

### Debug overlay
Enable `enable_debug_overlay` to see detected buildings as wireframes, watershed room boundaries, and a per-agent HUD.

## 🔨 Building from Source

```bash
git clone https://github.com/Aaron-AA0721/MinecraftMod-VillagerAgent.git
cd VillagerAgent
gradlew build          # produces build/libs/villageragent-*.jar
```

**Requirements**

- Java 8 JDK
- Minecraft 1.16.5 · Forge 36.2.42
- Gradle 8.8+ (ForgeGradle 6.x)

## 📝 Roadmap

**Done**

- [x] Core AI agent system, personality & memory
- [x] Goal system, daily schedule, mood & needs
- [x] LLM integration (OpenAI-compatible / Anthropic / Ollama / Gemini)
- [x] Player conversation + custom chat GUI
- [x] Villager-to-villager chat, gossip, proximity greeting
- [x] Farming, combat, crafting, block building
- [x] Dynamic trading + trade GUI
- [x] Layered perception (chunk memory / structure index / frustum scan)
- [x] Building & room detection (distance field + watershed)
- [x] Decision harness (timeout, circuit breaker, journal)
- [x] Debug overlay + in-game config command

**Planned**

- [ ] True pathfinding / navigation wired back to the room index (villagers currently navigate via vanilla `HOME` POI)
- [ ] Village coordination (multi-villager teamwork)
- [ ] Relationship-based trade price negotiation
- [ ] Weather reactions (seek shelter in rain)
- [ ] Death memory (witnesses remember nearby deaths)

## 🤝 Contributing

Contributions are welcome! Feel free to open issues or submit pull requests.

## 🙏 Acknowledgments

- Minecraft Forge team
- The Minecraft modding community
- OpenAI / Anthropic / DeepSeek for LLM APIs

## 📧 Contact

For questions or support, open an issue on [GitHub](https://github.com/Aaron-AA0721/MinecraftMod-VillagerAgent).

---

**Made with ❤️ for the Minecraft community**
