# Metroid NES ROM Layout & Data Format Reference

Synthesized from: metroid-disassembly, m1disasm, MetroidMMC3, Editroid, METEdit,
SnowBro's "Metroid Level Data Explained" (metroiddatabase.com).

---

## 1. ROM Structure

Metroid NES is 128KB PRG ROM using MMC1 mapper (iNES mapper 1). No CHR ROM (uses CHR RAM).

| Bank | Size   | Contents                    | CPU Window              |
|------|--------|-----------------------------|-------------------------|
| 0    | $4000  | Title screen, world map     | $8000-$BFFF (switchable)|
| 1    | $4000  | Brinstar area data          | $8000-$BFFF (switchable)|
| 2    | $4000  | Norfair area data           | $8000-$BFFF (switchable)|
| 3    | $4000  | Tourian area data           | $8000-$BFFF (switchable)|
| 4    | $4000  | Kraid area data             | $8000-$BFFF (switchable)|
| 5    | $4000  | Ridley area data            | $8000-$BFFF (switchable)|
| 6    | $4000  | Graphics/CHR data           | $8000-$BFFF (switchable)|
| 7    | $4000  | Engine (fixed bank)         | $C000-$FFFF (always)    |

Each area bank (1-5) contains ALL map data for that area.

### ROM File Offset Formula

```
rom_offset = iNES_header (16 bytes) + bank_number * $4000 + (cpu_address - $8000)
```

For fixed bank 7 (CPU $C000-$FFFF):
```
rom_offset = 16 + 7 * $4000 + (cpu_address - $C000)
```

---

## 2. Area Pointer Table ($9598)

Located at CPU $9598 in each area bank. Eight 16-bit (little-endian) pointers:

| Offset | ZP Dest  | Label              | Description                        |
|--------|----------|--------------------|------------------------------------|
| +0     | (none)   | SpecItmsTbl        | Special items linked list (NOT copied to ZP) |
| +2     | $3B-$3C  | RoomPtrTable       | Room pointer table                 |
| +4     | $3D-$3E  | StructPtrTable     | Structure pointer table            |
| +6     | $3F-$40  | MacroPtr           | Macro definitions (2x2 tiles)      |
| +8     | $41-$42  | EnemyFramePtrTbl1  | Enemy frame pointers               |
| +10    | $43-$44  | EnemyFramePtrTbl2  | Enemy frame pointers               |
| +12    | $45-$46  | EnemyPlacePtrTbl   | Enemy placement pointers           |
| +14    | $47-$48  | EnemyAnimIndexTbl  | Enemy animation indices            |

`CopyPtrs` copies entries 1-7 (14 bytes) to ZP $3B-$48. Entry 0 (SpecItmsTbl)
is read directly from ROM at $9598 by `ScanForItems`.

Room count = (StructPtrTable - RoomPtrTable) / 2.

---

## 3. Room Data Format

Each room is a variable-length byte sequence:

```
[palette]                          — 1 byte: attribute table palette
[object 0: posYX, structIdx, pal]  — 3 bytes per object
[object 1: posYX, structIdx, pal]
...
[terminator]                       — $FD (has enemies/doors) or $FF (no enemies)
[enemy/door data...]               — only if terminated by $FD
[$FF]                              — end of all room data
```

### Object Format (3 bytes)

| Byte | Bits | Field       | Range                           |
|------|------|-------------|---------------------------------|
| 0    | 7-4  | posY        | 0-15 (x $40 in room RAM)       |
| 0    | 3-0  | posX        | 0-15 (x 2 in room RAM)         |
| 1    | 7-0  | structIndex | 0-255 (index into StructPtrTbl) |
| 2    | 1-0  | palette     | 0-3 (attribute table bits)      |

Special bytes: $FE = empty placeholder, $FD = end objects, $FF = end room.

### Enemy/Door Section

After $FD, entries parsed by low nibble:

| Low nibble | Type     | Size    | Format                              |
|------------|----------|---------|-------------------------------------|
| 1          | Enemy    | 3 bytes | [slot<<4 \| 1] [type] [posYX]      |
| 2          | Door     | 2 bytes | [info] [doorData]                   |
| 4          | Elevator | 2 bytes | [info] [elevData]                   |
| 6          | Statues  | 1 byte  | [marker]                            |
| 7          | Zeb hole | 3 bytes | [marker] [type] [posYX]             |

Terminated by $FF.

---

## 4. Structure Format

Each structure: rectangular tile pattern referenced by index via StructPtrTable.

```
[row 0 header] [macro indices...]
[row 1 header] [macro indices...]
...
[$FF]   — end of structure
```

### Row Header Byte

| Bits | Field   | Description                                    |
|------|---------|------------------------------------------------|
| 7-4  | xOffset | Horizontal offset (0-15)                       |
| 3-0  | count   | Number of macros in row (1-15; 0 means 16)     |

---

## 5. Macro Format (2x2 Tile Blocks)

Each macro is 4 bytes at `MacroPtr + (macroIndex * 4)`:

| Offset | Position      |
|--------|---------------|
| +0     | Top-left      |
| +1     | Top-right     |
| +2     | Bottom-left   |
| +3     | Bottom-right  |

Each byte is a CHR tile index (0-255) into the background pattern table.

---

## 6. Collision System

Tile indices determine collision directly — no separate collision map.

