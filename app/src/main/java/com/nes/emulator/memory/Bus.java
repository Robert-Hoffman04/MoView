package com.nes.emulator.memory;

import com.nes.emulator.apu.APU;
import com.nes.emulator.input.Controller;
import com.nes.emulator.mapper.Cartridge;
import com.nes.emulator.ppu.PPU;

public class Bus {

    private final int[] ram = new int[0x800];

    private PPU        ppu;
    private final APU        apu;
    private final Cartridge  cartridge;
    private final Controller controller1;
    private final Controller controller2;

    private boolean dmaPending;
    private int     dmaPage;

    // Open-bus: last value placed on the data bus by any read or write.
    private int openBus = 0;

    private int dmaStallCycles = 0;
    private boolean dmcDmaReadSinceCpu = false;

    public Bus(Cartridge cartridge, APU apu, Controller ctrl1, Controller ctrl2) {
        this.cartridge   = cartridge;
        this.apu         = apu;
        this.controller1 = ctrl1;
        this.controller2 = ctrl2;
    }

    public void setPPU(PPU ppu) { this.ppu = ppu; }

    public int consumeDmaStall() {
        int s = dmaStallCycles;
        dmaStallCycles = 0;
        return s;
    }

    public boolean isDmaPending() { return dmaPending; }

    public int readPRG(int addr) {
        return cartridge.cpuRead(addr);
    }

    public int dmcRead(int addr) {
        int val = cpuRead(addr);
        dmcDmaReadSinceCpu = true;
        return val;
    }

    public boolean consumeDmcDmaReadSinceCpu() {
        boolean happened = dmcDmaReadSinceCpu;
        dmcDmaReadSinceCpu = false;
        return happened;
    }

    public int cpuRead(int addr) {
        addr &= 0xFFFF;
        int val;
        boolean updateOpenBus = true;

        if (addr < 0x2000) {
            val = ram[addr & 0x07FF] & 0xFF;
        } else if (addr < 0x4000) {
            val = ppu.readRegister(addr & 0x07);
        } else if (addr == 0x4015) {
            val = (apu.readStatus() & 0xDF) | (openBus & 0x20);
            updateOpenBus = false;
        } else if (addr == 0x4016) {
            val = (openBus & 0xE0) | (controller1.read() & 0x01);
        } else if (addr == 0x4017) {
            val = (openBus & 0xE0) | (controller2.read() & 0x01);
        } else if (addr >= 0x4020) {
            val = cartridge.cpuRead(addr);
            if (val < 0) val = openBus;
            else val &= 0xFF;
        } else {
            val = openBus;
        }

        if (updateOpenBus) openBus = val & 0xFF;
        return val;
    }

    public void cpuWrite(int addr, int val) {
        addr &= 0xFFFF;
        val  &= 0xFF;

        if (addr < 0x2000) {
            ram[addr & 0x07FF] = val;
        } else if (addr < 0x4000) {
            ppu.writeRegister(addr & 0x07, val);
        } else if (addr == 0x4014) {
            dmaPending     = true;
            dmaPage        = val;
            dmaStallCycles = 513;
        } else if (addr == 0x4016) {
            controller1.write(val);
            controller2.write(val);
        } else if (addr < 0x4018) {
            apu.write(addr, val);
        } else if (addr >= 0x4020) {
            cartridge.cpuWrite(addr, val);
        }

        openBus = val;
    }

    public int  ppuRead(int addr)           { return cartridge.ppuRead(addr & 0x3FFF); }
    public void ppuWrite(int addr, int val) { cartridge.ppuWrite(addr & 0x3FFF, val); }
    public int  getMirrorMode()             { return cartridge.getMirrorMode(); }

    public void doDMA() {
        int base = dmaPage << 8;
        for (int i = 0; i < 256; i++) {
            int val = cpuRead((base + i) & 0xFFFF);
            ppu.writeOAMByte(i, val & 0xFF);
        }
        dmaPending = false;
    }
}