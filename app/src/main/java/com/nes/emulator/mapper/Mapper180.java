package com.nes.emulator.mapper;

/**
 * Mapper 180 – Crazy Climber (Nichibutsu).
 *
 * Inverse of Mapper 2 (UxROM):
 *   - $8000-$BFFF is fixed to bank 0.
 *   - $C000-$FFFF is switchable.
 * CHR-RAM, no switching.
 */
public class Mapper180 extends Mapper {

    private int prgBank;

    Mapper180(byte[] prg, byte[] chr, int prgBanks, int chrBanks, int mirror) {
        super(prg, chr, prgBanks, chrBanks, mirror);
    }

    @Override
    public int cpuRead(int addr) {
        if (addr < 0x8000) return 0;
        int bank = (addr < 0xC000) ? 0 : prgBank;
        return prg[(bank * 0x4000) + (addr & 0x3FFF)] & 0xFF;
    }

    @Override
    public void cpuWrite(int addr, int val) {
        if (addr >= 0x8000) prgBank = val & 0x07;
    }
}
