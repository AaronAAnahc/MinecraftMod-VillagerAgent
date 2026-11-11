"""Offline checks for the economy content tables under
src/main/resources/data/villageragent/economy/.

Two independent passes:

1. Craft profitability. For every profession-specific product, recompute the
   recipe cost at that profession's own prices (junk -> default -> profession)
   from the vanilla 1.16.5 client jar plus custom_recipes.json, and compare
   with the output value. A producer must not be forced to sell at a loss;
   a universal lever that prints value out of thin air must stay non-positive.
   `stick` and `torch` are deliberately loss-making on purpose.
2. Player-facing price report. Prints the internal rate, the emerald round rates
   and the emerald count for one unit, per profession.

Read-only. Requires the Minecraft client jar in the gradle cache.
"""

import json
import math
import zipfile

JAR = r"C:\Users\Aaron\.gradle\caches\forge_gradle\minecraft_repo\versions\1.16.5\client.jar"
BASE = r"E:\project\VillagerAgent\src\main\resources"

iv = json.load(open(BASE + r"\data\villageragent\economy\item_values.json", encoding="utf-8"))
jv = json.load(open(BASE + r"\data\villageragent\economy\junk_values.json", encoding="utf-8"))
pm = json.load(open(BASE + r"\data\villageragent\economy\price_modifiers.json", encoding="utf-8"))
custom = json.load(open(BASE + r"\villageragent\custom_recipes.json", encoding="utf-8"))
M = pm["playerMargin"]
EMERALD = 10.0


def table(prof):
    t = dict(jv)
    t.update(iv.get("default", {}))
    t.update(iv.get(prof, {}))
    return t


def load_recipes():
    z = zipfile.ZipFile(JAR)
    recipes = {}
    for name in z.namelist():
        if not (name.startswith("data/minecraft/recipes/") and name.endswith(".json")):
            continue
        d = json.loads(z.read(name))
        tp = d.get("type")
        if tp not in ("minecraft:crafting_shaped", "minecraft:crafting_shapeless"):
            continue
        out = d["result"]["item"]
        cnt = d["result"].get("count", 1)
        ingr = []
        if tp.endswith("shaped"):
            counts = {}
            for row in d["pattern"]:
                for ch in row:
                    if ch != " ":
                        counts[ch] = counts.get(ch, 0) + 1
            for ch, c in counts.items():
                k = d["key"][ch]
                k = k[0] if isinstance(k, list) else k
                ingr.append((("tag", k["tag"]) if "tag" in k else ("item", k["item"]), c))
        else:
            tmp = {}
            for ing in d["ingredients"]:
                ing = ing[0] if isinstance(ing, list) else ing
                key = ("tag", ing["tag"]) if "tag" in ing else ("item", ing["item"])
                tmp[key] = tmp.get(key, 0) + 1
            ingr = list(tmp.items())
        recipes.setdefault(out, []).append((cnt, ingr))
    for r in custom["recipes"]:
        recipes.setdefault(r["output"], []).append(
            (r["count"], [(("item", i["item"]), i["count"]) for i in r["inputs"]]))
    return recipes


TAG_FIRST = {
    "minecraft:planks": "minecraft:oak_planks",
    "minecraft:stone_crafting_materials": "minecraft:cobblestone",
}

