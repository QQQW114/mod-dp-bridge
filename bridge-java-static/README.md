# bridge-java-static

Read-only Java source analysis for Java-based Mindustry mods.

This module parses source text into an AST, inventories top-level Content
constructors, and participates in the conversion pipeline through the
`StaticSourceExporter` service-provider interface. It never compiles, loads,
reflects over, or executes mod classes.

The inventory pass recognizes static fields declared as `Item`, `Liquid`,
`StatusEffect`, `UnitType` (including tank and missile variants), or `Block`.
The concrete constructor may be a built-in or custom subclass; nested weapon,
bullet, effect, and missile-unit constructors are not reported as top-level
Content.

The current production exporter emits Item, Liquid/CellLiquid, StatusEffect,
Unit, and Block root files. Block/Unit object-graph support is still an early
best-effort slice: every omitted expression, builder, callback, or custom field
is reported and the affected Content is marked degraded. Mapping requirements
and the next implementation priorities are documented in
`../docs/JAVA_TO_DP_MAPPING_V1597.md`.
