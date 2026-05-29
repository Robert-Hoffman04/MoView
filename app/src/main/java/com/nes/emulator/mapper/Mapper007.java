package com.nes.emulator.mapper;

/**
 * Mapper 7 – AxROM (AOROM / Rare games, Battletoads, Marble Madness, etc.)
 *
 * - 32 KB PRG bank switching.
 * - Single-screen mirroring (low bit of bank selects which CIRAM page).
 * - CHR-RAM (no CHR-ROM switching).
 */
public class Mapper007 extends Mapper {

    private int prgBank;

    Mapper007(byte[] prg, byte[] chr, int prgBanks, int chrBanks, int mirror) {
        super(prg, chr, prgBanks, chrBanks, mirror);
    }

    @Override
    public int cpuRead(int addr) {
        if (addr < 0x8000) return 0;
        int offset = (prgBank * 0x8000) + (addr & 0x7FFF);
        return prg[offset % prg.length] & 0xFF;
    }

    @Override
    public void cpuWrite(int addr, int val) {
        if (addr < 0x8000) return;
        prgBank   = val & 0x07;
        // Bit 4 selects single-screen page: 0=lower, 1=upper
        mirrorMode = (val & 0x10) != 0 ? 1 : 0;
    }
}
