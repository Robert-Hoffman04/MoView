package com.nes.emulator;

import com.nes.emulator.apu.APU;
import com.nes.emulator.cpu.CPU6502;
import com.nes.emulator.input.Controller;
import com.nes.emulator.mapper.Cartridge;
import com.nes.emulator.memory.Bus;
import com.nes.emulator.ppu.PPU;
import java.nio.IntBuffer;

/**
 * Top-level NES system. Sequentially advances CPU, PPU, and APU.
 */
public class NES {

    public final Bus     bus;
    public final CPU6502 cpu;
    public final PPU     ppu;
    public final APU     apu;

    /** Callback receiving the completed frame's pixel buffer. */
    public interface FrameCallback { void onFrame(IntBuffer pixels); }
    private final FrameCallback frameCallback;

    public NES(Cartridge cartridge, Controller ctrl1, Controller ctrl2, FrameCallback cb) {
        this.frameCallback = cb;
        apu = new APU();
        bus = new Bus(cartridge, apu, ctrl1, ctrl2);
        cpu = new CPU6502(bus);
        ppu = new PPU(bus, cpu::triggerNMI);
        bus.setPPU(ppu);
    }

    public void reset() { cpu.reset(); }

    /**
     * Run one complete video frame (~29780 CPU cycles).
     */
    public void runFrame() {
        int framesBefore = ppu.getFrames();
        while (ppu.getFrames() == framesBefore) {
            int cycles;
            if (bus.isDmaPending()) {
                bus.doDMA();
                cycles = 513;
            } else {
                cycles = cpu.step();
            }
            ppu.tickBatch(cycles * 3);
            apu.tick(cycles);
        }
        frameCallback.onFrame(ppu.frameBuffer);
    }

    public void destroy() { apu.destroy(); }
}