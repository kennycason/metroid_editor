# Connect Rooms Bug Analysis

## Problem (Resolved)

Room connections shown in the editor were ~10% correct. The `findRoomNeighbors()` method
returned wrong neighbors for most rooms.

## Root Cause: Area Ownership Cannot Be Derived from ROM

The world map is a shared 32x32 grid used by ALL areas. Room numbers are **per-area** --
room #5 in Brinstar is completely different from room #5 in Norfair. But the world map
stores just raw room numbers with no area tag.

### Why flood-fill fails

All areas are physically adjacent on the map with **no $FF barriers** between them.
Room number ranges overlap completely (all areas have rooms 0x00 through 0x20+).
Any flood-fill approach claims the entire connected map for whichever area processes first.

### How METEdit solves it

METEdit hardcodes a `MapIndex[32*32]` array -- 1024 bytes mapping each cell to its area
(0=Brinstar, 1=Norfair, 2=Tourian, 3=Kraid, 4=Ridley). This data is stored externally,
not read from ROM. Editroid does the same via project files.

### Our solution: Embedded MAP_AREA_INDEX + position tracking

1. **`MAP_AREA_INDEX`** -- Embedded in `MetroidRomData.kt` companion object, sourced from
   METEdit's proven `MapIndex` array. Used by `areaAt(mapX, mapY)` to determine which
   area owns any map cell. This is the ONLY reliable way to determine area ownership.

2. **`findRoomNeighbors(area, roomNum, hintMapX, hintMapY)`** -- When navigating via D-pad,
   uses exact position. For initial room selection (from room list), filters by
   `MAP_AREA_INDEX` to find the correct instance of the room in the right area.

3. **`NeighborCell(roomNumber, mapX, mapY)`** -- neighbors carry their exact map position.
   D-pad clicks pass (mapX, mapY) through `EditorState.currentMapPos` for subsequent lookups.

## Fix History

1. **v1 (broken)**: Proximity to area start, no area ownership. Failed because room $21
   appears in 4 areas and proximity picks the wrong one.

2. **v2 (broken)**: Flood-fill area ownership. Failed because areas share map edges with
   no $FF barriers -- first area claims all 511 cells.

3. **v3 (current)**: Embedded MAP_AREA_INDEX from METEdit + D-pad position tracking.
   Verified with 12 unit tests against known map data from m1map_room_ids.png.

## Remaining Limitations

- **ROM hacks with different area layouts**: MAP_AREA_INDEX would need updating. Could be
  stored in project files like Editroid does.
- **Room reuse at multiple positions in same area**: picks closest to area start for
  initial selection; once navigating via D-pad, always correct.
