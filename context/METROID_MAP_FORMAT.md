# Metroid NES Map Data Format — Complete Reference

Synthesized from: metroid-disassembly, editroid (C#), metroid_source_code_expanded, MetroidMMC3 port.

---

## 1. ROM Layout & Bank Structure

Metroid NES is 128KB PRG ROM using MMC1 mapper. Banks are 16KB each ($4000 bytes).

| Bank | Contents | CPU Window |
|------|----------|------------|
| 0 | Title screen, world map | $8000-$BFFF (switchable) |
| 1 | Brinstar area data | $8000-$BFFF (switchable) |
| 2 | Norfair area data | $8000-$BFFF (switchable) |
| 3 | Tourian area data | $8000-$BFFF (switchable) |
| 4 | Kraid area data | $8000-$BFFF (switchable) |
| 5 | Ridley area data | $8000-$BFFF (switchable) |
| 6 | Graphics data | $8000-$BFFF (switchable) |
| 7 | Engine (fixed bank) | $C000-$FFFF (always mapped) |

Each area bank (1-5) contains ALL map data for that area: room data, structures, macros, special items, enemy tables.

---

## 2. Area Pointer Table ($9598)

Located at CPU $9598 in each area bank. Eight 16-bit (little-endian) pointers:

| Offset | ZP Dest | Label | Description |
|--------|---------|-------|-------------|
| +0 | (none) | SpecItmsTbl | Special items linked list — NOT copied to ZP |
| +2 | $3B-$3C | RoomPtrTable | Room pointer table |
| +4 | $3D-$3E | StructPtrTable | Structure pointer table |
| +6 | $3F-$40 | MacroPtr | Macro definitions (2x2 tiles) |
| +8 | $41-$42 | EnemyFramePtrTbl1 | Enemy frame pointers |
| +10 | $43-$44 | EnemyFramePtrTbl2 | Enemy frame pointers |
| +12 | $45-$46 | EnemyPlacePtrTbl | Enemy placement pointers |
| +14 | $47-$48 | EnemyAnimIndexTbl | Enemy animation indices |

### CopyPtrs Routine (Bank 7)

```assembly
CopyPtrs:
    ldx #$0D            ; 14 bytes = 7 words (entries 1-7)
*   lda AreaPointers+2,x ; skip entry 0 (SpecItmsTbl)
    sta RoomPtrTable,x
    dex
    bpl -
    rts
```

**Critical**: Entry 0 (SpecItmsTbl) is NOT copied to zero page. The engine reads it directly from ROM at $9598 via `ScanForItems`. This means the SpecItmsTbl pointer must remain at a fixed ROM location ($9598), while entries 1-7 can be read from zero page after CopyPtrs runs.

---

## 3. Bank Data Layout (Contiguous Order)

Within each area bank, data is laid out contiguously:

```
$9598: AreaPointers table (16 bytes)
  ...
RoomPtrTable:    [word] × N rooms      (pointers to room data)
StructPtrTable:  [word] × M structs    (pointers to struct data)
SpecItmsTbl:     linked list entries   (variable size)
  ... room data, struct data ...       (variable, referenced by pointers)
MacroDefs:       [4 bytes] × K macros  (extends to end of usable area)
```

**Key insight**: RoomPtrTable, StructPtrTable, and SpecItmsTbl are contiguous with no gaps. Room count = (StructPtrTable - RoomPtrTable) / 2.

---

## 4. Room Data Format

Each room is a variable-length byte sequence:

```
[palette]                           — 1 byte: attribute table palette index
[object₀: posYX, structIdx, pal]   — 3 bytes per object
[object₁: posYX, structIdx, pal]
...
[terminator]                        — $FD (has enemies/doors) or $FF (no enemies)
[enemy/door data...]               — only if terminated by $FD
[0xFF]                              — end of all room data
```

### Object Format (3 bytes)

| Byte | Bits | Field | Range |
|------|------|-------|-------|
| 0 | 7-4 | posY | 0-15 (× $40 in room RAM) |
| 0 | 3-0 | posX | 0-15 (× 2 in room RAM) |
| 1 | 7-0 | structIndex | 0-255 (index into StructPtrTable) |
| 2 | 1-0 | palette | 0-3 (attribute table bits) |

Special object bytes:
- `$FE` = empty placeholder (skip, no struct)
- `$FD` = end of objects, enemy/door section follows
- `$FF` = end of room data (no enemy/door section)

### Enemy/Door Section

After `$FD`, entries are parsed by their low nibble:

| Low nibble | Type | Size | Format |
|-----------|------|------|--------|
| 1 | Enemy | 3 bytes | [slot<<4 \| 1] [type] [posYX] |
| 2 | Door | 2 bytes | [info] [doorData] |
| 4 | Elevator | 2 bytes | [info] [elevData] |
| 6 | Statues | 1 byte | [marker] |
| 7 | Zeb hole | 3 bytes | [marker] [type] [posYX] |

Terminated by `$FF`.

### Room Size Calculation
```
size = 1 (palette) + 3×objects + 1 (terminator)
     + enemy/door data size + 1 ($FF terminator, if enemy section present)
```

---

## 5. Structure Format

Each structure defines a rectangular tile pattern. Referenced by index via StructPtrTable.

```
[row₀ header] [macro indices...]
[row₁ header] [macro indices...]
...
[$FF]   — end of structure
```

### Row Header Byte

| Bits | Field | Description |
|------|-------|-------------|
| 7-4 | xOffset | Horizontal offset (0-15) added to object's X position |
| 3-0 | count | Number of macros in row (1-15; 0 means 16) |

After the header, `count` bytes follow — each is a macro index.

### Structure Rendering (DrawStruct)

For each row:
1. Read header byte; if `$FF`, done
2. Extract xOffset (upper nibble) and count (lower nibble, 0→16)
3. Calculate room RAM position: base + (xOffset × 2)
4. For each macro index in the row, call DrawMacro
5. Advance Y position by $40 (one row of 16-pixel-tall tiles in room RAM)

---

## 6. Macro Format (2×2 Tile Blocks)

Each macro is 4 bytes at `MacroPtr + (macroIndex × 4)`:

| Offset | Position | PPU Nametable Offset |
|--------|----------|---------------------|
| +0 | Top-left | +$00 |
| +1 | Top-right | +$01 |
| +2 | Bottom-left | +$20 |
| +3 | Bottom-right | +$21 |

Each byte is a CHR tile index (0-255) into the background pattern table (PPU $1000-$1FFF).

### DrawMacro

```assembly
; macro_addr = MacroPtr + (macroNumber × 4)
; Writes 4 tile indices to room RAM:
;   [base+$00] = byte 0 (TL)
;   [base+$01] = byte 1 (TR)
;   [base+$20] = byte 2 (BL)
;   [base+$21] = byte 3 (BR)
```

The TilePosTable `[$21, $20, $01, $00]` is read in reverse (X=3→0), so bytes are written in order TL, TR, BL, BR.

---

## 7. Collision System

The engine checks tile indices stored in room RAM directly — no separate collision map. The collision routine `LD651` is **area-specific**:

```assembly
LD651:  ldy InArea
        cpy #$10        ; Tourian?
        beq +           ; If Tourian, skip to cmp #$80
        cmp #$70        ; Non-Tourian: tile < $70?
        bcs ++          ; If tile >= $70, return with CF=1 (passable)
*       cmp #$80        ; Tourian path (or non-Tourian tile < $70 fallthrough)
*       rts
```

**Three-tier collision:**

| Tile Range | Non-Tourian | Tourian |
|-----------|-------------|---------|
| < $70 | Solid | Solid |
| $70-$7F | Passable/Blastable | Solid |
| $80-$9F | Passable/Blastable | Blastable |
| ≥ $A0 | Walkable | Walkable |

After `LD651` returns, the caller further classifies passable tiles:
- `$70/$80 to $9F`: routed to `IsBlastTile` (destructible by weapons)
- `≥ $A0`: routed to `IsWalkableTile` (fully passable)

The choice of CHR tile indices in macros directly determines collision behavior.

---

## 8. Special Items Table (SpecItmsTbl)

A singly-linked list stored in the area bank. Entry 0 of the area pointer table points to the first node.

### Y-Node Format (linked list between Y coordinates)

```
Byte 0:    MapY position
Byte 1-2:  Absolute CPU pointer to next Y-node (little-endian)
           $FFFF = end of list (no more Y-nodes)
Byte 3+:   X-entries for this Y coordinate (variable length)
```

### X-Entry Format (within a Y-node)

```
Byte 0:    MapX position
Byte 1:    Relative offset to next X-entry's MapX byte
           $FF = no more X-entries for this Y
Byte 2:    Item type byte (lower nibble = handler index)
Byte 3+:   Type-specific data (variable length per handler)
```

### ScanForItems Algorithm (verified from assembly)

1. Load pointer from `SpecItmsTable` at ROM $9598 (NOT zero page — CopyPtrs skips entry 0)
2. **ScanOneItem**: Read MapY at offset 0; compare to MapPosY
   - Y < current → read "next Y" pointer at bytes 1-2, follow it (unless $FFFF → exit)
   - Y > current → exit (list is sorted by Y ascending)
   - Y matches → add 3 to pointer → go to ScanItemX
3. **ScanItemX**: Read MapX at offset 0; compare to MapPosX
   - X matches → add 2 to pointer → read type byte → dispatch to handler
   - X > current → exit (entries sorted by X ascending within Y)
   - X < current → read byte at offset 1 via **AnotherItem**
     - If $FF → no more X entries, exit
     - Otherwise → add offset to pointer → loop back to ScanItemX

### Item Type Handlers (lower nibble of type byte)

| Index | Handler | Bytes consumed (incl. type) |
|-------|---------|-----------|
| 0 | ExitSub (empty) | 0 |
| 1 | SqueeptHandler | 3 (calls GetEnemyData) |
| 2 | PowerUpHandler | 3 |
| 3 | SpecEnemyHandler | 3 |
| 4 | ElevatorHandler | 2 |
| 5 | CannonHandler | 2 |
| 6 | MotherBrainHandler | varies |
| 7 | ZeebetiteHandler | varies |
| 8 | RinkaHandler | 1 |
| 9 | DoorHandler | 2 |
| A | PaletteHandler | varies |

Handlers return with A = byte count to advance, then loop back to ChooseHandlerRoutine
for the next item type byte (multiple items can exist at the same X,Y).

### Critical Encoder Concern

The linked list contains two kinds of pointers:
1. **Absolute CPU addresses** (bytes 1-2 of each Y-node) — these MUST be delta-adjusted when relocating the table
2. **Relative offsets** (byte 1 of each X-entry) — these do NOT need adjustment since they're relative

When relocating specItemsTable during export, walk the Y-node chain and adjust only the "next Y" pointers. Failure to adjust breaks the linked list traversal at runtime.

---

## 9. Room RAM / Nametable Buffers

The engine double-buffers room rendering:

| Buffer | Address Range | Size |
|--------|--------------|------|
| RoomRAMA | $6000-$63FF | 1024 bytes |
| RoomRAMB | $6400-$67FF | 1024 bytes |

### Buffer Layout

```
$6000-$639F: Nametable tile data (32 bytes/row × 29 tile rows = 928 bytes)
             DrawMacro stops when work pointer reaches $x3A0 (attribute table boundary)
$63C0-$63FF: Attribute table (64 bytes, 8×8 grid of 2-bit palette selections)
```

Each macro occupies 2 tiles wide × 2 tiles tall. With 29 tile rows, that's ~14 complete macro rows.
With 32 tile columns, the usable width is 16 macros (32 / 2).
The engine enforces a wrap check: `lda $00; and #$1F; bne +` — if the low 5 bits of the
work pointer reach 0, the row ends early to prevent wrapping.

CartRAMPtr ($39-$3A) points to the active buffer base ($60 or $64 high byte).

### Coordinate Mapping

Room RAM address = CartRAMPtr + (posY × $40) + (posX × 2)

- Each Y unit = $40 bytes (2 rows of 32 tiles = one macro height)
- Each X unit = 2 bytes (one macro width = 2 tiles)
- Maximum visible area: 16 macros wide × ~15 macros tall

---

## 10. Attribute Table

Each attribute byte controls palette selection for a 4×4 tile area (2×2 macros):

```
Bit layout per attribute byte:
  [7:6] = bottom-right macro palette
  [5:4] = bottom-left macro palette
  [3:2] = top-right macro palette
  [1:0] = top-left macro palette
```

When an object's palette differs from the room's base palette, `UpdateAttrib` masks and writes the appropriate 2-bit field.

---

## 11. World Map

A single shared 32×32 grid (1024 bytes) in bank 0 at CPU $A53E.

- Copied to RAM $7000-$73FF by `CopyMap` routine
- `GetRoomNum`: addr = (MapY × 32) + MapX + $7000
- Each byte = room number for that map cell; $FF = empty/unused
- All areas share the same map — different coordinate regions correspond to different areas

---

## 12. CHR RAM / Graphics Loading

Metroid uses CHR RAM (no CHR ROM). The GFXInfo table at $C6E0 (bank 7) defines tile copy operations:

```
Per entry (7 bytes):
  Byte 0:    Source bank number
  Bytes 1-2: Source CPU address
  Bytes 3-4: PPU destination address
  Bytes 5-6: Length in bytes
```

Background tiles go to PPU $1000-$1FFF (pattern table 1, 256 tiles).
Loading order: LoadSamusGFX entries first, then area-specific entries (later overwrites earlier).

---

## 13. Engine Limits

| Constraint | Limit | Source |
|-----------|-------|--------|
| Macros per struct row | 16 max (4-bit count, 0=16) | Row header format |
| X offset per row | 0-15 (4-bit) | Row header format |
| Struct index | 0-255 (8-bit) | Object byte 1 |
| Macro index | 0-255 (8-bit) | Struct row data |
| Object position X/Y | 0-15 each (4-bit) | Object byte 0 |
| Objects per room | ~85 practical (before $FD/$FF) | Room data format |
| Room palette | 0-255 (8-bit, but only 0-3 meaningful) | Room byte 0 |
| Object palette | 0-3 (2-bit) | Object byte 2 |
| Enemy slots | 8 (0, $10, $20, ..., $70) | Engine slot check |
| Room RAM buffer | 1024 bytes each | Hardware constraint |
| Bank size | $4000 (16384) bytes | MMC1 mapper |

### Space Budget Per Area Bank

All room data, struct data, struct pointer table, room pointer table, special items, and macro definitions must fit in one 16KB bank. The area pointer table and engine code share the fixed bank 7 address space.

---

## 14. Editroid's Architecture (Reference)

Key patterns from snarfblam's C# editor that inform our Kotlin encoder:

1. **In-memory ROM**: Entire ROM as `byte[]`; modifications directly to array
2. **Lazy load / deferred write**: Data loaded into objects on demand; written back via `SaveToRom()` chain
3. **Save sequence**: `SerializeStructTable` → `SerializeStructData` → `SerializeRoomTable` → `SerializeRoomData`
4. **Pointer management**: `ScreenWritePointers.AllocateSpace(size, out bank, out address)` for expanded ROMs
5. **Free space**: `BankAllocation` class tracks bank-level allocation (for MMC3 expanded ROMs)
6. **Item data**: `ItemCollection.SaveItemData()` recalculates sizes, repositions data, updates linked-list pointers
7. **Structure encoding**: Same format — row descriptor `(xOffset<<4 | width)`, tile bytes, `$FF` terminator

---

## 15. Implications for Our Encoder/Decoder

### What We Must Get Right

1. **SpecItmsTbl is read from ROM, not ZP** — pointer at $9598 must remain valid
2. **SpecItmsTbl internal pointers** — every "next" pointer in the linked list must be adjusted when data is relocated
3. **Contiguous tables** — RoomPtrTable, StructPtrTable, SpecItmsTbl are adjacent; growing one shifts the others
4. **Struct sharing** — multiple rooms can reference the same struct index; modifying a shared struct affects all rooms using it
5. **Collision via tile index** — area-specific: non-Tourian threshold is $70, Tourian is $80. Tiles $70/$80-$9F are blastable, ≥$A0 walkable
6. **Attribute table** — palette is per-object, written to attribute table during DrawRoom; overlapping objects can overwrite each other's palette bits
7. **Bank space is finite** — all area data must fit in 16KB; our allocator must track every byte

### Recommended Encoder Strategy

1. **Snapshot before modify** — capture SpecItmsTbl, all pointer tables from original ROM before any writes
2. **Track free space** — scan for gaps between known data regions; maintain a free block list
3. **Compact when needed** — relocate room/struct data to reclaim space, updating all pointer references
4. **1×1 struct allocation** — for uncovered edits, create minimal single-macro structs in free space
5. **Pointer table growth** — when adding new structs, the struct pointer table grows by 2 bytes, potentially displacing SpecItmsTbl
6. **Delta-adjust Y-node pointers** — when SpecItmsTbl is relocated, walk the Y-node chain and delta-adjust only the absolute "next Y" pointers (bytes 1-2 of each Y-node). X-entry offsets are relative and need no adjustment
7. **Validate after export** — re-parse the modified ROM and verify all rooms, structs, and items are readable
