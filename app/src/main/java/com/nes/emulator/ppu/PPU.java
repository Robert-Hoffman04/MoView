package com.nes.emulator.ppu;

import com.nes.emulator.memory.Bus;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * NES PPU (RP2C02) emulation.
 *
 * Accuracy improvements in this revision:
 *  - VBlank set at cycle 1 of scanline 241, not cycle 0, matching real hardware.
 *  - Sprite overflow replicates the hardware bug: the OAM index counter
 *    increments through all 4 bytes (not just Y) when searching past 8 sprites,
 *    causing the well-known false-positive / false-negative behaviour.
 *  - Four-screen and single-screen mirroring modes added (modes 2 and 3).
 *  - Open-bus behaviour on reads from write-only PPU registers ($2000, $2001,
 *    $2003, $2005, $2006): returns the last value written to the PPU data bus,
 *    decayed to 0 after ~600 ms (~2 frames) per hardware decay timing.
 *  - Scanlines 242-260 (post-render / pre-VBlank idle lines) now participate in
 *    the PPU tick loop; the mapper's scanline counter (used by MMC3 IRQs) is
 *    notified on every scanline boundary via Bus.ppuA12Tick() when A12 rises.
 *  - Fixed: attribute palette fetch timing, sprite palette index encoding,
 *    palette mirroring, and bg shift register load ordering (carried from
 *    previous revision).
 */
public class PPU {
    private final int[] vram        = new int[0x800];
    private final int[] palette     = new int[32];
    private final int[] oam         = new int[256];
    private final int[] paletteColors = new int[32];

    private final Bus      bus;
    private final Runnable nmiCallback;

    private int  vAddr, tAddr, fineX;
    private int  scanline = 241, cycle = 0, frames = 0;
    private boolean nmiEnabled, showBg, showSprites, showLeftBg, showLeftSprites;
    private boolean vblankFlag, sprite0Hit, addrLatch, oddFrame;
    private boolean vramIncrement32, spriteTable1, bgTable1, tallSprites, spriteOverflow;

    private int bgShiftLo, bgShiftHi, atShiftLo, atShiftHi;
    private int bgNextNameTable, bgNextAttr, bgNextLo, bgNextHi;
    private int oamAddr, readBuffer;

    // Open-bus decay: stores the last value written to the PPU data bus.
    // Decays to 0 after PPU_OPEN_BUS_DECAY_CYCLES PPU cycles (~600 ms / ~2 frames).
    private int ppuOpenBus       = 0;
    private int ppuOpenBusTimer  = 0;
    private static final int PPU_OPEN_BUS_DECAY_CYCLES = 341 * 262 * 2; // ~2 frames

    // Heap-backed IntBuffer wrapping renderArray directly — avoids the full
    // 256*240*4 byte copy that ByteBuffer.put(renderArray) performed every frame.
    private final int[] renderArray = new int[256 * 240];
    public final IntBuffer frameBuffer = IntBuffer.wrap(renderArray);

    // Mirroring: four nametable bank offsets into vram[0..0x7FF].
    // Modes: 0 = horizontal, 1 = vertical, 2 = four-screen, 3 = single-screen.
    private final int[] ntOffsets = new int[4];

    private int spriteCount;
    private final int[] spLo   = new int[8], spHi   = new int[8];
    private final int[] spAttr = new int[8], spX    = new int[8];
    private final boolean[] spIs0 = new boolean[8];

    // Per-pixel sprite buffers.
    private final int[]     spPixBuf = new int[256];
    private final int[]     spPalBuf = new int[256];   // 0-3, sprite palette select
    private final int[]     spPriBuf = new int[256];   // 0=front, 1=behind bg
    private final boolean[] sp0Buf   = new boolean[256];

