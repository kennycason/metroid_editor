# Metroid NES Technical Documentation

Authoritative reference docs for the Metroid NES engine, synthesized from all known
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
  the broken room connections + fix plan (area ownership map via flood-fill)

### Context (legacy, see docs/ for updated versions)
- `../context/METROID_MAP_FORMAT.md` — Original map format notes (superseded by ROM_LAYOUT.md)

## Key Facts

1. **Doors have NO destination data** — they're cosmetic + scroll-blocking only
2. **Room connections = world map adjacency** — the 32x32 grid at bank 0 $A53E determines everything
3. **Room numbers are per-area** — room #5 Brinstar != room #5 Norfair
4. **Area transitions = elevators only** — stored in SpecItmsTbl, not door data
5. **Horizontal/vertical alternation** — adjacent rooms MUST alternate scroll direction
6. **Area start positions** — at $95D7/$95D8 in each area bank (banks 1-5)
