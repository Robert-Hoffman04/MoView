package com.nes.emulator.mapper;

/**
 * Mapper 9 – PxROM (MMC2).
 *
 * Used exclusively by Mike Tyson's Punch-Out!! / Punch-Out!!
 *
 * PRG: first 8 KB bank switchable, remaining three 8 KB banks
 *      fixed to the last three banks of the ROM.
 * CHR: two 4 KB windows, each with a latch that switches between
 *      two candidate banks whenever tile $FD or $FE is fetched.
 */
public class Mapper009 extends Mapper {

    private int prgBank;

    // CHR latch state (0=$FD bank, 1=$FE bank)
    private int latch0 = 0, latch1 = 0;

    // CHR bank registers
    private int chrBank0fd, chrBank0fe;
    private int chrBank1fd, chrBank1fe;

    Mapper009(byte[] prg, byte[] chr, int prgBanks, int chrBanks, int mirror) {
        super(prg, chr, prgBanks, chrBanks, mirror);
    }

    @Override
    public int cpuRead(int addr) {
        if (addr < 0x8000) return 0;
        int numSlots = prgBanks * 2; // 8 KB slots
        if (addr < 0xA000) {
            // Switchable first 8 KB
            int bank = prgBank % numSlots;
            return prg[(bank * 0x2000) + (addr & 0x1FFF)] & 0xFF;
        }
        // Fixed last three 8 KB banks
        int slot = (addr - 0xA000) / 0x2000 + (numSlots - 3);
        slot = Math.max(0, slot);
        return prg[(slot * 0x2000) + (addr & 0x1FFF)] & 0xFF;
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
                mirrorMode = (val & 1) == 0 ? 0 : 1; // 0=H, 1=V
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
        if (tile == 0x0FD8) latch0 = 0; // fetched $FD tile -> switch to FD bank next
        else if (tile == 0x0FE8) latch0 = 1;
    }

    private void updateLatch1(int addr) {
        int tile = addr & 0x0FF8;
        if (tile == 0x0FD8) latch1 = 0;
        else if (tile == 0x0FE8) latch1 = 1;
    }
}