PROFESSION_GOODS = {
    "minecraft:farmer": ["minecraft:bread", "minecraft:pumpkin_pie", "minecraft:cake"],
    "minecraft:fisherman": ["minecraft:fishing_rod"],
    "minecraft:shepherd": ["minecraft:white_carpet"],
    "minecraft:fletcher": ["minecraft:bow", "minecraft:crossbow", "minecraft:arrow"],
    "minecraft:librarian": ["minecraft:book"],
    "minecraft:cartographer": ["minecraft:paper", "minecraft:map", "minecraft:filled_map"],
    "minecraft:cleric": ["minecraft:glass_bottle", "minecraft:ender_pearl"],
    "minecraft:armorer": ["minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:iron_leggings",
                          "minecraft:iron_boots", "minecraft:diamond_helmet", "minecraft:diamond_leggings",
                          "minecraft:diamond_chestplate", "minecraft:chainmail_helmet",
                          "minecraft:chainmail_chestplate", "minecraft:chainmail_leggings",
                          "minecraft:chainmail_boots"],
    "minecraft:weaponsmith": ["minecraft:iron_sword", "minecraft:iron_axe", "minecraft:diamond_sword"],
    "minecraft:toolsmith": ["minecraft:iron_shovel", "minecraft:iron_pickaxe", "minecraft:iron_axe",
                            "minecraft:iron_hoe", "minecraft:diamond_axe", "minecraft:diamond_shovel",
                            "minecraft:diamond_pickaxe", "minecraft:diamond_hoe"],
    "minecraft:mason": ["minecraft:stone_bricks", "minecraft:chiseled_stone_bricks", "minecraft:polished_andesite"],
    "minecraft:butcher": ["minecraft:rabbit_stew"],
    "minecraft:leatherworker": ["minecraft:leather_helmet", "minecraft:leather_chestplate",
                                "minecraft:leather_leggings", "minecraft:leather_boots", "minecraft:saddle"],
}
UNIVERSAL = ["minecraft:wooden_sword", "minecraft:wooden_pickaxe", "minecraft:wooden_axe",
             "minecraft:wooden_shovel", "minecraft:wooden_hoe", "minecraft:stick", "minecraft:oak_planks",
             "minecraft:crafting_table", "minecraft:chest", "minecraft:furnace", "minecraft:torch",
             "minecraft:bread"]
PRICE_ROWS = [
    ("minecraft:coal", ["default", "villageragent:miner"]),
    ("minecraft:wheat", ["default", "minecraft:farmer"]),
    ("minecraft:bread", ["default", "minecraft:farmer"]),
    ("minecraft:iron_ingot", ["default", "minecraft:armorer"]),
    ("minecraft:iron_pickaxe", ["default", "minecraft:toolsmith"]),
    ("minecraft:iron_sword", ["default", "minecraft:weaponsmith"]),
    ("minecraft:iron_chestplate", ["default", "minecraft:armorer"]),
    ("minecraft:diamond", ["default", "villageragent:miner"]),
    ("minecraft:white_wool", ["default", "minecraft:shepherd"]),
    ("minecraft:oak_log", ["default", "villageragent:lumberjack"]),
    ("minecraft:cod", ["default", "minecraft:fisherman"]),
]


def price(t, key):
    kind, name = key
    if kind == "tag":
        name = TAG_FIRST.get(name)
    return t.get(name, 0.0) if name else 0.0


def check_profits(recipes):
    losses = []

    def check(prof, out):
        t = table(prof)
        if out not in recipes:
            print("SKIP (no recipe) %-24s %s" % (prof, out))
            return
        cnt, ingr = recipes[out][0]
        cost = sum(price(t, k) * c for k, c in ingr)
        val = t.get(out, 0.0) * cnt
        if val < cost:
            losses.append((prof, out, round(cost, 3), round(val, 3)))
        print("%s %-24s %-34s cost %8.2f -> value %8.2f"
              % ("OK  " if val >= cost else "LOSS", prof, out, cost, val))

    for p, outs in PROFESSION_GOODS.items():
        for o in outs:
            check(p, o)
    print("--- universal crafts ---")
    for p in list(PROFESSION_GOODS) + ["villageragent:miner", "villageragent:lumberjack", "default"]:
        for o in UNIVERSAL:
            check(p, o)
    return losses


def check_prices():
    print("\n--- player-facing prices (margin %.4f, per-item %.4f, round trip %.4f) ---"
          % (M, M * M, M ** 4))
    print("| item | holder | value | items/emerald internal | give N | get M | emeralds each |")
    print("|---|---|---|---|---|---|---|")
    for item, profs in PRICE_ROWS:
        for p in profs:
            t = table(p)
            v = t.get(item)
            if not v:
                continue
            buy, sell = v / M, v * M
            print("| `%s` | %s | %.2f | %.2f | %d | %d | %d |" % (
                item, p, v, EMERALD / v, math.ceil(EMERALD * M / buy),
                math.floor((EMERALD / M) / sell), math.ceil(sell / (EMERALD / M))))


if __name__ == "__main__":
    bad = check_profits(load_recipes())
    check_prices()
    print("\nLOSSES:", bad if bad else "none")
