---
name: Recipes
description: Processing and alloy smelter recipe formats
sort-index: 4
---
# Recipes

---

## Machine-to-Recipe Matrix

| Machine | Recipe Type | Max Bonus Outputs |
|---------|------------|------------------|
| ![Crusher](Icons/ItemsGenerated/Crusher_Icon.png) Crusher | Processing | 4 |
| ![Extractor](Icons/ItemsGenerated/extractor_icon.png) Extractor | Processing | 0 |
| ![Centrifuge](Icons/ItemsGenerated/Electrical_Centrifuge_Icon.png) Centrifuge | Processing | 0 |
| ![Press](Icons/ItemsGenerated/Press_Icon.png) Press | Press | 0 |
| Compressor | Press | 0 |
| ![Lathe](Icons/ItemsGenerated/Lathe_Icon.png) Lathe | Lathe | 0 |
| ![Rod Puller](Icons/ItemsGenerated/RodPuller_Icon.png) Rod Puller | Lathe | 0 |
| ![Sieve](Icons/ItemsGenerated/Sieve_Icon.png) Sieve | Sieve | 2 |
| Mechanical Grinder | Processing | 4 |
| Alloy Smelter | Alloy | 1 (second output) |

> ! The **Press** and **Compressor** share recipes. The **Lathe** and **Rod Puller** share recipes. Both pairs can run the same inputs â€” just with different energy sources.

---

## Processing Recipe Format

Used by: Crusher, Extractor, Centrifuge, Sieve, Press, Compressor, Lathe, Rod Puller, Mechanical Grinder.

```json
{
  "Input":        "ItemId",
  "Output":       "OutputItemId",
  "OutputQty":    1,
  "CfCost":       1200,
  "Ticks":        100,

  "BonusOutput":  "BonusItemId",
  "BonusQty":     1,
  "BonusChance":  0.65,

  "BonusOutput2": "BonusItemId2",
  "BonusQty2":    1,
  "BonusChance2": 0.30,

  "BonusOutput3": "BonusItemId3",
  "BonusQty3":    1,
  "BonusChance3": 0.10,

  "BonusOutput4": "BonusItemId4",
  "BonusQty4":    1,
  "BonusChance4": 0.05
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `Input` | Yes | Item ID of the input |
| `Output` | Yes | Item ID of the primary output |
| `OutputQty` | Yes | Amount produced |
| `CfCost` | Yes | CF consumed (ignored for mechanical machines) |
| `Ticks` | Yes | Base processing duration |
| `BonusOutput` â€“ `BonusOutput4` | No | Up to 4 independent bonus drops |
| `BonusChance` â€“ `BonusChance4` | No | Per-roll probability (0.0 â€“ 1.0); each rolls independently |

---

## Alloy Smelter Recipe Format

Used by: Alloy Smelter only.

```json
{
  "Input1":     "Ingredient_Bar_Iron",
  "Input2":     "Ingredient_CoalCoke",
  "Output1":    "SteelAge_Ingredient_Bar_Steel",
  "Output1Qty": 1,
  "Output2":    "Ingredient_Slag",
  "Output2Qty": 1,
  "CfCost":     1500,
  "Ticks":      120
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `Input1` / `Input2` | Yes | Both must be present before processing starts |
| `Output1` | Yes | Primary output |
| `Output1Qty` | Yes | |
| `Output2` | No | Optional secondary output |
| `Output2Qty` | No | |
| `CfCost` | Yes | CF consumed |
| `Ticks` | Yes | Duration |

---

## Recipe File Location

Place recipe JSON files in:

```
src/main/resources/Server/Recipes/{MachineName}/
```

| Machine | Subfolder |
|---------|-----------|
| Crusher | `Server/Recipes/Crusher/` |
| Extractor | `Server/Recipes/Extractor/` |
| Centrifuge | `Server/Recipes/Centrifuge/` |
| Press & Compressor | `Server/Recipes/Press/` |
| Lathe & Rod Puller | `Server/Recipes/Lathe/` |
| Sieve | `Server/Recipes/Sieve/` |
| Alloy Smelter | `Server/Recipes/AlloySmelter/` |

All `.json` files in the matching subfolder are loaded automatically at startup.
