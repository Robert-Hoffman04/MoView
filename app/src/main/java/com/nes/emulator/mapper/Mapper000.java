package com.nes.emulator.mapper;

/** Mapper 0 – NROM. No bank switching. */
public class Mapper000 extends Mapper {
    Mapper000(byte[] prg, byte[] chr, int prgBanks, int chrBanks, int mirror) {
        super(prg, chr, prgBanks, chrBanks, mirror);
    }

    @Override
    public int cpuRead(int addr) {
        if (addr < 0x8000) return 0;
        // Mirror if only 1 bank (16KB)
        int idx = addr & (prgBanks > 1 ? 0x7FFF : 0x3FFF);
        return prg[idx] & 0xFF;
    }

    @Override
    public void cpuWrite(int addr, int val) { /* ROM - no writes */ }
}
