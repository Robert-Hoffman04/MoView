package com.nes.emulator.mapper;

/** Mapper 4 – TxROM (MMC3) with WRAM, fixed IRQ, and proper PPU address handling. */
public class Mapper004 extends Mapper {
    private int[] r = new int[8];
    private int bankSelect;
    private boolean prgMode, chrMode;
    private int irqCounter, irqLatch;
    private boolean irqEnabled, irqReload;
    private Runnable irqCallback;

    Mapper004(byte[] prg, byte[] chr, int prgBanks, int chrBanks, int mirror) {
        super(prg, chr, prgBanks, chrBanks, mirror);
        r[6] = 0;
        r[7] = 1;
        initWRAM();  // 8KB PRG-RAM at $6000-$7FFF (battery-backed)
    }

    public void setIRQCallback(Runnable cb) { irqCallback = cb; }

    @Override
    public int cpuRead(int addr) {
        if (addr >= 0x6000 && addr < 0x8000) {
            return wramRead(addr);
        }
        if (addr < 0x8000) return 0;
        int[] banks = getPRGBanks();
        int slot = (addr - 0x8000) / 0x2000;
        int bank = banks[slot];
        return prg[(bank * 0x2000) + (addr & 0x1FFF)] & 0xFF;
    }

    @Override
    public void cpuWrite(int addr, int val) {
        if (addr >= 0x6000 && addr < 0x8000) {
            wramWrite(addr, val);
            return;
        }
        if (addr < 0x8000) return;

        val &= 0xFF;
        boolean even = (addr & 1) == 0;
        if (addr < 0xA000) {
            if (even) {
                bankSelect = val & 7;
                prgMode = (val & 0x40) != 0;
                chrMode = (val & 0x80) != 0;
            } else {
                r[bankSelect] = val;
            }
        } else if (addr < 0xC000) {
            if (even) {
                mirrorMode = (val & 1) == 0 ? 1 : 0;
            }
        } else if (addr < 0xE000) {
            if (even) {
                irqLatch = val;
            } else {
                // $C001 write: set reload flag, do NOT clear counter
                irqReload = true;
            }
        } else {
            // $E000 (even) = disable IRQ, $E001 (odd) = enable IRQ
            irqEnabled = !even;
            if (!irqEnabled && irqCallback != null) {
                // When disabled, ensure IRQ line is cleared
                irqCallback.run(); // some emulators require a "clear" method; adjust as needed
            }
        }
    }

    private int[] getPRGBanks() {
        int last = prgBanks * 2 - 1;
        if (!prgMode) {
            return new int[]{r[6], r[7], last - 1, last};
        } else {
            return new int[]{last - 1, r[7], r[6], last};
        }
    }

    @Override
    public int ppuRead(int addr) {
        // Pattern tables only (0x0000-0x1FFF)
        if (addr >= 0x2000) return 0;
        int[] banks = getCHRBanks();
        int slot = addr / 0x400;
        int bank = banks[slot];
        return chr[(bank * 0x400) + (addr & 0x3FF)] & 0xFF;
    }

    @Override
    public void ppuWrite(int addr, int val) {
        // CHR-RAM is only at 0x0000-0x1FFF
        if (addr >= 0x2000) return;
        int[] banks = getCHRBanks();
        int slot = addr / 0x400;
        int bank = banks[slot];
        chr[(bank * 0x400) + (addr & 0x3FF)] = (byte) val;
    }

    private int[] getCHRBanks() {
        if (!chrMode) {
            return new int[]{
                    r[0] & ~1, r[0] | 1,
                    r[1] & ~1, r[1] | 1,
                    r[2], r[3], r[4], r[5]
            };
        } else {
            return new int[]{
                    r[2], r[3], r[4], r[5],
                    r[0] & ~1, r[0] | 1,
                    r[1] & ~1, r[1] | 1
            };
        }
    }

    /** Called on every PPU A12 rising edge (each scanline). */
    @Override
    public void ppuA12Rise() {
        tickIRQ();
    }

    private void tickIRQ() {
        if (irqReload) {
            irqCounter = irqLatch;
            irqReload = false;
        } else if (irqCounter > 0) {
            irqCounter--;
        }
        if (irqCounter == 0 && irqEnabled && irqCallback != null) {
            irqCallback.run();
        }
    }
}