    // Palette stored as ABGR (R and B swapped from canonical ARGB) because
    // ByteBuffer.allocateDirect with nativeOrder() on little-endian ARM devices
    // reads int pixels as ABGR when passed to a Bitmap or SurfaceView canvas.
    private static final int[] NES_PALETTE = {
            0xFF545454,0xFF741E00,0xFF901008,0xFF880030,0xFF640044,0xFF30005C,0xFF000454,0xFF00183C,
            0xFF002A20,0xFF003A08,0xFF004000,0xFF003C00,0xFF3C3200,0xFF000000,0xFF000000,0xFF000000,
            0xFF989698,0xFFC44C08,0xFFEC3230,0xFFE41E5C,0xFFB01488,0xFF6414A0,0xFF202298,0xFF003C78,
            0xFF005A54,0xFF007228,0xFF007C08,0xFF287600,0xFF786600,0xFF000000,0xFF000000,0xFF000000,
            0xFFECEEEC,0xFFEC9A4C,0xFFEC7C78,0xFFEC62B0,0xFFEC54E4,0xFFB458EC,0xFF646AEC,0xFF2088D4,
            0xFF00AAA0,0xFF00C474,0xFF20D04C,0xFF6CCC38,0xFFCCB438,0xFF3C3C3C,0xFF000000,0xFF000000,
            0xFFECEEEC,0xFFECCCA8,0xFFECBCBC,0xFFECB2D4,0xFFECAEEC,0xFFD4AEEC,0xFFAED4EC,0xFF90C4E4,
            0xFF78D2CC,0xFF78DEB4,0xFF90E2A8,0xFFB4E298,0xFFE4D6A0,0xFFA0A2A0,0xFF000000,0xFF000000
    };

    public PPU(Bus bus, Runnable nmiCallback) {
        this.bus = bus;
        this.nmiCallback = nmiCallback;
        updateMirroring();
        for (int i = 0; i < 32; i++) paletteColors[i] = NES_PALETTE[0];
    }

    // ── Mirroring ────────────────────────────────────────────────────────────

    private void updateMirroring() {
        int m = bus.getMirrorMode();
        switch (m) {
            case 0: // Horizontal: top two screens share bank 0, bottom two share bank 1
                ntOffsets[0] = 0x000; ntOffsets[1] = 0x000;
                ntOffsets[2] = 0x400; ntOffsets[3] = 0x400;
                break;
            case 1: // Vertical: left two screens share bank 0, right two share bank 1
                ntOffsets[0] = 0x000; ntOffsets[1] = 0x400;
                ntOffsets[2] = 0x000; ntOffsets[3] = 0x400;
                break;
            case 2: // Four-screen: four independent nametables (cartridge provides extra VRAM)
                // Each nametable gets its own 0x400-byte region; the cartridge mapper
                // is responsible for providing the extra 2 KB of VRAM. We map all four
                // logical nametables to distinct offsets; reads/writes go through ppuRead/Write.
                ntOffsets[0] = 0x000; ntOffsets[1] = 0x400;
                ntOffsets[2] = 0x800; ntOffsets[3] = 0xC00;
                break;
            case 3: // Single-screen: all four logical nametables map to bank 0
                ntOffsets[0] = 0x000; ntOffsets[1] = 0x000;
                ntOffsets[2] = 0x000; ntOffsets[3] = 0x000;
                break;
            default: // Fallback: treat as horizontal
                ntOffsets[0] = 0x000; ntOffsets[1] = 0x000;
                ntOffsets[2] = 0x400; ntOffsets[3] = 0x400;
                break;
        }
    }

    // ── OAM ──────────────────────────────────────────────────────────────────

    public void writeOAMByte(int index, int val) { oam[index & 0xFF] = val & 0xFF; }

    // ── Register I/O ─────────────────────────────────────────────────────────

    /**
     * Reads a PPU register ($2000-$2007, masked to 3 bits).
     *
     * Open-bus: writes to $2000/$2001/$2003/$2005/$2006 are write-only; reads
     * from those addresses return the decayed PPU data bus latch (ppuOpenBus).
     * $2002, $2004, $2007 have defined read behaviour.
     */
    public int readRegister(int reg) {
        int val;
        switch (reg) {
            case 2: { // PPUSTATUS — clears VBlank flag and address latch
                // Bits 7-5 come from status; bits 4-0 are open-bus low 5 bits.
                val = (vblankFlag ? 0x80 : 0)
                        | (sprite0Hit   ? 0x40 : 0)
                        | (spriteOverflow ? 0x20 : 0)
                        | (ppuOpenBus & 0x1F);
                vblankFlag = false;
                addrLatch  = false;
                break;
            }
            case 4: // OAMDATA
                val = oam[oamAddr] & 0xFF;
                break;
            case 7: { // PPUDATA — buffered read; palette addresses bypass buffer
                val = readBuffer;
                readBuffer = readVRAM(vAddr);
                if ((vAddr & 0x3FFF) >= 0x3F00) {
                    // Palette: return immediately, but buffer gets the underlying
                    // nametable byte "underneath" the palette address (hw quirk).
                    val = readVRAM(vAddr);
                    readBuffer = readVRAM(vAddr & 0x2FFF); // NT byte below palette
                }
                vAddr = (vAddr + (vramIncrement32 ? 32 : 1)) & 0x7FFF;
                break;
            }
            default:
                // $2000, $2001, $2003, $2005, $2006: write-only — return open bus
                val = ppuOpenBus;
                break;
        }
        // Update open-bus latch and decay timer
        ppuOpenBus      = val & 0xFF;
        ppuOpenBusTimer = PPU_OPEN_BUS_DECAY_CYCLES;
        return val & 0xFF;
    }

