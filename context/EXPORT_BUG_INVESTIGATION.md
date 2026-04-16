# Metroid NES ROM Export Bug Investigation

## Summary

Placing any new tile in the editor and exporting the ROM produces a broken game.
Symptoms range from visual glitches on the first exported tile to a complete failure
to start a new game when multiple rooms are edited. This document records the full
history of what was found and attempted so the investigation can be resumed cleanly.

---

## Background: How the ROM is Structured

Metroid NES is a 128 KB game that uses mapper 1 (MMC1) with switchable 16 KB PRG
banks. Brinstar data lives in **bank 1** (ROM file offsets `$04010–$08010`,
CPU addresses `$8000–$BFFF` when that bank is switched in).

The game engine stores a small **Area Pointer Table** at CPU `$9598` (ROM `$055A8`).
For Brinstar the table contains eight 16-bit little-endian CPU addresses:

| Table slot | CPU addr | ROM offset | Purpose |
|-----------|----------|------------|---------|
| 0 (bytes 0-1) | `$9598` | `$055A8` | **specItemsTable** – linked list of all pickups and enemies |
| 1 (bytes 2-3) | `$959A` | `$055AA` | **roomPtrTable** – one CPU ptr per room |
| 2 (bytes 4-5) | `$959C` | `$055AC` | **structPtrTable** – one CPU ptr per background structure |
| 3 (bytes 6-7) | `$959E` | `$055AE` | **macroDefs** – 4-byte (2×2 tile index) macro definitions |
| 4-7 | ... | ... | Enemy frame/placement/animation tables |

The `CopyPtrs` routine at CPU `$C869` copies entries 1–7 to zero-page variables at
power-on and keeps them there for the life of the game.  Entry 0 (`specItemsTable`)
is **not** copied to zero-page; `ScanForItems` re-reads it directly from ROM
address `$9598` on every room load.

### Layout of the data block in bank 1

Objects in Brinstar bank 1 are laid out contiguously like this (original ROM):

```
CPU $A314  (ROM $06324)  roomPtrTable     – 30 rooms × 2 bytes = 60 bytes
CPU $A372  (ROM $06382)  structPtrTable   – 50 structs × 2 bytes = 100 bytes
CPU $A3D6  (ROM $063E6)  specItemsTable   – 107 bytes (linked list, see below)
CPU $A446  (ROM $06456)  [room data...]
CPU $A6??                [struct data...]
CPU $AEF0  (ROM $06F00)  macroDefs        – 66 macros × 4 bytes = 264 bytes
```

The struct pointer table and the specItemsTable are **back-to-back with no gap**:
- struct pointer table ends at ROM `$063E5` (CPU `$A3D5`)
- specItemsTable begins at ROM `$063E6` (CPU `$A3D6`)

### specItemsTable format

`specItemsTable` is a singly-linked list.  Each entry has this layout:

```
byte  0     : Y map position (tile row, not pixel)
bytes 1-2   : CPU address of next entry (little-endian); $FFFF = end of list
byte  3     : X map position
bytes 4-5   : ... (AddToPtr00 adds 2 more, reaching the object type byte)
byte  5     : object type (index into handler table at $EDF2)
bytes 6+    : handler-specific payload (variable length)
```

The original Brinstar list has **8 entries** chained Y=$02→$03→$05→$07→$09→$0B→$0E→$12.
`ScanForItems` walks this list on every room load to spawn Rinkas and place items.
Because the pointers are **absolute CPU addresses**, any relocation of the table
invalidates all of them unless they are patched.

### Struct format

Each "structure" (object) is a list of rows terminated by `$FF`.  Each row is:

```
byte 0: xOffset (bits 3-0) and count-1 (bits 7-4)  — actually: low nibble = count, high nibble = xOffset
byte 1..N: macro index per column
$FF: end-of-rows terminator
```

A minimal 1×1 structure (`xOffset=0, count=1, macroIndex, $FF`) uses **3 bytes**.

---

## Relevant Source References

| Codebase | Key file(s) |
|----------|-------------|
| `~/code/metroid/metroid-disassembly` | `Source_Files/Bank07.asm` — full disassembly |
| `~/code/metroid/editroid` | C# ROM editor by snarfblam |
| `~/code/metroid/metroid_source_code_expanded` | Assembler source with symbol names |
| `~/code/metroid/MetroidMMC3` | MMC3-expanded port, useful for layout reference |

Key disassembly routines:

| Label | CPU addr | Description |
|-------|----------|-------------|
| `SetupRoom` / `CopyPtrs` | `$C869` | Copies 7 area ptrs (NOT specItemsTable) to zero-page `$3B–$48` |
| `ScanForItems` | `$ED98` | Reads `specItemsTable` from ROM `$9598` each room load |
| `DrawObject` | `$EA60` | Uses zp `$3D–$3E` (structPtrTable) to locate struct data |
| `DrawMacro` | `$EF2B` | Draws 2×2 tile block; skips if nametable address ≥ row 28 |
| `UpdateAttrib` | `$EF9E` | Sets palette bits in attribute table for each macro drawn |
| `LD651` | `$D651` | Collision: tile index `< $70` = solid, `≥ $70` = passable |
| `InitTables` | `$EFF8` | Clears room RAM to `$FF`; tile `$FF` = passable |

