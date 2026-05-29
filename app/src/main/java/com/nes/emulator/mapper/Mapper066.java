package com.nes.emulator.mapper;

/**
 * Mapper 66 – GxROM (GNROM/MHROM).
 *
 * - PRG: 32 KB bank switching via bits 5:4.
 * - CHR: 8 KB bank switching via bits 1:0.
 * - Used by Doraemon, Dragon Power, Gumshoe, and others.
 */
public class Mapper066 extends Mapper {

    private int prgBank;
    private int chrBank;

    Mapper066(byte[] prg, byte[] chr, int prgBanks, int chrBanks, int mirror) {
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
        prgBank = (val >> 4) & 0x03;
        chrBank =  val       & 0x03;
    }

    @Override
    public int ppuRead(int addr) {
        if (addr >= 0x2000) return super.ppuRead(addr);
        int idx = (chrBank * 0x2000) + (addr & 0x1FFF);
        return chr[idx % chr.length] & 0xFF;
    }
}
