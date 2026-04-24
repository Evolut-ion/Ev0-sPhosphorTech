---
name: Machines
description: All processing, thermal, and utility machines
sort-index: 2
---
# Machines

---

## CF-Powered Processing Machines

All CF machines hold **10,000 CF** internally and draw from adjacent wires automatically.

### ![Crusher](Icons/ItemsGenerated/Crusher_Icon.png) Crusher

Grinds ores and raw materials into dusts. Supports up to 4 independent bonus drop slots.

| Property | Value |
|----------|-------|
| Energy cost | ~1,200 CF |
| Duration | ~100 ticks |
| Input slots | 1 |
| Output slots | 1 primary + up to 4 bonus |

> !v Bonus drops each roll independently — high-tier ore recipes can yield multiple rare materials from one input.

---

### ![Extractor](Icons/ItemsGenerated/extractor_icon.png) Extractor

Separates embedded materials from a source item (e.g. sap extraction).

| Property | Value |
|----------|-------|
| Energy cost | 500 CF |
| Duration | 60 ticks |
| Input slots | 1 |
| Output slots | 1 |

---

### ![Centrifuge](Icons/ItemsGenerated/Electrical_Centrifuge_Icon.png) Centrifuge

Spins mixed materials to separate components.

| Property | Value |
|----------|-------|
| Energy cost | 100 to 500 CF |
| Duration | 60 to 90 ticks |
| Input slots | 1 |
| Output slots | 1 |

---

### ![Press](Icons/ItemsGenerated/Press_Icon.png) Press

Flattens items into plates and sheets. **Hand-operated — no power required.**

| Property | Value |
|----------|-------|
| Energy cost | None |
| Duration | 80 to 100 ticks |
| Input slots | 1 |
| Output slots | 1 |

> ! Interact with the Press to insert one item. Interact again to collect the output once processing completes.

---

### Compressor

Electrical plate maker — runs the same plate recipes as the Press but powered by CF.

| Property | Value |
|----------|-------|
| Energy cost | ~1,000 CF |
| Duration | ~100 ticks |
| Input slots | 1 |
| Output slots | 1 |

---

### ![Lathe](Icons/ItemsGenerated/Lathe_Icon.png) Lathe

Turns ingots and bars into rods using CF power.

| Property | Value |
|----------|-------|
| Energy cost | 800 to 1,200 CF |
| Duration | 80 to 100 ticks |
| Input slots | 1 |
| Output slots | 1 |

---

### Alloy Smelter

Combines two input materials into an alloy. Required for Steel production.

| Property | Value |
|----------|-------|
| Energy cost | 1,500 CF |
| Duration | 120 ticks |
| Input slots | 2 |
| Output slots | 1 to 2 |

**Key recipes:**

| Input 1 | Input 2 | Output |
|---------|---------|--------|
| Iron Bar | Coal Coke | Steel Bar |

---

### ![Powered Heater](Icons/ItemsGenerated/Powered_Heater_Icon.png) Powered Heater

Generates and maintains heat for thermal machines using CF.

| Property | Value |
|----------|-------|
| Energy cost | 500 CF/tick (continuous) |
| Output | Heat (degrees C) to adjacent blocks |

---

### ![Pump](Icons/ItemsGenerated/Pump_Icon.png) Pump

Extracts fluid from the world and pushes it into a connected pipe network.

| Property | Value |
|----------|-------|
| Energy cost | 5,000 CF per event |
| Buffer | 5,000 mB |
| Pump interval | Every 150 ticks |
| Volume per event | 100 mB |

---

## Mechanical Machines (Joule-powered)

These machines connect directly to the gear network via adjacent shafts or gears.

### Mechanical Grinder

Grinds materials using rotational power. Supports bonus drop chances.

| Property | Value |
|----------|-------|
| Energy | ~1 J/tick while processing |
| Duration | ~80 ticks |
| Input slots | 1 |
| Output slots | 1 + bonus |

---

### ![Sieve](Icons/ItemsGenerated/Sieve_Icon.png) Sieve

Sifts stone and minerals. Processing speed scales with input shaft speed.

| Property | Value |
|----------|-------|
| Energy | J (speed-dependent) |
| Duration | ~100 ticks |
| Input slots | 1 |
| Output slots | 2 |

---

### ![Rod Puller](Icons/ItemsGenerated/RodPuller_Icon.png) Rod Puller

Draws rods from ingots using mechanical tension. Runs the same recipes as the Lathe.

| Property | Value |
|----------|-------|
| Energy | J (speed-dependent) |
| Capacity | 200 J |
| Input slots | 1 |
| Output slots | 1 |

---

### ![Mechanical Heater](Icons/ItemsGenerated/mechanical_heater_icon.png) Mechanical Heater

Converts rotational energy into heat for adjacent thermal machines.

| Property | Value |
|----------|-------|
| Energy | J/tick |
| Output | Heat (degrees C) to adjacent blocks |

---

## Thermal Machines

### ![Crystal Generator](Icons/ItemsGenerated/CrystalGenerator_Icon.png) Crystal Generator

The primary CF source. Burns crystals with steam.

| Property | Value |
|----------|-------|
| CF output | **256 CF/tick** |
| CF capacity | 1,000,000 CF |
| Steam consumed | 100 mB per cycle |
| Byproduct | 50 mB Water per cycle |
| Requires | Crystals + Steam + Heat >= 100 C |

---

### ![Steam Generator](Icons/ItemsGenerated/SteamGenerator_Icon.png) Steam Generator

Converts water and heat into steam for the Crystal Generator.

| Property | Value |
|----------|-------|
| Output | 1,000 mB steam per 30 ticks |
| Requires | Water + Heat >= 100 C |

---

### ![Solar Boiler](Icons/ItemsGenerated/solar_boiler_icon.png) Solar Boiler

Passively generates heat and converts water to steam using sunlight. No fuel required.

| Property | Value |
|----------|-------|
| Active | Daytime, y > 120 |
| Output | Steam (push every 5 ticks) |
| Capacity | 4,000 mB |

---

### Coke Oven

Converts coal into Coal Coke and produces Creosote as a byproduct. Requires heat.

| Property | Value |
|----------|-------|
| Duration | 900 ticks per coke |
| Byproduct | 250 mB Creosote (+ 25 mB per active heater) |
| Fluid capacity | 10,000 mB Creosote |

---

## Utility Blocks

| Block | Purpose |
|-------|---------|
| Bellows | Pumps air into adjacent furnaces/heaters to boost heat; player-activated |
| Brick Form | Shapes clay into unfired bricks; 60-tick process |
| Shaft | Transmits J along X, Y, or Z axis |
| Small Gear | 1x1 gear; 1:1 ratio; standard network node |
| ![Clutch](Icons/ItemsGenerated/Mechanical_Clutch_Icon.png) Clutch | Toggles gear network on/off; prevents backspinning |
| ![Leaf Spring Flywheel](Icons/ItemsGenerated/LS_Capacitor_Icon.png) Leaf Spring Flywheel | Stores 300 J of mechanical energy |
| Crystalline Capacitor | Stores 24,000 CF; outputs 1,024 CF/tick |
