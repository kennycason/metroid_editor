another strategy we basically, at export time re-encode ALL the map data, recompressed, new addresses, new room/map tables, so things like doors/ scrolls that load new maps would be able to trust this system to generate correct values. please evaluate your thoughts on this. 

another idea, if we delete a tile on a map/room it "adds" back to the tiles that we can render for that room, sort of like a credit/debit system. at save we fill in with emepty blank tiles  or something to fill the space in a way we we'll never notice but still keeps the memory fixed. this is a last ditch, my least favorite way but if we can' do the others.


analytsis please

---

Great questions. Let me do a thorough analysis of each strategy based on the deep ROM analysis I just completed.

---

## Strategy 1: SUROM 256KB Expansion

**How it works:** Metroid uses MMC1 (mapper 1) with the SNROM board variant (128KB PRG, 8 banks). The SUROM variant of MMC1 supports 256KB (16 banks). The extra bit comes from the CHR bank register — bit 4 selects between two 128KB "pages."

**What's involved:**
- Change iNES header byte 4 from `$08` to `$10` (16 banks)
- Append 128KB of empty space to the ROM
- Copy bank 7 (the fixed bank with game engine code) to bank 15 (the new fixed bank for page 1)
- Write a trampoline routine (~20-30 bytes of 6502) into both bank 7 and bank 15 at the same address, so the page switch is seamless
- Patch the game's room loading routine (`GetRoomData`) to check if a room should load from the expanded area
- Store relocated room data, structure data, and pointer tables in banks 8-14

**Effort:** Medium-high. The mechanical parts (header, appending data) are trivial. The hard part is writing the 6502 trampoline and patching the room loader. Probably ~40-60 lines of 6502 assembly, plus thorough testing. Since we only have **14 free bytes in bank 7**, we'd need to either:
- Optimize existing bank 7 code to free ~30 bytes, or
- Use a hook that jumps to the switchable bank, then page-switches from there

**Risk:** Medium. The trampoline must be bullet-proof — an NMI (vblank interrupt) firing during a page switch would crash the game. The standard solution is to disable interrupts (SEI) during the switch. Well-understood technique in the ROM hacking community.

**Emulator/Hardware compatibility:** Excellent. SUROM is used by Dragon Warrior 4, Final Fantasy 1&2, and other commercial games. Supported by:
- All major emulators (Mesen, FCEUX, Nestopia, RetroArch cores)
- FPGA solutions (MiSTer, Analogue Nt)
- Flash carts (Everdrive N8 Pro, PowerPak)
- Basically anything that can run DW4 runs SUROM

**Verdict:** Good long-term solution. Gives us 128KB of breathing room — effectively unlimited for map editing. Main cost is writing/testing the 6502 trampoline.

---

## Strategy 2: Full Re-encode at Export Time

**How it works:** Instead of patching individual bytes in the existing ROM data, we REGENERATE all room and structure data from scratch at export time. We already have the complete decoded state (the 16×15 macro grid for every room), so we can re-encode it optimally.

**What's involved:**
1. **Structure encoder**: Given a 16×15 grid, find an optimal set of structures that cover all non-background positions. This is a covering/tiling problem. A greedy algorithm works well:
   - Find horizontal runs of macros → single-row structures
   - Deduplicate identical structures across rooms
   - Share structures where possible

2. **Room data encoder**: For each room, emit `[palette][objects...][$FD][enemy/door data][$FF]`. The enemy/door section is copied verbatim from the original.

3. **Pointer table rebuilder**: Write new room pointer table and structure pointer table pointing to the new data locations.

4. **Cross-reference safety**: Door targets use room NUMBERS (not addresses), so they survive re-encoding. Elevators also use room numbers. Enemy placement is embedded in the room data and copied as-is.

**The key insight:** The current encoding is from 1986 and wasn't optimized for space efficiency — it was optimized for the original developers' workflow. A modern encoder can likely produce SMALLER data that represents the same (or modified) grids.

