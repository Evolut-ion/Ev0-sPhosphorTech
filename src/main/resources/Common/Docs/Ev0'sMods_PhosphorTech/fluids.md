---
name: Fluids
description: Fluid types, pipes, tanks, and heat simulation
sort-index: 3
---
# Fluid System

PhosphorTech uses a push-based fluid system. Fluid sources push into adjacent pipes each tick, which route fluid to tanks or machine inputs. Pipes carry **temperature metadata** enabling heat-aware processing.

---

## Fluid Types

| Fluid | Primary Source | Used By |
|-------|---------------|---------|
| Water | World / Crystal Generator byproduct | Steam Generator, Solar Boiler |
| Steam | Steam Generator / Solar Boiler | Crystal Generator |
| Lava | World | Fluid Generator |
| Tar | Processing byproduct | Specialty crafting |
| Sap | Sieve output | Specialty crafting |
| Creosote | Coke Oven byproduct | Fluid Generator |

---

## Pipe Tiers

| Pipe | Transfer Rate | Allowed Fluids |
|------|-------------|---------------|
| Clay Pipe | 50 mB/tick | Water, Sap |
| Copper Pipe | 100 mB/tick | Water, Sap, Creosote |
| Potin Pipe | 150 mB/tick | All fluids |

> ! Use Clay Pipes for basic water loops. Upgrade to Copper or Potin when introducing Creosote or Lava.

---

## Fluid Progression

| Goal | Setup |
|------|-------|
| Feed Crystal Generator | World Water > Pipe > Steam Generator > Steam > Crystal Generator |
| Generate CF from Lava | Lava > Potin Pipe > Fluid Generator > CF |
| Generate CF from Creosote | Coke Oven > Copper Pipe > Fluid Generator > CF |
| Buffer steam | Steam Generator > Pipe > Steam Reservoir > Crystal Generator |

---

## Storage

| Block | Capacity | Notes |
|-------|----------|-------|
| ![Generic Fluid Tank](Icons/ItemsGenerated/WaterTank_Icon.png) Generic Fluid Tank | 10,000 mB | Any single fluid type |
| ![Steam Reservoir](Icons/ItemsGenerated/Steam_Reservoir_Icon.png) Steam Reservoir | 10,000 mB | Dedicated steam buffer |
| Pump internal buffer | 5,000 mB | World fluid extraction |

---

## Heat System

Thermal blocks output heat in degrees C to adjacent connections each tick.

| Source | Type | Notes |
|--------|------|-------|
| ![Solar Boiler](Icons/ItemsGenerated/solar_boiler_icon.png) Solar Boiler | Passive | Active daytime only, y > 120 |
| ![Mechanical Heater](Icons/ItemsGenerated/mechanical_heater_icon.png) Mechanical Heater | J to Heat | Scales with shaft speed |
| ![Powered Heater](Icons/ItemsGenerated/Powered_Heater_Icon.png) Powered Heater | CF to Heat | 500 CF/tick continuous |

**Heat thresholds:**

| Temperature | Effect |
|------------|--------|
| Below 100 C | Steam Generator idle — no output |
| 100 C or above | Steam production active |
| Higher temps | Coke Oven produces bonus Creosote |
