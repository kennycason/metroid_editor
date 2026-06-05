# Metroid NES Reference

Canonical reference folder for the Metroid NES editor. These docs are synthesized from known
reference codebases: metroid-disassembly, m1disasm, metroid_source_code_expanded,
MetroidMMC3, Editroid (C#), and METEdit (C++).

## Documents

### Core References
- [ROM_LAYOUT.md](ROM_LAYOUT.md) — ROM banks, area pointer tables, room/struct/macro formats,
  collision system, special items, CHR loading, engine limits
- [ROOM_NAVIGATION.md](ROOM_NAVIGATION.md) — Room connections, world map, scrolling,
  door data format, door scroll mechanism, elevators, area transitions, complete navigation flow

### Editor-Specific
- [CONNECT_ROOMS_BUG_ANALYSIS.md](CONNECT_ROOMS_BUG_ANALYSIS.md) — Root cause analysis of
  the broken room connections + final area ownership map fix
- [EXPORT_BUG_INVESTIGATION.md](EXPORT_BUG_INVESTIGATION.md) — History of export corruption
  issues and the fixes/constraints found while debugging
- [tile_memory_strategy_ideas.md](tile_memory_strategy_ideas.md) — Strategy notes for
  re-encoding, ROM expansion, and tile budget tradeoffs

### Historical Context
- [CONTEXT.md](CONTEXT.md) — External local reference locations and useful links
- [METROID_MAP_FORMAT.md](METROID_MAP_FORMAT.md) — Original map format notes, mostly
  superseded by [ROM_LAYOUT.md](ROM_LAYOUT.md)

### Image References
- [m1map.png](m1map.png) — Full-game map reference
- [m1map_room_ids.png](m1map_room_ids.png) — 32x32 world map room ID reference
- [m1room_tile_layout.png](m1room_tile_layout.png) — Room tile layout reference

## Key Facts

1. **Doors have NO destination data** — they're cosmetic + scroll-blocking only
2. **Room connections = world map adjacency** — the 32x32 grid at bank 0 $A53E determines everything
3. **Room numbers are per-area** — room #5 Brinstar != room #5 Norfair
4. **Area transitions = elevators only** — stored in SpecItmsTbl, not door data
5. **Horizontal/vertical alternation** — adjacent rooms MUST alternate scroll direction
6. **Area start positions** — at $95D7/$95D8 in each area bank (banks 1-5)
