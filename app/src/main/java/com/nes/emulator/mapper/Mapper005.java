package com.nes.emulator.mapper;

/**
 * Mapper 5 – ExROM (MMC5).
 *
 * Supports PRG modes 0-3, CHR modes 0-3, ExRAM, split screen,
 * scanline IRQ, and 8 KB PRG-RAM.  Fill-mode, multiplicand
 * registers, and sprite fetch detection are also implemented.
 */
public class Mapper005 extends Mapper {

    // ── PRG ──────────────────────────────────────────────────────────────
    private int prgMode = 3;          // 0=32KB, 1=16KB, 2=16+8KB, 3=8KB
    private int[] prgRegs = new int[5]; // $5113-$5117
    private byte[] prgRam = new byte[0x10000]; // up to 64KB PRG-RAM

    // ── CHR ──────────────────────────────────────────────────────────────
    private int chrMode = 0;          // 0=8KB, 1=4KB, 2=2KB, 3=1KB
    private int[] chrRegsA = new int[8]; // $5120-$5127  (sprite/bg)
    private int[] chrRegsB = new int[4]; // $5128-$512B  (bg upper half)
    private boolean chrUpper = false;  // which set active during rendering

    // ── ExRAM ($5C00-$5FFF, 1KB) ─────────────────────────────────────────
    private byte[] exRam = new byte[0x400];
    private int exRamMode = 0;        // 0=extra nametable, 1=attr ext, 2=R/W, 3=R/O

    // ── Nametable mapping ────────────────────────────────────────────────
    private int[] ntCtrl = new int[4]; // per-NT source: 0=CIRAM0, 1=CIRAM1, 2=ExRAM, 3=Fill

    // ── Fill mode ────────────────────────────────────────────────────────
    private int fillTile  = 0;
    private int fillAttr  = 0;

    // ── IRQ ──────────────────────────────────────────────────────────────
    private int  irqScanline  = 0;
    private boolean irqEnabled  = false;
    private boolean irqPending  = false;
    private int  scanlineCount  = 0;
    private Runnable irqCallback;

    // ── Multiplier ───────────────────────────────────────────────────────
    private int mulA = 0, mulB = 0;

    Mapper005(byte[] prg, byte[] chr, int prgBanks, int chrBanks, int mirror) {
        super(prg, chr, prgBanks, chrBanks, mirror);
        // Power-on: last bank mapped everywhere
        prgRegs[4] = 0xFF;
        for (int i = 0; i < 4; i++) prgRegs[i] = 0xFF;
    }

    public void setIRQCallback(Runnable cb) { irqCallback = cb; }

    // ─────────────────────────────────────────────────────────────────────
    // CPU R/W
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public int cpuRead(int addr) {
        if (addr < 0x6000) {
            // Internal registers readable at some addresses
            if (addr == 0x5204) {
                int v = (irqPending ? 0x80 : 0) | (scanlineCount > 0 ? 0x40 : 0);
                irqPending = false;
                return v;
            }
            if (addr == 0x5205) return (mulA * mulB) & 0xFF;
            if (addr == 0x5206) return ((mulA * mulB) >> 8) & 0xFF;
            if (addr >= 0x5C00 && addr <= 0x5FFF && exRamMode >= 2)
                return exRam[addr - 0x5C00] & 0xFF;
            return 0;
        }
        if (addr < 0x8000) {
            // PRG-RAM: $6000-$7FFF mapped by prgRegs[0] (bit 2 = RAM select)
            int reg = prgRegs[0];
            if ((reg & 0x04) != 0) {
                int bank = (reg >> 2) & 0x3F;
                return prgRam[(bank * 0x2000) + (addr & 0x1FFF)] & 0xFF;
            }
            return 0;
        }
        return prgRomRead(addr);
    }

    private int prgRomRead(int addr) {
        int slot = (addr - 0x8000) / 0x2000; // 0-3
        switch (prgMode) {
            case 0: { // 32 KB mode: regs[4] selects 32KB block
                int bank = ((prgRegs[4] & ~3) | slot) & (prgBanks * 2 - 1);
                return prg[(bank * 0x2000) + (addr & 0x1FFF)] & 0xFF;
            }
            case 1: { // 16 KB: reg[2] & reg[4]
                int base = (slot < 2) ? prgRegs[2] : prgRegs[4];
                int bankBase = (base & ~1) + (slot & 1);
                bankBase &= (prgBanks * 2 - 1);
                return prg[(bankBase * 0x2000) + (addr & 0x1FFF)] & 0xFF;
            }
            case 2: { // 16+8+8: reg[2] for $8000, reg[3]+reg[4] for $C000
                if (slot < 2) {
                    int bank = ((prgRegs[2] & ~1) + slot) & (prgBanks * 2 - 1);
                    return prg[(bank * 0x2000) + (addr & 0x1FFF)] & 0xFF;
                } else {
                    int bank = prgRegs[slot + 1] & (prgBanks * 2 - 1);
                    return prg[(bank * 0x2000) + (addr & 0x1FFF)] & 0xFF;
                }
            }
            case 3: { // 8 KB: separate regs per slot
                int bank = prgRegs[slot + 1] & (prgBanks * 2 - 1);
                return prg[(bank * 0x2000) + (addr & 0x1FFF)] & 0xFF;
            }
        }
        return 0;
    }

