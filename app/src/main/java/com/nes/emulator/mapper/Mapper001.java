package com.nes.emulator.mapper;

/** Mapper 1 – SxROM (MMC1) with WRAM, proper mirroring, and safe CHR indexing. */
public class Mapper001 extends Mapper {
    private int shiftRegister = 0x10;
    private int shiftCount;
    private int control  = 0x0C;
    private int chrBank0, chrBank1;
    private int prgBank;

    Mapper001(byte[] prg, byte[] chr, int prgBanks, int chrBanks, int mirror) {
        super(prg, chr, prgBanks, chrBanks, mirror);
        initWRAM();  // allocate 8KB PRG-RAM (battery-backed if needed)
    }

    @Override
    public int cpuRead(int addr) {
        if (addr >= 0x6000 && addr < 0x8000) {
            return wramRead(addr);
        }
        if (addr < 0x8000) return 0;

        int prgMode = (control >> 2) & 3;
        int bank;
        if (prgMode <= 1) {
            // 32 KB mode: ignore lower bit, map both 16KB halves
            bank = (prgBank & ~1) + ((addr < 0xC000) ? 0 : 1);
            return prgRead(addr, bank % prgBanks);
        } else if (prgMode == 2) {
            // Fix first bank at 0, switch 0xC000
            bank = (addr < 0xC000) ? 0 : prgBank;
            return prgRead(addr, bank % prgBanks);
        } else {
            // Fix last bank at 0xC000, switch 0x8000
            bank = (addr < 0xC000) ? prgBank : (prgBanks - 1);
            return prgRead(addr, bank % prgBanks);
        }
    }

    @Override
    public void cpuWrite(int addr, int val) {
        if (addr >= 0x6000 && addr < 0x8000) {
            wramWrite(addr, val);
            return;
        }
        if (addr < 0x8000) return;

        if ((val & 0x80) != 0) {
            shiftRegister = 0x10;
            shiftCount = 0;
            // Do NOT modify control register here! (fixed)
            return;
        }
        shiftRegister = ((val & 1) << 4) | (shiftRegister >> 1);
        shiftCount++;
        if (shiftCount == 5) {
            int reg = (addr >> 13) & 3;
            switch (reg) {
                case 0:
                    control = shiftRegister;
                    // Control bits 1:0: 0=one-screen low, 1=one-screen high, 2=vertical, 3=horizontal
                    switch (control & 3) {
                        case 0: mirrorMode = 2; break; // one-screen, lower bank
                        case 1: mirrorMode = 3; break; // one-screen, upper bank
                        case 2: mirrorMode = 1; break; // vertical
                        case 3: mirrorMode = 0; break; // horizontal
                    }
                    // WRAM enable (bit 2)
                    wramEnabled = (control & 0x04) != 0;
                    wramWriteProtect = !wramEnabled; // typical MMC1: enabled = writable
                    break;
                case 1: chrBank0 = shiftRegister; break;
                case 2: chrBank1 = shiftRegister; break;
                case 3: prgBank  = shiftRegister & 0x0F; break;
            }
            shiftRegister = 0x10;
            shiftCount = 0;
        }
    }

    @Override
    public int ppuRead(int addr) {
        // Pattern tables only (0x0000-0x1FFF); nametables are handled by PPU mirroring
        if (addr >= 0x2000) return 0;

        int chrMode = (control >> 4) & 1;
        int bank;
        if (chrMode == 0) {
            bank = (chrBank0 & ~1) + (addr < 0x1000 ? 0 : 1);
        } else {
            bank = addr < 0x1000 ? chrBank0 : chrBank1;
        }
        int bankAddr = bank * 0x1000 + (addr & 0x0FFF);
        return chr[bankAddr % chr.length] & 0xFF;
    }
}