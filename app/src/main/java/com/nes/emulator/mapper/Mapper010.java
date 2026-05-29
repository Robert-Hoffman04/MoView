package com.nes.emulator.mapper;

/**
 * Mapper 10 – MMC4 (FxROM).
 *
 * Used by Fire Emblem (Japan) and Fire Emblem Gaiden (Japan).
 *
 * Like MMC2 (Mapper 9) but:
 *   - PRG: first 16 KB switchable, last 16 KB fixed.
 *   - CHR latches work the same way ($FD/$FE tile detection).
 */
public class Mapper010 extends Mapper {

    private int prgBank;

    private int latch0 = 0, latch1 = 0;

    private int chrBank0fd, chrBank0fe;
    private int chrBank1fd, chrBank1fe;

    Mapper010(byte[] prg, byte[] chr, int prgBanks, int chrBanks, int mirror) {
        super(prg, chr, prgBanks, chrBanks, mirror);
    }

    @Override
    public int cpuRead(int addr) {
        if (addr < 0x8000) return 0;
        if (addr < 0xC000) {
            // Switchable 16 KB
            int bank = prgBank % prgBanks;
            return prg[(bank * 0x4000) + (addr & 0x3FFF)] & 0xFF;
        }
        // Fixed last 16 KB
        int bank = prgBanks - 1;
        return prg[(bank * 0x4000) + (addr & 0x3FFF)] & 0xFF;
    }

    @Override
    public void cpuWrite(int addr, int val) {
        val &= 0xFF;
        if (addr < 0xA000) return;
        switch ((addr >> 12) & 0xF) {
            case 0xA: prgBank    = val & 0x0F; break;
            case 0xB: chrBank0fd = val & 0x1F; break;
            case 0xC: chrBank0fe = val & 0x1F; break;
            case 0xD: chrBank1fd = val & 0x1F; break;
            case 0xE: chrBank1fe = val & 0x1F; break;
            case 0xF:
                mirrorMode = (val & 1) == 0 ? 0 : 1;
                break;
        }
    }

    @Override
    public int ppuRead(int addr) {
        if (addr >= 0x2000) return super.ppuRead(addr);

        int result;
        if (addr < 0x1000) {
            int bank = (latch0 == 0) ? chrBank0fd : chrBank0fe;
            result = chr[(bank * 0x1000) + (addr & 0x0FFF)] & 0xFF;
            updateLatch0(addr);
        } else {
            int bank = (latch1 == 0) ? chrBank1fd : chrBank1fe;
            result = chr[(bank * 0x1000) + (addr & 0x0FFF)] & 0xFF;
            updateLatch1(addr);
        }
        return result;
    }

    private void updateLatch0(int addr) {
        int tile = addr & 0x0FF8;
        if (tile == 0x0FD8) latch0 = 0;
        else if (tile == 0x0FE8) latch0 = 1;
    }

    private void updateLatch1(int addr) {
        int tile = addr & 0x0FF8;
        if (tile == 0x0FD8) latch1 = 0;
        else if (tile == 0x0FE8) latch1 = 1;
    }
}
