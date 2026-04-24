---
name: Energy
description: Crystalline Flux and Joule power systems
sort-index: 1
---
# Energy Systems

---

## Joules (J) — Mechanical Power

Joules are rotational energy carried through **Shafts** and **Gears**. All mechanical machines run on J.

### Generators

| Machine | Output | Condition |
|---------|--------|-----------|
| ![Hand Crank](Icons/ItemsGenerated/HandCrank_Icon.png) Hand Crank | 0.5 J/tick | Player interaction (30-tick burst) |
| ![Windmill](Icons/ItemsGenerated/Windmill_Icon.png) Windmill | 0.04 to 0.16 J/tick | Higher Y = more output |
| ![Waterwheel](Icons/ItemsGenerated/Waterwheel_Icon.png) Waterwheel | Up to ~0.5 J/tick | Requires adjacent flowing water (up to 4 sides) |

### Transmission

| Block | Function |
|-------|---------|
| Shaft | Passes J along one axis (X, Y, or Z) |
| Small Gear | 1x1 node; 1:1 ratio; counter-rotates neighbours |
| ![Clutch](Icons/ItemsGenerated/Mechanical_Clutch_Icon.png) Clutch | Toggles power flow on/off — use to isolate machine groups |

> !! Connecting two active J sources on the same node without a **Clutch** between them will lock the network.

### Storage

| Block | Capacity |
|-------|----------|
| ![Leaf Spring Flywheel](Icons/ItemsGenerated/LS_Capacitor_Icon.png) Leaf Spring Flywheel Capacitor | 300 J |

---

## Crystalline Flux (CF) — Electrical Power

CF is electrical energy transmitted through **Wires**. Most processing machines consume CF.

### Generators

| Machine | Output | Notes |
|---------|--------|-------|
| ![Crystal Generator](Icons/ItemsGenerated/CrystalGenerator_Icon.png) Crystal Generator | **256 CF/tick** | Requires crystals + steam + heat >= 100 C |
| ![Solar Panel](Icons/ItemsGenerated/solar_panel_icon.png) Solar Panel | Variable | Daytime only; altitude-boosted (y > 120) |
| ![Fluid Generator](Icons/ItemsGenerated/Fluid_Generator_Icon.png) Fluid Generator | Variable | Lava: 1 CF/mB, Creosote: 2.5 CF/mB |
| ![Dynamo](Icons/ItemsGenerated/Dynamo_icon.png) Dynamo | 50 CF per J | Bridges J network to CF network |

### Wire Tiers

Wires auto-connect to adjacent CF nodes. Upgrade tiers as your power needs grow.

| Tier | Relative Transfer | Crafted From |
|------|------------------|-------------|
| Copper | Low | Copper bars |
| Iron | Medium-Low | Iron bars |
| Thorium | Medium-High | Thorium bars |
| Cobalt | High | Cobalt bars |
| Adamantite | Maximum | Adamantite bars |

> ! Always use the highest tier your system demands — under-tiered wires bottleneck the entire network.

### Storage

| Block | Capacity | Output Rate |
|-------|----------|------------|
| Crystalline Capacitor | 24,000 CF | 1,024 CF/tick |

---

## Conversion

The **Dynamo** converts Joules into CF at **50 CF per Joule**. Place it touching both your gear network and your wire network.

---

## CF Output Comparison

| Source | CF/tick | Relative Scale |
|--------|---------|---------------|
| Dynamo (1 Windmill) | ~2 to 8 | Early game |
| Solar Panel (day) | ~8 | Early game |
| Fluid Generator (Lava) | ~40 | Mid game |
| Crystal Generator | **256** | End game |