---

## History of Bugs Found and Fixed

### Bug 1 – macroDefs overwritten by compactBankData (crash on game start)

**Symptom:** Exporting ANY edited room produced a ROM where clicking "Start New Game"
immediately returned to the Start/Continue screen (game crash at boot).

**Root cause:** `compactBankData` calculated an `envelopeRange` (the upper bound for
free space scanning) from the start of the struct pointer table rather than from the
last actual data element.  This caused `macroDefs` at CPU `$AEF0` (a fixed-address
lookup table referenced by the engine via zp `$3F–$40`) to be treated as free space
and overwritten.

**Fix:** Computed `dataMax` from the actual maximum ROM offset of all known data
ranges (rooms, structs, specItemsTable, etc.) before constructing the free-block
allocator, ensuring `macroDefs` and other fixed tables beyond the data block were
never touched.

---

### Bug 2 – Orphaned bytes / "not enough space" failure

**Symptom:** The second call to `createNew1x1Struct` (needed when a newly placed
tile doesn't match any existing struct) returned `null`, leaving the tile unplaced.
The export appeared to succeed but the tile was absent.

**Root cause:** `createNew1x1Struct` allocated 3 bytes from the free list *before*
checking whether a slot was available in the struct pointer table.  When no slot was
found, the 3 bytes were already consumed (written as non-`$FF`), so they were no
longer returned to the free list.  On subsequent invocations the cumulative missing
bytes caused the allocator to report insufficient space.

**Fix:** Reordered the function to validate slot availability (the `unusedSlots`
check) *before* calling `allocateFreeBlock`.

---

### Bug 3 – specItemsTable internal pointers not adjusted on relocation (visual glitch + collision)

**Symptom (single tile edit):** After exporting a ROM with one new tile placed in
room 9 (Brinstar), the game loaded and drew the tile, but:
- Row 2 from the top showed incorrectly rendered sprites (orange Rinka / sun-like
  enemies that don't appear in the editor).
- Samus could walk through the new tile.
- The morph ball disappeared when Samus walked left.

**Root cause – sprites:** Adding one new struct entry extends the `structPtrTable`
from 50 to 51 entries (+2 bytes), pushing the `specItemsTable` forward by 2 bytes
(from CPU `$A3D6` to `$A3D8`).  The pointer at ROM `$9598` was correctly updated to
`$A3D8`, but the 107 bytes of `specItemsTable` data were copied byte-for-byte without
adjusting the internal "next entry" CPU pointers.

`ScanForItems` starts reading at `$A3D8` (correct), hits the first entry's `next`
pointer which still says `$A3E4` (old address; correct new address is `$A3E6`), and
immediately jumps 2 bytes *before* the second entry.  The bytes there are the tail of
the first entry's payload, not a valid entry header, so every subsequent spawn reads
garbage position/type data.  This causes Rinkas to spawn in wrong positions or wrong
rooms, producing the observed visual corruption.

**Root cause – collision:** Tile index `$A0` (the blue floor macro 6) is `≥ $70`, so
`LD651` considers it **passable**.  Samus walking through the tile is expected behavior
for that specific macro; it is a cosmetic floor tile, not a collision tile.  This is
unrelated to the pointer bug.

**Fix:** After computing `newSpecItemsCpu` in `compactBankData`, walk the linked list
in `specItemsData` and add the relocation delta to every internal `next-entry` pointer
before writing the data to its new location:

```kotlin
val delta = newSpecItemsCpu - specItemsCpu
if (delta != 0) {
    var pos = 0
    while (pos + 2 < specItemsData.size) {
        val lo = specItemsData[pos + 1].toInt() and 0xFF
        val hi = specItemsData[pos + 2].toInt() and 0xFF
        if (lo == 0xFF && hi == 0xFF) break          // $FFFF = last entry
        val nextPtr = (hi shl 8) or lo
        val nextOff = nextPtr - specItemsCpu
        if (nextOff <= pos || nextOff >= specItemsData.size) break
        val newNextPtr = nextPtr + delta
        specItemsData[pos + 1] = (newNextPtr and 0xFF).toByte()
        specItemsData[pos + 2] = ((newNextPtr shr 8) and 0xFF).toByte()
        pos = nextOff
    }
}
```

A unit test (`spec items table internal pointers are fixed after relocation`) was
added to `ExportRoundTripTest` and **passes** for a single-tile edit.

---

### Bug 4 – specItemsTable data corrupted before compactBankData reads it (current / unsolved)

**Symptom (multi-room edit):** When 5+ rooms each receive a new tile, clicking
"Start New Game" once again returns immediately to the Start/Continue screen.  The
exported `.nes` file shows:

```
specItemsTable pointer: $A3D6 → $A3E0  (delta = +10, i.e. 5 new struct entries)
specItemsTable[0] first bytes: 74 AC BD A6 C0 A6 ...  (should be 02 E4 A3 03 ...)
```

The first 10 bytes of the specItemsTable are overwritten with struct pointer values;
the remaining bytes are the original specItemsTable data starting at offset 10.
`ScanForItems` reads Y=`$74`, attempts to follow the `next` pointer `$BDAC`
(out of range), and crashes.

**Root cause:** The `structPtrTable` and `specItemsTable` are contiguous with no gap.
Each call to `createNew1x1Struct` inside the `reencodeRoom` loop writes a 2-byte
pointer for the new struct slot directly into ROM.  The first new slot (index 50)
writes to ROM `$063E6`—which is exactly the first byte of the specItemsTable.  Each
additional new slot writes 2 bytes further into the specItemsTable.

By the time `compactBankData` runs after the `reencodeRoom` loop, the first `N×2`
bytes of the specItemsTable in the parser's ROM buffer have already been overwritten
by struct pointer values.  `compactBankData` then reads this corrupted data as the
specItemsTable, applies the pointer-adjustment fix (which detects the garbage
pointers and aborts immediately), and writes the corruption to the new location.

Even if the pointer-adjustment fix were perfect, it would still operate on corrupted
input because the `next-ptr` bytes in entries 0 and 1 have been replaced by struct
pointer lo/hi bytes.

**The fix that is needed:**

Option A *(simplest):* Snapshot the `specItemsData` bytes from the **original** ROM
(not the mutable parser) *before* the `reencodeRoom` loop begins, and pass the
snapshot into `compactBankData` instead of having it re-read from the (now-modified)
parser.

```kotlin
// In exportAll, before the reencodeRoom loop:
val origSpecItemsCpu  = data.readAreaPointers(area).specItemsTable
val origSpecItemsRom  = origParser.bankAddressToRomOffset(bank, origSpecItemsCpu)
// compute size = distance to first room/struct data
val specItemsSnapshot: ByteArray = origParser.readBytes(origSpecItemsRom, specItemsSize)

// Pass snapshot into compactBankData so it never re-reads from the modified buffer.
```

Option B: Reserve padding between the struct pointer table and the specItemsTable
during initial layout so that struct table growth never immediately collides with it.

Option C: During `createNew1x1Struct`, instead of writing the new struct pointer
entry directly into ROM (which may stomp the specItemsTable), accumulate a deferred
list of "new struct pointer entries" and apply them only after `compactBankData` has
already saved the original specItemsTable.

**Option A is the recommended starting point** — it requires the least structural
change and addresses the root cause directly.

---

## Current Test Coverage

| Test | File | Status |
|------|------|--------|
| `user actual medit file edits - Metroid U` | `ExportRoundTripTest` | Passes (single room with 1 tile) |
| `single uncovered tile preserves all room raw data byte for byte` | `ExportRoundTripTest` | Passes |
| `spec items table internal pointers are fixed after relocation` | `ExportRoundTripTest` | Passes (single tile, delta=2) |
| Multi-room edit (5+ new tiles across different rooms) | not yet written | Would fail |

---

## Key Code Files

| File | Purpose |
|------|---------|
| `shared/.../rom/RomExporter.kt` | Core export logic: `exportAll`, `compactBankData`, `reencodeRoom`, `createNew1x1Struct` |
| `shared/.../rom/MetroidRomData.kt` | ROM parsing, area pointers (`AREA_POINTERS_ADDR = 0x9598`), `readRoom`, `readStructure` |
| `shared/.../rom/NesRomParser.kt` | Low-level ROM read/write, `bankAddressToRomOffset` |
| `shared/.../rom/RoomEncoder.kt` | `extractEnemyDoorSuffix` (trailing room bytes) |
| `shared/.../rom/MapRenderer.kt` | `buildMacroGrid` (in-memory 16×15 macro layout) |
| `shared/src/jvmTest/.../ExportRoundTripTest.kt` | Integration tests for export correctness |
| `shared/src/jvmTest/.../ExportDiagnosticTest.kt` | Diagnostic dump tests |
| `metroid-disassembly/Source_Files/Bank07.asm` | Full disassembly — ground truth for all data formats |

---

## Key Addresses (Brinstar, original ROM)

| Symbol | CPU addr | ROM offset | Notes |
|--------|----------|------------|-------|
| `AreaPointers` / `SpecItmsTable` ptr | `$9598` | `$055A8` | Read by `ScanForItems` directly |
| roomPtrTable | `$A314` | `$06324` | Copied to zp `$3B–$3C` |
| structPtrTable | `$A372` | `$06382` | Copied to zp `$3D–$3E`; 50 entries × 2 bytes |
| specItemsTable | `$A3D6` | `$063E6` | 107 bytes; starts immediately after structPtrTable |
| macroDefs | `$AEF0` | `$06F00` | Copied to zp `$3F–$40`; 66 macros × 4 bytes |