**Space math for Brinstar:**
- Current: ~1700 bytes room data + ~620 bytes structure data + 194 bytes pointer tables = ~2514 bytes
- Available bank space for this data: ~2900 bytes
- With optimal encoding: probably **10-20% smaller** due to better structure deduplication and fewer redundant objects
- This means we'd have **300-500 extra bytes** — enough for significant room modifications

**Effort:** High but well-scoped. This is essentially writing a "room compiler." The algorithm is:

```
For each area:
  1. Decode all rooms to grids (already done)
  2. Apply user edits
  3. For each room, decompose grid into candidate structures:
     - Scan rows for horizontal runs
     - Group adjacent rows with same patterns
  4. Deduplicate structures across all rooms in the area
  5. Assign structure indices, write structure pointer table
  6. Write structure data
  7. Write room data (objects referencing new struct indices)
  8. Write room pointer table
  9. Verify: decode the new data and compare grids
```

**Risk:** Medium. The biggest risk is encoding bugs that produce slightly wrong grids. But this is **fully testable** — we can decode the re-encoded data and compare it pixel-by-pixel against the expected output. A round-trip test (encode → decode → compare) catches any encoding error.

**Verdict:** This is the most elegant solution. It solves the space problem WITHIN the existing ROM size. No mapper changes, no hardware compatibility concerns. The main cost is implementing the encoder, but the algorithm is straightforward.

---

## Strategy 3: Combo (Re-encode + SUROM)

**How it works:** Re-encode first for optimal packing. If ANY area still exceeds its bank capacity (unlikely, but possible with very heavy editing), overflow rooms go into the expanded SUROM banks.

**Verdict:** Best-of-both-worlds but probably overkill. The re-encode alone should provide enough headroom for practical editing. SUROM would only be needed if someone completely redesigns a room from scratch (filling every background position with tiles). Even then, the re-encode's space savings might cover it.

**Recommendation:** Implement re-encode first. If users hit limits, add SUROM as a Phase 2.

---

## Strategy 4: Credit/Debit System

**How it works:** Deleting an existing tile "frees" the structure bytes it used. Adding a background tile "spends" from the freed pool. The ROM stays the same size.

**The problem with in-place patching:** Structures are shared between rooms. If struct 11 covers positions (0,0)-(7,2) in rooms 5, 9, and 12, you can't modify struct 11 just for room 9 without affecting rooms 5 and 12.

**With the re-encode approach, this becomes natural:** Deleting a tile means the encoder has fewer macros to represent → smaller output. Adding a tile means more macros → larger output. The "budget" is the available space in the bank.

**As a standalone strategy (without re-encode):** Very limited. You can only "free" space from positions that are uniquely covered by a structure exclusive to the current room. From the analysis:
- Brinstar: 7 exclusive 1×1 structs — can free ~21 bytes (7 × 3)
- That's enough for maybe 7 new tile placements total
- Not very useful

**Verdict:** This works great as a UX concept ON TOP of the re-encode strategy. Show users a "space budget" per area:

```
Brinstar: 847 / 1200 bytes used  [=========----]  
```

When they add/remove tiles, the budget updates. If they exceed the budget, the editor warns them before export.

---

## My Recommendation

**Phase 1 (best bang for buck): Full Re-encode**
- Effort: ~3-5 days of focused implementation
- Risk: Low (fully testable with round-trip verification)
- Compatibility: 100% (no ROM format changes)
- Solves: Most practical editing scenarios
- The encoder is reusable infrastructure that makes everything else easier

**Phase 2 (if needed): SUROM expansion**
- Only if users consistently hit the space budget
- Adds ~30-40 lines of 6502 trampoline code
- Well-understood technique, excellent compatibility

**UX: Budget indicator (from Strategy 4)**
- Show available space per area
- Warn when approaching limits
- Delete = free space, add = use space
- This is just a UI feature on top of whichever encoding strategy we use

Want me to start implementing the re-encode strategy?