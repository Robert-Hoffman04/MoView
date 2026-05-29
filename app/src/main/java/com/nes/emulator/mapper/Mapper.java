package com.nes.emulator.mapper;

/**
 * Abstract base class for all NES mappers.
 *
 * Fixes vs original:
 *  - ppuRead/ppuWrite use safe modulo instead of power-of-2 mask, so non-
 *    power-of-2 CHR-ROM sizes (e.g. 3 banks = 24 576 bytes) work correctly.
 *  - prgRead clamps the bank index to the available bank count, preventing
 *    ArrayIndexOutOfBoundsException from bad mapper register values.
 *  - Shared 8 KB WRAM array + wramEnabled/wramWriteProtect flags for mappers
 *    that have PRG-RAM at $6000–$7FFF (MMC1, MMC3, etc.).
 *  - ppuA12Rise() hook so the PPU/Bus can notify the mapper of rising A12
 *    edges without knowing the concrete mapper type (used by MMC3 IRQ).
 *  - notifyScanline() kept as a coarser alternative for simpler integration.
 */
public abstract class Mapper {

    protected byte[] prg;
    protected byte[] chr;
    protected int    prgBanks, chrBanks;
    protected int    mirrorMode;

    // Shared 8 KB PRG-RAM (WRAM) at $6000–$7FFF.
    // Allocated lazily by subclasses that need it via initWRAM().
    protected byte[]  wram            = null;
    protected boolean wramEnabled     = false;
    protected boolean wramWriteProtect = false;

    Mapper(byte[] prg, byte[] chr, int prgBanks, int chrBanks, int mirror) {
        this.prg        = prg;
        this.chr        = chr;
        this.prgBanks   = prgBanks;
        this.chrBanks   = chrBanks;
        this.mirrorMode = mirror;
    }

    /** Allocates the shared 8 KB WRAM. Call from subclass constructor if needed. */
    protected void initWRAM() {
        wram        = new byte[0x2000];
        wramEnabled = true;
    }

    // ── CPU bus ───────────────────────────────────────────────────────────────

    public abstract int  cpuRead(int addr);
    public abstract void cpuWrite(int addr, int val);

    /**
     * Default WRAM read for $6000–$7FFF.
     * Subclasses that support WRAM should call this from cpuRead().
     */
    protected int wramRead(int addr) {
        if (wram == null || !wramEnabled) return 0;
        return wram[addr & 0x1FFF] & 0xFF;
    }

    /**
     * Default WRAM write for $6000–$7FFF.
     * Subclasses that support WRAM should call this from cpuWrite().
     */
    protected void wramWrite(int addr, int val) {
        if (wram == null || !wramEnabled || wramWriteProtect) return;
        wram[addr & 0x1FFF] = (byte) val;
    }

    // ── PPU bus ───────────────────────────────────────────────────────────────

    /**
     * Default CHR read using safe modulo — works for any CHR size, not just
     * powers of 2.
     */
    public int ppuRead(int addr) {
        return chr[addr % chr.length] & 0xFF;
    }

    /**
     * Default CHR write (for CHR-RAM). Uses safe modulo.
     */
    public void ppuWrite(int addr, int val) {
        if (chrBanks == 0) chr[addr % chr.length] = (byte) val;
    }

    public int getMirrorMode() { return mirrorMode; }

    // ── IRQ hooks ─────────────────────────────────────────────────────────────

    /**
     * Called by the PPU on every rising edge of address line A12.
     * Used by MMC3 for its scanline IRQ counter. Default: no-op.
     */
    public void ppuA12Rise() {}

    /**
     * Coarser alternative: called once per PPU scanline boundary.
     * Used when the integration doesn't track A12 per-cycle.
     * Default: calls ppuA12Rise() to give mappers a consistent hook.
     */
    public void notifyScanline() { ppuA12Rise(); }

    // ── PRG helpers ───────────────────────────────────────────────────────────

    /**
     * Read a byte from a 16 KB PRG bank.
     * Bank index is clamped to the available bank count so bad register
     * values never produce an ArrayIndexOutOfBoundsException.
     */
    protected int prgRead(int addr, int bank16k) {
        bank16k = ((bank16k % prgBanks) + prgBanks) % prgBanks;
        return prg[(bank16k * 0x4000) + (addr & 0x3FFF)] & 0xFF;
    }

    /**
     * Read a byte from an 8 KB PRG bank.
     * Used by MMC3 and other mappers that switch in 8 KB units.
     */
    protected int prgRead8k(int addr, int bank8k) {
        int totalBanks8k = (prg.length / 0x2000);
        bank8k = ((bank8k % totalBanks8k) + totalBanks8k) % totalBanks8k;
        return prg[(bank8k * 0x2000) + (addr & 0x1FFF)] & 0xFF;
    }

    /**
     * Read a byte from a 4 KB CHR bank.
     * Bank index is wrapped to the available CHR bank count.
     */
    protected int chrRead4k(int addr, int bank4k) {
        int totalBanks4k = Math.max(1, chr.length / 0x1000);
        bank4k = ((bank4k % totalBanks4k) + totalBanks4k) % totalBanks4k;
        return chr[(bank4k * 0x1000) + (addr & 0x0FFF)] & 0xFF;
    }

    /**
     * Read a byte from a 1 KB CHR bank.
     * Used by MMC3 which selects 1 KB CHR pages.
     */
    protected int chrRead1k(int addr, int bank1k) {
        int totalBanks1k = Math.max(1, chr.length / 0x400);
        bank1k = ((bank1k % totalBanks1k) + totalBanks1k) % totalBanks1k;
        return chr[(bank1k * 0x400) + (addr & 0x3FF)] & 0xFF;
    }

    // ── Battery save ─────────────────────────────────────────────────────────

    /**
     * Returns the WRAM contents for battery-save persistence, or null if this
     * mapper has no battery-backed RAM.
     */
    public byte[] getSaveRam() { return wram; }

    /**
     * Restores previously saved WRAM contents (e.g. on ROM load).
     */
    public void loadSaveRam(byte[] data) {
        if (wram != null && data != null)
            System.arraycopy(data, 0, wram, 0, Math.min(data.length, wram.length));
    }
}