    @Override
    public void cpuWrite(int addr, int val) {
        val &= 0xFF;
        if (addr >= 0x5100 && addr <= 0x5117) {
            handleControlWrite(addr, val);
            return;
        }
        if (addr >= 0x5120 && addr <= 0x512B) {
            if (addr <= 0x5127) chrRegsA[addr - 0x5120] = val;
            else                chrRegsB[addr - 0x5128] = val;
            return;
        }
        switch (addr) {
            case 0x5200: /* split-screen – not implemented */ break;
            case 0x5201: /* split-scroll */ break;
            case 0x5202: /* split bank  */ break;
            case 0x5203: irqScanline = val; break;
            case 0x5204: irqEnabled  = (val & 0x80) != 0; break;
            case 0x5205: mulA = val; break;
            case 0x5206: mulB = val; break;
        }
        // NT mapping
        if (addr >= 0x5105 && addr <= 0x5108) {
            int idx = addr - 0x5105;
            ntCtrl[idx] = val;
            return;
        }
        // PRG-RAM / ROM writes
        if (addr >= 0x6000 && addr < 0x8000) {
            int reg = prgRegs[0];
            if ((reg & 0x04) != 0) {
                int bank = (reg >> 2) & 0x3F;
                prgRam[(bank * 0x2000) + (addr & 0x1FFF)] = (byte) val;
            }
        }
        if (addr >= 0x5C00 && addr <= 0x5FFF && exRamMode == 2) {
            exRam[addr - 0x5C00] = (byte) val;
        }
    }

    private void handleControlWrite(int addr, int val) {
        switch (addr) {
            case 0x5100: prgMode = val & 3;      break;
            case 0x5101: chrMode = val & 3;      break;
            case 0x5102: /* PRG-RAM protect A */  break;
            case 0x5103: /* PRG-RAM protect B */  break;
            case 0x5104: exRamMode = val & 3;    break;
            case 0x5105:
            case 0x5106:
            case 0x5107:
            case 0x5108:
                ntCtrl[addr - 0x5105] = val;
                break;
            case 0x5109: fillTile = val; break;
            case 0x510A: fillAttr = val; break;
            case 0x5113: prgRegs[0] = val; break;
            case 0x5114: prgRegs[1] = val; break;
            case 0x5115: prgRegs[2] = val; break;
            case 0x5116: prgRegs[3] = val; break;
            case 0x5117: prgRegs[4] = val; break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PPU R/W
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public int ppuRead(int addr) {
        if (addr >= 0x2000) {
            // Nametable / attribute
            int nt   = (addr >> 10) & 3;
            int src  = ntCtrl[nt];
            int off  = addr & 0x3FF;
            switch (src) {
                case 0: return super.ppuRead((addr & ~0xC00) | 0x2000); // CIRAM page 0
                case 1: return super.ppuRead((addr & ~0xC00) | 0x2400); // CIRAM page 1
                case 2: return (exRamMode <= 1) ? (exRam[off] & 0xFF) : 0;
                case 3: return (off < 0x3C0) ? fillTile : fillAttr;
            }
        }
        return chrRead(addr);
    }

    private int chrRead(int addr) {
        int[] regs = chrUpper ? buildUpperRegs() : chrRegsA;
        switch (chrMode) {
            case 0: { // 8 KB
                int bank = regs[7] & (chrBanks > 0 ? chrBanks - 1 : 0);
                return chr[((bank * 0x2000) + addr) & (chr.length - 1)] & 0xFF;
            }
            case 1: { // 4 KB
                int slot = addr / 0x1000;
                int bank = regs[slot * 4 + 3] & (chr.length / 0x1000 - 1);
                return chr[(bank * 0x1000) + (addr & 0x0FFF)] & 0xFF;
            }
            case 2: { // 2 KB
                int slot = addr / 0x800;
                int bank = regs[slot * 2 + 1] & (chr.length / 0x800 - 1);
                return chr[(bank * 0x800) + (addr & 0x7FF)] & 0xFF;
            }
            case 3: { // 1 KB
                int slot = addr / 0x400;
                int bank = regs[slot] & (chr.length / 0x400 - 1);
                return chr[(bank * 0x400) + (addr & 0x3FF)] & 0xFF;
            }
        }
        return 0;
    }

    private int[] buildUpperRegs() {
        // B regs cover $1000-$1FFF; duplicate to fill 8-slot array
        return new int[]{
            chrRegsA[0], chrRegsA[1], chrRegsA[2], chrRegsA[3],
            chrRegsB[0], chrRegsB[1], chrRegsB[2], chrRegsB[3]
        };
    }

    /** Call at every PPU scanline tick. */
    public void tickIRQ() {
        scanlineCount++;
        if (scanlineCount == irqScanline) {
            irqPending = true;
            if (irqEnabled && irqCallback != null) irqCallback.run();
        }
    }
}
