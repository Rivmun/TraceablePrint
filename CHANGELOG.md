# 1.0.1
Broadcast released for 26.3-neobric (merged fabric/neoforge by Forgix), 26.2-neobric, 26.1-neobric, 1.21.11-neoforge/fabric, 1.21.1-neoforge/fabric, 1.20.1-forge/fabric.
- Fix default hardness gate set too high.
- Fix yOffset will apply to hitbox that leads print will instantly destroy.
- Fix server-side cannot control printSize entirely.
- Improve list access performance.
- Rework the translation key.
- Other small optimization.

# 1.0.0

Initial release.

## Footprint generation

- Living entities (mobs and players) leave footprint **entities** on the ground while walking and at the moment of landing — from an active jump, a fall off a ledge, or knockback; generation runs server-side and is fully synced/persisted with the world.
- Rate limiting: walking prints use a fixed spawn interval plus a minimum-distance gate against the previous footprint, so slow creeping never floods the ground. Landing is detected every tick and bypasses the interval (still gated by minimum distance), so holding jump to bunny-hop no longer drops landings.
- Sprinting shortens both the spawn interval and the minimum-distance gate to two-thirds, keeping the trail dense at speed.
- Sneaking entities never leave footprints; whether invisible entities (Invisibility effect / invisible flag) still print is configurable.
- Block filtering: prints spawn on soft blocks (mining-hardness gate) by default; any block ID or `#block tag` can be whitelisted to bypass the gate.
- Prints spawn behind the walker, oriented to the actual movement direction, with randomized left/right foot and forward/back stagger; positions are baked into the entity so hitbox and interaction follow the visual offset.
- Per-block extra height lift for snow layers, soul sand, mud and similar (ID or tag entries) so prints are not buried by non-full blocks.
- Auto-validity removal: a footprint discards itself when its supporting block is gone or its own block becomes a full cube; explosions destroy footprints, other damage is ignored.
- Self-expiring lifetime (no external cleanup manager): footprints discard after a configurable age, and fade out + sink into the ground over the second half of their life.

## Trail tracing & highlighting

- Each footprint links to its parent entity and the next footprint in the chain; chain pointers are persisted in entity NBT, so trails survive chunk unload/reload and server restarts.
- Right-click a footprint to follow the trail; reaching the true chain tail highlights the parent entity itself. Broken links (expired/destroyed mid-chain) are silently ignored — only a verified tail leads to the parent.
- Highlighting is 100% client-side local state (no network packets, other players unaffected), with an exclusive single-target rule: only one footprint/entity is highlighted at a time per player, re-clicking the same target just refreshes the duration.
- Highlighted footprints render with a pulsing golden shader effect; highlighted living entities get a see-through-blocks glowing outline that persists for as long as the highlight is active.
- A highlighted entity that starts sneaking loses its highlight.
- Footprints are targetable only with an empty main hand while not sneaking; holding any item (or sneaking) makes them fully transparent to the crosshair, so they never block mining/placing/attacking the block under them. A vanilla-style selection outline is drawn on a footprint only while it is actually clickable.
- Direction indicator: the footprint you clicked emits one slow END_ROD particle per second drifting toward the current trace target (next footprint / parent entity), immediately on click with no startup delay; only one emitter is active at a time, in sync with the exclusive highlight rule. Purely client-side, toggleable.

## Trace notifications

- When a trail is traced all the way to an online player, that player receives an action-bar warning ("Someone is tracing your footsteps...") without exposing the tracer's identity; configurable.
- Tracing your own trail (including singleplayer) shows a distinct action-bar hint instead. Both hints are throttled against rapid re-clicking.

## Per-mob customization

- Per-entity texture scale tables (with defaults for vanilla mobs); baby mobs and each entity's own `getScale()` multiply on top. The same multiplier scales side/forward offsets so big mobs get wide-strided prints.
- Per-entity side (lateral) and forward (along movement) offset overrides, shipped with gait presets (horses, spiders, camels, creepers...); defaults hardcoded, overrides take priority.
- Per-entity footprint texture override list (`modid:mobid, name1, name2, ...`): the server picks a random candidate at spawn and syncs the chosen name, so every player sees the same print; missing/unresolvable textures gracefully fall back to the default `footprint.png` per client (validated against loaded resource packs, retried after F3+T reloads, never a missing-texture block).
- Texture names accept plain filenames (resolved under `traceableprint:textures/entity/`), sub-directories, or full `namespace:path` IDs for pack authors; case-insensitive and whitespace-tolerant.

## Filtering & global controls

- Global work mode: footprints for all mobs / players only / disabled.
- Entity list supporting `namespace:path` IDs and `#entity tag` entries, switchable between blacklist (default) and whitelist semantics.
- Block whitelist (IDs and tags) taking priority over the hardness gate.

## Configuration & platform

- In-game config screen (Cloth Config, via Mod Menu on Fabric / mod config button), covering lifetime, spawn interval, minimum distance, highlight duration, direction particles, invisible-entity behavior, hardness gate, block/entity lists and all per-mob tables; every option has reset-to-default.
- Plain JSON config at `config/traceableprint.json`, loaded at startup and auto-saved; unknown/missing keys fall back to defaults for forward compatibility.
- Dedicated-server commands (op level 2): `/traceableprint setEnable off|player|all` to switch the work mode without opening the config screen, and `/traceableprint upload` to pull the executing player's client config onto the server.
- Available for Fabric / NeoForge on MC 1.21.1, 1.21.11, 26.1, 26.2, 26.3, and for Forge / Fabric on 1.20.1.
  - 26.x is single jar for all loaders by Forgix.
- Open the config screen from anywhere in-game with the client command `/traceableprintconfig` — no Mod Menu required, so it also works on NeoForge and in singleplayer.
- If the config library isn't installed, opening the config shows a clear in-game notice screen explaining what's missing instead of erroring out.
- English and Simplified Chinese localizations.
