# Connect Rooms Bug Analysis

## Problem

Room connections shown in the editor were ~10% correct. The `findRoomNeighbors()` method
in `MetroidRomData.kt` returned wrong neighbors for most rooms.

## Root Cause: Missing Area Ownership Map

The world map is a shared 32x32 grid used by ALL areas. Room numbers are **per-area** --
room #5 in Brinstar is completely different from room #5 in Norfair. But the world map
stores just raw room numbers with no area tag.

The engine handles this implicitly: it always loads rooms from the currently-swapped area
bank, so it never confuses room #5 in Brinstar with room #5 in Norfair. The engine only
changes areas via elevators.

## Fix Applied (April 2026)

### `buildAreaOwnership()` -- New Method

Flood-fills from each area's start position ($95D7/$95D8) across valid (non-$FF) world
map cells. Each cell gets tagged with its owning `Area`. First area to claim a cell wins
(BFS order). This mirrors METEdit's `MapIndex` array concept.

### Updated `findRoomNeighbors()`

1. **Room search filters by area ownership** -- only considers cells owned by the correct
   area, eliminating cross-area collisions
2. **`validAt()` filters by area ownership** -- adjacent cells in different areas are no
   longer returned as neighbors

### UI: D-pad Grid Layout

Replaced the flat row of clickable room chips with a 3x3 D-pad grid layout in
`RoomInfoPanel.kt`. Current room shown in center, neighbors shown at their spatial
positions (up/down/left/right). Each neighbor is clickable for navigation.

## Remaining Edge Cases

1. **Elevator cells** -- claimed by whichever area floods first. Elevators connect areas
   but aren't scroll-accessible neighbors.

2. **Disconnected regions** -- item rooms accessed only via special items may not be
   reachable by flood-fill from the area start. These would show no neighbors.

3. **Room reuse at multiple positions** -- same room number at multiple map cells within
   one area. Currently picks closest to area start; ideally should track which map
   position the user navigated to.
