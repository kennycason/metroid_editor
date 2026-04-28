# Metroid NES Room Navigation & Connections — Complete Reference

Synthesized from: metroid-disassembly, m1disasm, metroid_source_code_expanded, MetroidMMC3,
Editroid (C#), METEdit (C++).

---

## 1. Core Architecture: How Rooms Connect

**The single most important fact:** Metroid NES has **no destination data in doors**. Room
connections are determined entirely by the **32x32 world map grid**. When Samus scrolls off
one screen, the engine looks up the adjacent cell in the world map to find the next room number.

```
World Map Grid (32x32, 1024 bytes)
+----+----+----+----+
| 03 | 04 | 05 | FF |   Each cell = 1 byte = room number
+----+----+----+----+   $FF = empty/impassable
| 02 | FF | 06 | FF |   Adjacent valid cells = connected rooms
+----+----+----+----+
```

Doors are purely **cosmetic + scroll-blocking** — they control:
- Visual appearance (left/right, red/blue/missile)
- Whether scrolling is blocked until Samus walks through

They do NOT encode destinations, target rooms, or area transitions.

---

## 2. World Map

### Storage
- **ROM Location:** Bank 0 (title bank), CPU address `$A53E`
- **RAM Copy:** `$7000-$73FF` (WorldMapRAM), copied by `CopyMap` at init
- **Size:** 32 columns x 32 rows = 1024 bytes
- **Format:** Linear byte array: `grid[y * 32 + x]`
- **Shared:** All 5 areas use the same world map — different regions correspond to different areas

### Room Lookup Formula (GetRoomNum, `$E720`)
```
address = (MapPosY * 32) + MapPosX + $7000
room_number = byte at address
```

The engine calculates `MapPosY * 32` as: `Amul16(MapPosY)` then `ROL` (i.e., *16 then *2).

### Area Regions on the World Map

Each area occupies a distinct region. Start positions per area (from `$95D7`/`$95D8` in each area bank):

| Area     | Bank | Start X | Start Y | Map Region           |
|----------|------|---------|---------|----------------------|
| Brinstar | 1    | 3       | 14      | Left-center          |
| Norfair  | 2    | 22      | 13      | Right-center         |
| Tourian  | 3    | 3       | 4       | Upper-left           |
| Kraid    | 4    | 7       | 20      | Lower-left           |
| Ridley   | 5    | 25      | 24      | Lower-right          |

**Critical:** Room numbers are **per-area**, not global. Room #5 in Brinstar is a completely
different room from room #5 in Norfair. The world map stores room numbers without area tags —
the engine knows which area it's in via `InArea` ($74) and uses the corresponding area bank.

### Area Ownership — The Unsolvable Problem

The world map does NOT tag cells with area IDs. The game tracks the current area in
`InArea` and only changes it via elevator transitions. When scrolling, the engine always
loads rooms from the current area bank, so it implicitly assumes all adjacent cells belong
to the same area.

**This cannot be reliably derived from ROM data.** The areas are physically adjacent on
the grid with no $FF barriers between them, and room number ranges overlap completely
(all areas have rooms 0x00-0x2E). Flood-fill from area starts claims the entire connected
map for whichever area is processed first.

**METEdit's solution:** Hardcoded `MapIndex[32*32]` array — a 1024-byte lookup table
mapping each cell to its area (0=Brinstar, 1=Norfair, 2=Tourian, 3=Kraid, 4=Ridley).
This data is stored/loaded externally, NOT read from ROM. Editroid does the same.

**Our editor's approach:** Track map position through navigation. When users navigate
via the D-pad, each click passes the exact (mapX, mapY) of the destination cell.
Neighbors are determined purely by world map adjacency — no area ownership needed.
For initial room selection (from the room list), proximity to the area's start
position is used as a best-effort heuristic.

**From SnowBro (METEdit author):** "The same room can be used multiple times on the map.
You've probably noticed that many of the places in Metroid look very similar. Well, now
you know the reason: technically, they are the same place!"

---

## 3. Scrolling & Room Transitions

### ScrollDir ($49) — Scroll Direction Register
```
$00 = Up    (vertical scrolling)
$01 = Down  (vertical scrolling)
$02 = Left  (horizontal scrolling)
$03 = Right (horizontal scrolling)
```

Bit 1 distinguishes vertical (0) from horizontal (1). ToggleScroll (`$E252`) flips with `EOR #$03`.

### How Scrolling Loads New Rooms

When the player scrolls to a screen boundary:

1. **ScrollLeft/Right/Up/Down** updates `MapPosX`/`MapPosY`
2. Calls `GetRoomNum` to look up the room at the new map position
3. If room = `$FF` → can't scroll, undo map position change, return carry set
4. If valid → store in `RoomNumber` ($5A), call `SetupRoom` to load room data
5. Room data is rendered into the alternate nametable buffer (double-buffered)

**Scroll routines (Bank07.asm):**

| Routine     | Address | Action                                          |
|-------------|---------|------------------------------------------------|
| ScrollUp    | $E4F1   | Dec MapPosY, GetRoomNum, load room above        |
| ScrollDown  | $E517   | Inc MapPosY, GetRoomNum, load room below        |
| ScrollLeft  | $E6A7   | Dec MapPosX, GetRoomNum, load room left         |
| ScrollRight | $E6D2   | Inc MapPosX, GetRoomNum, load room right        |

### Nametable Double-Buffering

Two room RAM buffers render alternately:

| Buffer   | Address       | Size       |
|----------|---------------|------------|
| RoomRAMA | $6000-$63FF   | 1024 bytes |
| RoomRAMB | $6400-$67FF   | 1024 bytes |

New rooms are drawn into the inactive buffer while the current room is displayed.
`CartRAMPtr` ($39-$3A) points to the active buffer ($60 or $64 high byte).

### PPU Mirroring

- **Horizontal mirroring** ($27): For left/right scrolling rooms
- **Vertical mirroring** ($2F): For up/down scrolling rooms
- Controlled by `MirrorCntrl` variable via PPUCTRL register

---

## 4. Door Data Format

### Location in Room Data

Doors are in the enemy/door section after the `$FD` terminator:

```
Room data layout:
  [palette byte]           — 1 byte
  [objects...]             — 3 bytes each (posYX, structIdx, pal)
  $FD                      — end of objects marker
  [enemy/door entries...]  — variable size, parsed by type
  $FF                      — end of room data
```

### Entry Type Dispatch (after $FD)

Each entry's low nibble determines the handler:

| Low Nibble | Type     | Size    | Handler      |
|------------|----------|---------|--------------|
| 0          | (empty)  | 0 bytes | ExitSub      |
| 1          | Enemy    | 3 bytes | LoadEnemy    |
| **2**      | **Door** | **2 bytes** | **LoadDoor** |
| 4          | Elevator | 2 bytes | LoadElevator |
| 6          | Statues  | 1 byte  | LoadStatues  |
| 7          | Zeb hole | 3 bytes | ZebHole      |

### Door Entry (2 bytes)

```
Byte 0: [pppp 0010]
         pppp = upper nibble (position/flags, largely unused for doors)
         0010 = type nibble = 2 (identifies as door)

Byte 1: [SSSS SSpp]  (door info byte)
         S bits = side/slot info
         pp = palette (bits 0-1)

  Amul16 extracts bit 4 into carry:
    CF=0 → RIGHT door (placed at X=$F0)
    CF=1 → LEFT door  (placed at X=$10)
    Y position is always $68 (104 pixels)
```

### Door Types (from Editroid's proven parsing)

```
Bits 0-1 of door info byte = palette/type:
  $00 = Red/missile door (5 missiles to open)
  $01 = Blue/normal door (any weapon opens)
  $02 = 10-missile door
  $03 = Music-change door (Tourian)

Bits 4+ = side determination:
  Upper nibble $A0 → Right door
  Upper nibble $B0 → Left door
```

### Concrete Examples from Brinstar (Bank01.asm)

```asm
; Room 00 enemy/door data (after $FD):
.byte $02, $A1    ; Door: byte0=$02 (type=door), byte1=$A1 (right, blue)
.byte $02, $B1    ; Door: byte0=$02 (type=door), byte1=$B1 (left, blue)
.byte $FF         ; End of room data
```

### What Doors Actually Do at Runtime

1. **LoadDoor** (`$EB92`): Creates door sprite objects at fixed screen positions
2. **Sets scroll-blocking flags** in `DoorOnNameTable0/3` ($6C/$6D):
   - `$01` = Left door blocks left scroll
   - `$02` = Right door blocks right scroll
   - `$03` = Both doors on same nametable
3. **GetRoomNum checks these flags** before allowing horizontal scrolling
4. When Samus collides with door tiles ($A0/$A1), `SamusEnterDoor` triggers
5. Door opens, Samus walks through, scroll blocking is cleared
6. Scrolling resumes to the adjacent room from the world map

**Doors do NOT determine which room is next** — they only gate access to the
adjacent room that's already defined by the world map grid.

---

## 5. Door Scroll Mechanism

### Door Status Machine

`DoorStatus` ($56) tracks Samus's door transition state:

| Value | State              | Description                                    |
|-------|--------------------|------------------------------------------------|
| $00   | No door            | Normal gameplay                                |
| $01   | Right door entered | Scrolling right through door                   |
| $02   | Left door entered  | Scrolling left through door                    |
| $03   | Vertical centering | Centering room vertically (shaft entry)         |
| $04   | Vertical centered  | Room centered, ready to toggle scroll           |
| $05   | Door exit          | Samus exiting door on other side                |
| $80+  | MSB set            | Door entered flag (combined with lower states)  |

### DoorScrollStatus ($57) — Entry Type

| Value | Meaning                                                    |
|-------|-----------------------------------------------------------|
| $01   | Entered RIGHT door from HORIZONTAL area                    |
| $02   | Entered LEFT door from HORIZONTAL area                     |
| $03   | Entered door from VERTICAL shaft, room needs centering     |
| $04   | Entered door from VERTICAL shaft, room already centered    |

### SamusDoorData ($58) — Scroll Toggle Flags

```
Upper nibble (bits 4-5):
  Bit 4 set ($10): Toggle scrolling direction after door
  Bit 5 set ($20): Force horizontal scrolling (item rooms)

Lower nibble:
  Samus action state (preserved through door transition)
```

### Door Tile Collision

Door tiles in the CHR data trigger `SamusDoorData`:
- Tile `$A0` → Toggle-scroll door (switches horizontal/vertical)
- Tile `$A1` → Horizontal-scroll door (enters from vertical shaft)

### Horizontal/Vertical Alternation Rule

**Adjacent rooms MUST alternate between horizontal and vertical scrolling.**
This is fundamental to Metroid's engine design:

```
[Horizontal Room] ←door→ [Vertical Shaft] ←door→ [Horizontal Room]
   ScrollDir=2/3            ScrollDir=0/1           ScrollDir=2/3
```

When Samus walks through a door, `ToggleScroll` (`$E252`) flips ScrollDir:
```asm
ToggleScroll:
  LDA ScrollDir
  EOR #$03          ; 0↔3, 1↔2 — flips between up/down and left/right
  STA ScrollDir
  LDA MirrorCntrl
  EOR #$08          ; Toggle PPU mirroring mode
  STA MirrorCntrl
```

Exception: Item rooms (entered via special items) don't toggle — they continue
horizontal scrolling. Controlled by bit 5 of `SamusDoorData`.

---

## 6. Elevator Handling

### Elevator Data Format

In the enemy/door section after $FD:
```
Byte 0: [eeee 0100]
         eeee = upper nibble (position info)
         0100 = type nibble = 4 (elevator)

Byte 1: Elevator type/direction byte
         Bit 7: Direction (1=UP, 0=DOWN)
         Other bits: additional flags
```

### Elevator Position
- Always centered: X = $80 (128 pixels), Y = $83 (131 pixels)
- Fixed position — cannot be moved within a room

### Area Transitions via Elevators

**Elevators are the ONLY mechanism for changing areas.** They are stored in the
**Special Items Table** (SpecItmsTbl), NOT in the room data door section.

Elevator destinations (from Editroid):
```
$00 = Brinstar to Brinstar (internal)
$01 = Brinstar to Norfair
$02 = Brinstar to Kraid
$03 = Brinstar to Tourian
$04 = Norfair to Ridley
$81 = Norfair exit (to Brinstar)
$82 = Kraid exit (to Brinstar)
$83 = Tourian exit (to Brinstar)
$84 = Ridley exit (to Norfair)
$8F = End of game
```

When an elevator triggers an area transition:
1. New area bank is swapped in
2. `MapPosX`/`MapPosY` are loaded from the new area's start position ($95D7/$95D8)
3. `InArea` ($74) is updated
4. `ScrollDir` is set (Brinstar starts horizontal/left, others start vertical/down)
5. Room is loaded from the new area's room pointer table

---

## 7. Special Rooms and Item Room Music

Each area bank contains a 7-byte table at `$95D0` listing "special room" numbers
that trigger item room music:

```asm
; Brinstar ($95D0): $2B, $2C, $28, $0B, $1C, $0A, $1A
; Norfair  ($95D0): $10, $05, $27, $04, $0F, $FF, $FF
; Tourian  ($95D0): $FF, $FF, $FF, $FF, $FF, $FF, $FF  (none)
; Kraid    ($95D0): $FF, $FF, $FF, $FF, $FF, $FF, $FF  (none)
; Ridley   ($95D0): $FF, $FF, $FF, $FF, $FF, $FF, $FF  (none)
```

`GetRoomNum` checks the loaded room number against this table. If it matches,
`ItemRmMusicSts` ($79) is set to $01 to start item room music.

---

## 8. Area Initialization Sequence

When entering an area (via elevator or game start):

1. `AreaInit` ($C801): Clear scroll registers, set starting nametable
2. `MoreInit` ($C81D): Clear RAM, load area-specific pointers
3. `CopyPtrs` ($C801): Copy 7 pointers from $9598+2 to ZP ($3B-$48)
4. `ScrollDir` set to 2 (left) for Brinstar, 1 (down) for other areas
5. `MapPosX` ← `$95D7`, `MapPosY` ← `$95D8` (area start position)
6. `GetRoomNum`: Look up room at start position
7. `SetupRoom` → `DrawRoom`: Load and render starting room
8. `SamusInit` ($C8D1): Place Samus at `$95D9` (vertical start position), X=$80

### Area Start Positions in ROM

Located at CPU `$95D7`-`$95D9` in each area bank:

| Offset  | Content                | Example (Brinstar) |
|---------|------------------------|---------------------|
| $95D7   | Start X on world map   | $03                 |
| $95D8   | Start Y on world map   | $0E (14)            |
| $95D9   | Samus Y screen position| $B0                 |

---

## 9. GetRoomNum — Complete Logic

```
GetRoomNum ($E720):
  1. Check ScrollDir:
     - If horizontal (bit 1 set):
       a. Calculate door-blocking check: A = $01 (left) or $02 (right)
       b. Check DoorOnNameTable0/3 for current nametable
       c. If door blocks → return carry SET (can't scroll)
     - If vertical: skip door check

  2. Calculate map address:
     address = (MapPosY * 32) + MapPosX + $7000

  3. Load room number from world map:
     - If $FF → return carry SET (invalid room)
     - Otherwise → store in RoomNumber ($5A)

  4. Check special room table ($95D0, 7 entries):
     - If match → set ItemRmMusicSts = $01
     - If no match and was in item room → set ItemRmMusicSts = $80

  5. Return carry CLEAR (success)
```

---

## 10. Key RAM Variables Reference

| Address | Name                | Description                                |
|---------|---------------------|--------------------------------------------|
| $49     | ScrollDir           | 0=Up, 1=Down, 2=Left, 3=Right             |
| $4A     | TempScrollDir       | Snapshot of ScrollDir at room load         |
| $4E     | SamusDoorDir        | Direction through door (0=right, 1=left)   |
| $4F     | MapPosY             | World map Y coordinate (0-31)              |
| $50     | MapPosX             | World map X coordinate (0-31)              |
| $56     | DoorStatus          | Door transition state machine              |
| $57     | DoorScrollStatus    | Door entry type ($01-$04)                  |
| $58     | SamusDoorData       | Scroll toggle flags + action state         |
| $59     | DoorDelay           | Frame counter for door animation           |
| $5A     | RoomNumber          | Current room number being loaded           |
| $6C     | DoorOnNameTable3    | Door presence flags for nametable 3        |
| $6D     | DoorOnNameTable0    | Door presence flags for nametable 0        |
| $74     | InArea              | Current area ($10=Brin, $11=Nor, etc.)     |
| $79     | ItemRmMusicSts      | Item room music status                     |
| $FC     | ScrollY             | PPU Y scroll offset (0-239)                |
| $FD     | ScrollX             | PPU X scroll offset (0-255)                |

---

## 11. Door Sprite Slots

Doors use sprite object slots $80-$B0. Slot assignment is based on map position:

```asm
; Slot selection: index = ((MapPosX + MapPosY) ROL doorSide) AND $03
DoorSlots:
  .byte $80    ; Index 0
  .byte $B0    ; Index 1
  .byte $A0    ; Index 2
  .byte $90    ; Index 3

DoorXs:
  .byte $F0    ; Right door X coordinate (240px, right edge)
  .byte $10    ; Left door X coordinate (16px, left edge)

; Door Y coordinate is always $68 (104 pixels)
```

---

## 12. Complete Room Navigation Flow

```
Player pushes right on D-pad
  → Engine calls ScrollRight ($E6D2)
    → Checks: is ScrollDir horizontal? (bit 1 set)
    → If scrolling right (ScrollDir=3): Inc MapPosX
    → Calls GetRoomNum
      → Checks DoorOnNameTable for blocking doors
        → If door blocks: return carry SET, undo MapPosX++
      → Calculates world map address
      → Loads room number at (MapPosX, MapPosY)
        → If $FF: return carry SET, undo MapPosX++
      → Stores RoomNumber
      → return carry CLEAR
    → Calls SetupRoom → DrawRoom
      → Parses room data: objects, then $FD, then enemies/doors
      → LoadDoor: places door sprites, sets DoorOnNameTable flags
    → Room rendered into alternate nametable buffer
    → PPU scrolling advances, new room slides in

Player walks into door
  → SamusEnterDoor detects door tile collision ($A0 or $A1)
  → Sets DoorStatus, SamusDoorData
  → Door opens (animation frames)
  → DoorOnNameTable flags cleared
  → ToggleScroll: ScrollDir EOR #$03
  → Samus walks through
  → New room scrolls in from world map
  → Door closes behind Samus
```
