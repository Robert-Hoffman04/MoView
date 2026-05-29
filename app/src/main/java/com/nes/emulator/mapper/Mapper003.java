package com.nes.emulator.mapper;

/** Mapper 3 – CNROM. CHR bank switching only. */
public class Mapper003 extends Mapper {
    private int chrBank;

    Mapper003(byte[] prg, byte[] chr, int prgBanks, int chrBanks, int mirror) {
        super(prg, chr, prgBanks, chrBanks, mirror);
    }

    @Override
    public int cpuRead(int addr) {
        if (addr < 0x8000) return 0;
        int idx = addr & (prgBanks > 1 ? 0x7FFF : 0x3FFF);
        return prg[idx] & 0xFF;
    }

    @Override
    public void cpuWrite(int addr, int val) {
        if (addr >= 0x8000) chrBank = val & 0x03;
    }

    @Override
    public int ppuRead(int addr) {
        // Pattern tables only
        if (addr >= 0x2000) return 0;
        int bankAddr = (chrBank * 0x2000) + (addr & 0x1FFF);
        return chr[bankAddr % chr.length] & 0xFF;
    }
}