### Non-Tourian Areas
| Tile Range | Behavior           |
|------------|--------------------|
| < $70      | Solid              |
| $70-$7F    | Blastable          |
| $80-$9F    | Blastable          |
| >= $A0     | Walkable/passable  |

### Tourian
| Tile Range | Behavior           |
|------------|--------------------|
| < $80      | Solid              |
| $80-$9F    | Blastable          |
| >= $A0     | Walkable/passable  |

Special tiles: $A0 = toggle-scroll door, $A1 = horizontal-scroll door.

---

## 7. World Map

- **Location:** Bank 0, CPU $A53E (ROM offset $254E)
- **RAM copy:** $7000-$73FF
- **Size:** 32x32 = 1024 bytes
- **Format:** Each byte = room number for that cell; $FF = empty
- **Shared:** All areas use same map, different coordinate regions

See [ROOM_NAVIGATION.md](ROOM_NAVIGATION.md) for full navigation details.

---

## 8. Special Items Table (SpecItmsTbl)

A singly-linked list in the area bank. Entry 0 of area pointer table points to first node.

### Y-Node Format
```
Byte 0:    MapY position
Byte 1-2:  Absolute CPU pointer to next Y-node ($FFFF = end)
Byte 3+:   X-entries for this Y coordinate
```

### X-Entry Format
```
Byte 0:    MapX position
Byte 1:    Relative offset to next X-entry ($FF = no more)
Byte 2:    Item type byte (lower nibble = handler index)
Byte 3+:   Type-specific data
```

### Item Type Handlers

| Index | Handler            | Bytes (incl. type) |
|-------|--------------------|--------------------|
| 0     | ExitSub            | 0                  |
| 1     | SqueeptHandler     | 3                  |
| 2     | PowerUpHandler     | 3                  |
| 3     | SpecEnemyHandler   | 3                  |
| 4     | ElevatorHandler    | 2                  |
| 5     | CannonHandler      | 2                  |
| 6     | MotherBrainHandler | varies             |
| 7     | ZeebetiteHandler   | varies             |
| 8     | RinkaHandler       | 1                  |
| 9     | DoorHandler        | 2                  |
| A     | PaletteHandler     | varies             |

---

## 9. Per-Area Data at Fixed Offsets

Each area bank has area-specific data at these CPU addresses:

| Address   | Size    | Content                                   |
|-----------|---------|-------------------------------------------|
| $9598     | 16 bytes| Area pointer table (8 x 16-bit pointers)  |
| $95CE     | 1 byte  | Base enemy damage (low byte)              |
| $95CF     | 1 byte  | Base enemy damage (high byte)             |
| $95D0     | 7 bytes | Special room numbers (item room music)     |
| $95D7     | 1 byte  | Samus start X on world map                |
| $95D8     | 1 byte  | Samus start Y on world map                |
| $95D9     | 1 byte  | Samus start Y screen position             |
| $95DA     | 11 bytes| Palette toggle + misc area config         |

---

## 10. CHR RAM / Graphics Loading

GFXInfo table at $C6E0 (bank 7) defines tile copy operations:

```
Per entry (7 bytes):
  Byte 0:    Source bank number
  Bytes 1-2: Source CPU address
  Bytes 3-4: PPU destination address
  Bytes 5-6: Length in bytes
```

Background tiles → PPU $1000-$1FFF (pattern table 1, 256 tiles).

---

## 11. Engine Limits

| Constraint          | Limit                    | Source          |
|---------------------|--------------------------|-----------------|
| Macros per row      | 16 max (4-bit, 0=16)    | Row header      |
| X offset per row    | 0-15 (4-bit)            | Row header      |
| Struct index        | 0-255 (8-bit)           | Object byte 1   |
| Macro index         | 0-255 (8-bit)           | Struct row data  |
| Object position X/Y | 0-15 each (4-bit)       | Object byte 0   |
| Object palette      | 0-3 (2-bit)             | Object byte 2   |
| Enemy slots         | 8 (0-$70, step $10)     | Engine          |
| Room RAM buffer     | 1024 bytes each          | Hardware        |
| Bank size           | $4000 (16384) bytes      | MMC1 mapper     |
| World map           | 32x32 = 1024 cells       | Fixed           |

---

## 12. Quick ROM Offset Reference (SnowBro)

Absolute ROM file offsets for level data (includes 16-byte iNES header):

| Area     | Palette      | Room Ptrs    | Struct Ptrs  | Room Data      | Struct Defs    | Tile Defs  |
|----------|-------------|-------------|-------------|----------------|----------------|------------|
| Brinstar | $06284      | $06324      | $06382      | $06451-$06C93  | $06C94-$06EFF  | $06F00+    |
| Norfair  | $0A18B      | $0A22B      | $0A287      | $0A3BB-$0ACC8  | $0ACC9-$0AEFB  | $0AEFC+    |
| Tourian  | $0E72B      | $0E7E1      | $0E80B      | $0E8BF-$0EC25  | $0EC26-$0EE58  | $0EE59+    |
| Kraid    | $12168      | $121E5      | $1222F      | $122C7-$12A7A  | $12A7B-$12C41  | $12C42+    |
| Ridley   | $160FE      | $1618F      | $161E3      | $1624F-$169CE  | $169CF-$16B32  | $16B33+    |

World map: $0254E-$0294D (1024 bytes, 32x32 grid)