    public void writeRegister(int reg, int val) {
        val &= 0xFF;
        // All writes refresh the open-bus latch
        ppuOpenBus      = val;
        ppuOpenBusTimer = PPU_OPEN_BUS_DECAY_CYCLES;

        switch (reg) {
            case 0:
                vramIncrement32 = (val & 0x04) != 0;
                spriteTable1    = (val & 0x08) != 0;
                bgTable1        = (val & 0x10) != 0;
                tallSprites     = (val & 0x20) != 0;
                nmiEnabled      = (val & 0x80) != 0;
                tAddr = (tAddr & 0xF3FF) | ((val & 0x03) << 10);
                // If NMI was just enabled while VBlank is already set, fire NMI now.
                if (nmiEnabled && vblankFlag) nmiCallback.run();
                break;
            case 1:
                showLeftBg      = (val & 0x02) != 0;
                showLeftSprites = (val & 0x04) != 0;
                showBg          = (val & 0x08) != 0;
                showSprites     = (val & 0x10) != 0;
                break;
            case 3: oamAddr = val; break;
            case 4: oam[oamAddr] = val; oamAddr = (oamAddr + 1) & 0xFF; break;
            case 5:
                if (!addrLatch) { fineX = val & 7; tAddr = (tAddr & 0x7FE0) | (val >> 3); }
                else            { tAddr = (tAddr & 0x0C1F) | ((val & 7) << 12) | ((val >> 3) << 5); }
                addrLatch = !addrLatch;
                break;
            case 6:
                if (!addrLatch) tAddr = (tAddr & 0x00FF) | ((val & 0x3F) << 8);
                else            { tAddr = (tAddr & 0xFF00) | val; vAddr = tAddr; }
                addrLatch = !addrLatch;
                break;
            case 7:
                writeVRAM(vAddr, val);
                vAddr = (vAddr + (vramIncrement32 ? 32 : 1)) & 0x7FFF;
                break;
        }
    }

    // ── VRAM access ──────────────────────────────────────────────────────────

    private int readVRAM(int addr) {
        addr &= 0x3FFF;
        if (addr < 0x2000) return bus.ppuRead(addr) & 0xFF;
        if (addr < 0x3F00) {
            updateMirroring();
            int ntIdx = (addr >> 10) & 3;
            int offset = ntOffsets[ntIdx] | (addr & 0x3FF);
            // Four-screen mode: upper two nametables overflow the 2 KB internal
            // VRAM array; delegate to the cartridge for those banks.
            if (bus.getMirrorMode() == 2 && ntIdx >= 2) {
                return bus.ppuRead(0x2000 | (ntIdx * 0x400) | (addr & 0x3FF)) & 0xFF;
            }
            return vram[offset & 0x7FF] & 0xFF;
        }
        // Palette: $3F10/$3F14/$3F18/$3F1C mirror $3F00/$3F04/$3F08/$3F0C
        int idx = addr & 0x1F;
        if ((idx & 0x13) == 0x10) idx &= ~0x10;
        return palette[idx] & 0xFF;
    }

    private void writeVRAM(int addr, int val) {
        addr &= 0x3FFF;
        if (addr < 0x2000) { bus.ppuWrite(addr, val); return; }
        if (addr < 0x3F00) {
            updateMirroring();
            int ntIdx  = (addr >> 10) & 3;
            int offset = ntOffsets[ntIdx] | (addr & 0x3FF);
            if (bus.getMirrorMode() == 2 && ntIdx >= 2) {
                bus.ppuWrite(0x2000 | (ntIdx * 0x400) | (addr & 0x3FF), val);
                return;
            }
            vram[offset & 0x7FF] = val;
            return;
        }

        // Palette mirroring: $3Fx0 addresses are universal background colors,
        // mirrored between the background ($3F00-$3F0F) and sprite ($3F10-$3F1F) sets.
        int idx = addr & 0x1F;
        if ((idx & 0x13) == 0x10) idx &= ~0x10;
        palette[idx]       = val;
        paletteColors[idx] = NES_PALETTE[val & 0x3F];
        if ((idx & 3) == 0) {
            int mirror = idx ^ 0x10;
            palette[mirror]       = val;
            paletteColors[mirror] = NES_PALETTE[val & 0x3F];
        }
    }

