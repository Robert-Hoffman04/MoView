package com.nes.emulator.mapper;

/** Mapper 0 – NROM. No bank switching. */
public class Mapper000 extends Mapper {
    private final byte[] prgRam = new byte[0x2000];

    Mapper000(byte[] prg, byte[] chr, int prgBanks, int chrBanks, int mirror) {
        super(prg, chr, prgBanks, chrBanks, mirror);
    }

    @Override
    public int cpuRead(int addr) {
        addr &= 0xFFFF;
        if (addr >= 0x6000 && addr < 0x8000) return prgRam[addr & 0x1FFF] & 0xFF;
        if (addr < 0x8000) return -1; // NROM has no expansion-device response here: leave CPU bus open.
        // Mirror if only 1 bank (16KB)
        int idx = addr & (prgBanks > 1 ? 0x7FFF : 0x3FFF);
        return prg[idx] & 0xFF;
    }

    @Override
    public void cpuWrite(int addr, int val) {
        addr &= 0xFFFF;
        if (addr >= 0x6000 && addr < 0x8000) prgRam[addr & 0x1FFF] = (byte) val;
    }
}
