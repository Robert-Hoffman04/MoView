package com.nes.emulator.mapper;

/**
 * Mapper 71 – Camerica / Codemasters (BxROM variant).
 *
 * - PRG: 16 KB bank switchable at $8000-$BFFF; last bank fixed at $C000.
 * - CHR-RAM (8 KB, no switching).
 * - Single-screen mirroring selectable via $9000-$9FFF writes (bit 4).
 *
 * Games: Fire Hawk, Micro Machines, Fantastic Dizzy, Pegasus 4-in-1.
 */
public class Mapper071 extends Mapper {

    private int prgBank;

    Mapper071(byte[] prg, byte[] chr, int prgBanks, int chrBanks, int mirror) {
        super(prg, chr, prgBanks, chrBanks, mirror);
    }

    @Override
    public int cpuRead(int addr) {
        if (addr < 0x8000) return 0;
        int bank = (addr < 0xC000) ? prgBank : (prgBanks - 1);
        return prg[(bank * 0x4000) + (addr & 0x3FFF)] & 0xFF;
    }

    @Override
    public void cpuWrite(int addr, int val) {
        if (addr < 0x8000) return;
        if (addr < 0xA000) {
            // $8000-$9FFF: single-screen mirror select (Fire Hawk only)
            mirrorMode = (val & 0x10) != 0 ? 1 : 0;
        } else {
            // $A000-$FFFF: PRG bank select
            prgBank = val & 0x0F;
        }
    }
}