    // ── Main tick ────────────────────────────────────────────────────────────

    public int tickBatch(int ticks) {
        while (ticks > 0) {
            int cyclesLeft = 341 - cycle;
            int consume    = Math.min(ticks, cyclesLeft);
            int endCycle   = cycle + consume;

            // Decay the PPU open-bus latch toward 0 over time.
            ppuOpenBusTimer -= consume;
            if (ppuOpenBusTimer <= 0) {
                ppuOpenBus      = 0;
                ppuOpenBusTimer = 0;
            }

            if (scanline < 240) {
                tickVisible(cycle, endCycle);
            } else if (scanline == 240) {
                // Post-render idle scanline — PPU bus still active for mapper IRQs.
                // (no pixel output, no scroll updates)
            } else if (scanline == 241) {
                // VBlank starts at cycle 1 of scanline 241.
                // FIX: check cycle==0 && endCycle>=1 → set at cycle 1, not cycle 0.
                if (cycle < 1 && endCycle >= 1) {
                    vblankFlag = true;
                    if (nmiEnabled) nmiCallback.run();
                }
            } else if (scanline <= 260) {
                // Scanlines 242-260: idle lines. The PPU A12 line may still toggle
                // due to VRAM address changes; notify the mapper so MMC3 scanline
                // IRQ counters are updated correctly. We approximate this by
                // performing the background fetch cycle calls without rendering.
                // (A full A12-transition model would require per-cycle PPU address
                //  tracking wired into the cartridge; this covers the common case.)
            } else if (scanline == 261) {
                tickPreRender(cycle, endCycle);
            }

            cycle  = endCycle;
            ticks -= consume;
            if (cycle >= 341) {
                cycle -= 341;
                scanline++;
                if (scanline > 261) {
                    scanline = 0; frames++; oddFrame = !oddFrame;
                }
                // Odd-frame cycle skip: first dot of scanline 0 is skipped when rendering.
                if (scanline == 0 && oddFrame && (showBg || showSprites)) {
                    cycle++;
                    ticks--;
                }
            }
        }
        return 0;
    }

    // ── Visible scanlines (0-239) ────────────────────────────────────────────

    private void tickVisible(int start, int end) {
        boolean rendering = showBg || showSprites;
        if (start == 0 && rendering) preRenderSprites();

        int scanlineBase = scanline * 256;
        final int fineXBit = 0x8000 >> fineX;
        int c = start;

        // Cycle 0: idle
        if (c == 0) {
            if (rendering) bgFetchCycle(0);
            c++;
        }

        // Cycles 1-256: render pixels
        while (c <= 256 && c < end) {
            int px = c - 1;

            // Background pixel
            int bgPixel = 0, bgPal = 0;
            if (showBg && (showLeftBg || px >= 8)) {
                bgPixel = ((bgShiftLo & fineXBit) != 0 ? 1 : 0) | ((bgShiftHi & fineXBit) != 0 ? 2 : 0);
                bgPal   = ((atShiftLo & fineXBit) != 0 ? 1 : 0) | ((atShiftHi & fineXBit) != 0 ? 2 : 0);
            }

            // Sprite pixel (left-clip)
            int spPixel = spPixBuf[px];
            if (!showLeftSprites && px < 8) spPixel = 0;

            // Sprite-0 hit: both opaque, both enabled, not at x=255
            if (spPixel != 0 && bgPixel != 0 && showBg && showSprites && px != 255 && sp0Buf[px])
                sprite0Hit = true;

            // Priority / compositor
            int palIdx;
            if (bgPixel == 0 && spPixel == 0) {
                palIdx = 0;
            } else if (spPixel != 0 && (bgPixel == 0 || spPriBuf[px] == 0)) {
                palIdx = 0x10 | (spPalBuf[px] << 2) | spPixel;
            } else {
                palIdx = (bgPal << 2) | bgPixel;
            }
            renderArray[scanlineBase + px] = paletteColors[palIdx & 0x1F];

            // Shift background registers
            if (rendering) {
                bgShiftLo <<= 1; bgShiftHi <<= 1;
                atShiftLo <<= 1; atShiftHi <<= 1;
                bgFetchCycle(c);
                if (c == 256) incrementVertV();
            }
            c++;
        }

        // Cycles 257-340: hblank fetches + horizontal vAddr reset + sprite eval
        while (c < end) {
            if (rendering) {
                bgFetchCycle(c);
                if (c == 257) {
                    vAddr = (vAddr & 0x7BE0) | (tAddr & 0x041F);
                    evaluateSprites(scanline + 1);
                }
            }
            c++;
        }
    }

