package com.nes.emulator.mapper;

/** Mapper 2 – UxROM (Mega Man, Castlevania, etc.) */
public class Mapper002 extends Mapper {
    private int prgBank;

    Mapper002(byte[] prg, byte[] chr, int prgBanks, int chrBanks, int mirror) {
        super(prg, chr, prgBanks, chrBanks, mirror);
    }

    @Override
    public int cpuRead(int addr) {
        if (addr < 0x8000) return 0;
        int bank = (addr < 0xC000) ? prgBank : (prgBanks - 1);
        return prgRead(addr, bank);
    }

    @Override
    public void cpuWrite(int addr, int val) {
        if (addr >= 0x8000) prgBank = val & 0x0F;
    }
}