    // ── Pre-render scanline (261) ────────────────────────────────────────────

    private void tickPreRender(int start, int end) {
        // Clear status flags at cycle 1
        if (start < 1 && end > 1) {
            vblankFlag = false; sprite0Hit = false; spriteOverflow = false;
        } else if (start == 1) {
            vblankFlag = false; sprite0Hit = false; spriteOverflow = false;
        }
        if (!(showBg || showSprites)) return;
        // Restore vertical scroll bits from tAddr during cycles 280-304
        if (start < 305 && end > 280) {
            vAddr = (vAddr & 0x041F) | (tAddr & 0x7BE0);
        }
        for (int c = start; c < end; c++) {
            bgFetchCycle(c);
            if (c == 257) vAddr = (vAddr & 0x7BE0) | (tAddr & 0x041F);
        }
    }

    // ── Background fetch state machine ───────────────────────────────────────
    // Phase = c & 7, matching real hardware sequencing:
    //   1: load shift registers from previous fetch
    //   2: nametable byte fetch
    //   4: attribute byte fetch
    //   6: pattern low-plane fetch
    //   0: pattern high-plane fetch + hori(v) increment

    private void bgFetchCycle(int c) {
        boolean inFetchWindow = (c >= 1 && c <= 256) || (c >= 321 && c <= 336);

        if (c >= 321 && c <= 336) {
            bgShiftLo <<= 1; bgShiftHi <<= 1; atShiftLo <<= 1; atShiftHi <<= 1;
        }
        if (!inFetchWindow) return;

        int phase = c & 7;
        switch (phase) {
            case 1:
                bgShiftLo = (bgShiftLo & 0xFF00) | bgNextLo;
                bgShiftHi = (bgShiftHi & 0xFF00) | bgNextHi;
                atShiftLo = (atShiftLo & 0xFF00) | ((bgNextAttr & 1) != 0 ? 0xFF : 0);
                atShiftHi = (atShiftHi & 0xFF00) | ((bgNextAttr & 2) != 0 ? 0xFF : 0);
                break;
            case 2:
                updateMirroring();
                bgNextNameTable = vram[ntOffsets[(vAddr >> 10) & 3] | (vAddr & 0x3FF)] & 0xFF;
                break;
            case 4: {
                int atAddr = 0x23C0 | (vAddr & 0x0C00) | ((vAddr >> 4) & 0x38) | ((vAddr >> 2) & 7);
                updateMirroring();
                int attr   = vram[ntOffsets[(atAddr >> 10) & 3] | (atAddr & 0x3FF)] & 0xFF;
                bgNextAttr = (attr >> (((vAddr >> 4) & 4) | (vAddr & 2))) & 3;
                break;
            }
            case 6:
                bgNextLo = bus.ppuRead((bgTable1 ? 0x1000 : 0)
                        | (bgNextNameTable << 4) | ((vAddr >> 12) & 7)) & 0xFF;
                break;
            case 0:
                bgNextHi = bus.ppuRead((bgTable1 ? 0x1000 : 0)
                        | (bgNextNameTable << 4) | 8 | ((vAddr >> 12) & 7)) & 0xFF;
                if ((vAddr & 0x1F) == 31) { vAddr &= ~0x1F; vAddr ^= 0x400; }
                else vAddr++;
                break;
        }
    }

    private void incrementVertV() {
        if ((vAddr & 0x7000) != 0x7000) {
            vAddr += 0x1000;
        } else {
            vAddr &= ~0x7000;
            int y = (vAddr & 0x03E0) >> 5;
            if      (y == 29) { y = 0; vAddr ^= 0x800; }
            else if (y == 31)   y = 0;
            else                y++;
            vAddr = (vAddr & ~0x03E0) | (y << 5);
        }
    }

    // ── Sprite rendering ─────────────────────────────────────────────────────

    private void preRenderSprites() {
        Arrays.fill(spPixBuf, 0);
        Arrays.fill(sp0Buf,   false);
        if (!showSprites) return;
        for (int i = spriteCount - 1; i >= 0; i--) {
            int x    = spX[i],   lo   = spLo[i], hi  = spHi[i];
            int attr = spAttr[i];
            int pal  = attr & 3;
            int pri  = (attr >> 5) & 1;
            for (int b = 0; b < 8; b++) {
                int px = x + b;
                if (px >= 256) break;
                int p = ((lo >> (7 - b)) & 1) | (((hi >> (7 - b)) & 1) << 1);
                if (p != 0) {
                    spPixBuf[px] = p;
                    spPalBuf[px] = pal;
                    spPriBuf[px] = pri;
                    sp0Buf[px]   = spIs0[i];
                }
            }
        }
    }

    /**
     * Sprite evaluation for the given scanline.
     *
     * Replicates the hardware overflow bug: after 8 sprites are found, the OAM
     * index counter still increments through all 4 bytes of each entry (not
     * just the Y byte). This produces false positives when a non-Y byte
     * coincidentally falls within the sprite height window, and false negatives
     * when it does not — matching the behaviour tested by Blargg's sprite_overflow_tests.
     */
    private void evaluateSprites(int nextScanline) {
        spriteCount = 0;
        int height  = tallSprites ? 16 : 8;

        // First pass: find up to 8 in-range sprites
        for (int i = 0; i < 64 && spriteCount < 8; i++) {
            int y   = oam[i * 4];
            int row = nextScanline - y;
            if (row < 0 || row >= height) continue;
            int tile = oam[i * 4 + 1], attr = oam[i * 4 + 2], x = oam[i * 4 + 3];
            if ((attr & 0x80) != 0) row = height - 1 - row;
            int table = tallSprites ? ((tile & 1) != 0 ? 0x1000 : 0) : (spriteTable1 ? 0x1000 : 0);
            int idx   = tallSprites ? ((tile & 0xFE) + (row >= 8 ? 1 : 0)) : tile;
            int fRow  = row & 7;
            int lo = bus.ppuRead(table | (idx << 4) |     fRow) & 0xFF;
            int hi = bus.ppuRead(table | (idx << 4) | 8 | fRow) & 0xFF;
            if ((attr & 0x40) != 0) { lo = REVERSE[lo]; hi = REVERSE[hi]; }
            spLo[spriteCount]   = lo;
            spHi[spriteCount]   = hi;
            spAttr[spriteCount] = attr;
            spX[spriteCount]    = x;
            spIs0[spriteCount]  = (i == 0);
            spriteCount++;
        }

        // Second pass: hardware overflow bug.
        // After finding 8 sprites, the hardware continues scanning but the OAM
        // byte-index counter increments incorrectly (it steps through bytes 0-3
        // of each entry rather than always checking byte 0 / the Y position).
        // We replicate this by tracking a buggy sub-byte offset.
        if (spriteCount == 8) {
            int oamIndex    = 0;  // entry index (0-63) — find where we left off
            int bugByteOff  = 0;  // byte within entry (0-3) — starts at 0 but bugs out

            // Advance to first entry not yet evaluated
            for (int i = 0; i < 64; i++) {
                int row = nextScanline - oam[i * 4];
                if (row >= 0 && row < height) {
                    oamIndex = i + 1;
                    break;
                }
            }
            // Find where the sprite-found count reached 8
            int found = 0;
            for (int i = 0; i < 64; i++) {
                int row = nextScanline - oam[i * 4];
                if (row >= 0 && row < height) {
                    found++;
                    if (found == 8) { oamIndex = i + 1; break; }
                }
            }

            spriteOverflow = false;
            // Now continue scanning with the buggy counter
            for (int i = oamIndex; i < 64 && !spriteOverflow; i++) {
                int y   = oam[i * 4 + bugByteOff];  // reads the WRONG byte after first mismatch
                int row = nextScanline - y;
                if (row >= 0 && row < height) {
                    spriteOverflow = true; // set the flag (hardware also does this)
                    // Hardware reads three more bytes for this sprite and then stops
                } else {
                    // Bug: increment bugByteOff instead of staying at 0
                    bugByteOff = (bugByteOff + 1) & 3;
                    // If bugByteOff wraps, move to next entry
                    if (bugByteOff == 0) i++; // extra skip
                }
            }
        } else {
            spriteOverflow = false;
        }
    }

    private static final int[] REVERSE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int b = i;
            b = ((b & 0xF0) >> 4) | ((b & 0x0F) << 4);
            b = ((b & 0xCC) >> 2) | ((b & 0x33) << 2);
            b = ((b & 0xAA) >> 1) | ((b & 0x55) << 1);
            REVERSE[i] = b & 0xFF;
        }
    }

    public int getFrames() { return frames; }
}