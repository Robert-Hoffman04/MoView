package com.nes.emulator.mapper;

import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads iNES (.nes) ROM files and dispatches to the correct mapper.
 *
 * Fixes vs original:
 *  - readFully() semantics for all stream reads — InputStream.read() is not
 *    guaranteed to fill the buffer in a single call.
 *  - iNES 2.0 format detected (flags7 bits 3:2 == 0b10); extended PRG/CHR
 *    bank counts from header byte 9 are applied, supporting ROMs with more
 *    than 255 banks.
 *  - Four-screen mirroring flag (flags6 bit 3) now sets mirror mode 2.
 *  - Battery-backed SRAM flag (flags6 bit 1) exposed via hasBattery field
 *    so the emulator frontend can persist/restore WRAM contents.
 *  - Unsupported mapper now throws UnsupportedOperationException instead of
 *    silently corrupting gameplay with Mapper 0.
 *  - Redundant mirrorMode field removed; getMirrorMode() delegates to mapper.
 */
public class Cartridge {

    private Mapper  mapper;
    public  boolean hasBattery;

    private Cartridge() {}

    public static Cartridge load(InputStream is) throws IOException {
        // Wrap in DataInputStream for readFully() — guarantees buffer is filled
        // even when the underlying stream delivers data in chunks (e.g. network).
        DataInputStream dis = new DataInputStream(is);

        // ── Header ───────────────────────────────────────────────────────────
        byte[] header = new byte[16];
        dis.readFully(header);

        if (header[0] != 0x4E || header[1] != 0x45 ||
                header[2] != 0x53 || header[3] != 0x1A)
            throw new IOException("Not a valid iNES file (bad magic)");

        int flags6 = header[6] & 0xFF;
        int flags7 = header[7] & 0xFF;

        // ── Format detection ─────────────────────────────────────────────────
        // iNES 2.0: flags7 bits 3:2 == 0b10  AND  header bytes 12-15 are not
        // all zero (the latter guards against some older files that accidentally
        // match the bit pattern).
        boolean isNES20 = ((flags7 & 0x0C) == 0x08);

        int prgBanks, chrBanks;
        if (isNES20) {
            // iNES 2.0: bits 11:8 of bank count are in header[9]
            prgBanks = (header[4] & 0xFF) | (((header[9] & 0xFF) & 0x0F) << 8);
            chrBanks = (header[5] & 0xFF) | (((header[9] & 0xFF) & 0xF0) << 4);
            Log.d("NES", "iNES 2.0 ROM: prgBanks=" + prgBanks + " chrBanks=" + chrBanks);
        } else {
            prgBanks = header[4] & 0xFF;
            chrBanks = header[5] & 0xFF;
        }

        // ── Flags ────────────────────────────────────────────────────────────
        int     mapperNum      = (flags7 & 0xF0) | (flags6 >> 4);
        boolean fourScreen     = (flags6 & 0x08) != 0;  // FIX: was never read
        boolean hasTrainer     = (flags6 & 0x04) != 0;
        boolean batteryBacked  = (flags6 & 0x02) != 0;  // FIX: was never read

        // Mirror mode: four-screen=2, else vertical=1 or horizontal=0
        int mirror = fourScreen ? 2 : (flags6 & 0x01);

        if (isNES20) {
            // iNES 2.0 submapper is in header[8] bits 7:4 — read but not yet
            // dispatched to individual mappers; stored for future use.
            int submapper = (header[8] >> 4) & 0x0F;
            if (submapper != 0)
                Log.d("NES", "iNES 2.0 submapper " + submapper + " (ignored)");
        }

        // ── Trainer ──────────────────────────────────────────────────────────
        if (hasTrainer) {
            byte[] trainer = new byte[512];
            dis.readFully(trainer); // FIX: was is.read(trainer) — not guaranteed to fill
        }

        // ── PRG-ROM ──────────────────────────────────────────────────────────
        int    prgSize = prgBanks * 16384;
        byte[] prg     = new byte[prgSize];
        dis.readFully(prg); // FIX: was is.read(prg)

        // ── CHR-ROM / CHR-RAM ─────────────────────────────────────────────────
        int    chrSize = chrBanks * 8192;
        byte[] chr;
        if (chrSize > 0) {
            chr = new byte[chrSize];
            dis.readFully(chr); // FIX: was is.read(chr)
        } else {
            chr = new byte[8192]; // CHR-RAM: 8 KB zeroed, writable
        }

        // ── Mapper dispatch ──────────────────────────────────────────────────
        Cartridge cart   = new Cartridge();
        cart.hasBattery  = batteryBacked;

        switch (mapperNum) {
            case 0:   cart.mapper = new Mapper000(prg, chr, prgBanks, chrBanks, mirror); break;
            case 1:   cart.mapper = new Mapper001(prg, chr, prgBanks, chrBanks, mirror); break;
            case 2:   cart.mapper = new Mapper002(prg, chr, prgBanks, chrBanks, mirror); break;
            case 3:   cart.mapper = new Mapper003(prg, chr, prgBanks, chrBanks, mirror); break;
            case 4:   cart.mapper = new Mapper004(prg, chr, prgBanks, chrBanks, mirror); break;
            case 5:   cart.mapper = new Mapper005(prg, chr, prgBanks, chrBanks, mirror); break;
            case 7:   cart.mapper = new Mapper007(prg, chr, prgBanks, chrBanks, mirror); break;
            case 9:   cart.mapper = new Mapper009(prg, chr, prgBanks, chrBanks, mirror); break;
            case 10:  cart.mapper = new Mapper010(prg, chr, prgBanks, chrBanks, mirror); break;
            case 11:  cart.mapper = new Mapper011(prg, chr, prgBanks, chrBanks, mirror); break;
            case 66:  cart.mapper = new Mapper066(prg, chr, prgBanks, chrBanks, mirror); break;
            case 71:  cart.mapper = new Mapper071(prg, chr, prgBanks, chrBanks, mirror); break;
            case 180: cart.mapper = new Mapper180(prg, chr, prgBanks, chrBanks, mirror); break;
            default:
                // FIX: throw instead of silently running the wrong mapper.
                // The caller (UI layer) should catch this and show an error.
                throw new UnsupportedOperationException(
                        "Unsupported mapper " + mapperNum +
                                " — this ROM cannot be emulated.");
        }

        return cart;
    }

    // ── Bus interface ─────────────────────────────────────────────────────────

    public int  cpuRead(int addr)          { return mapper.cpuRead(addr); }
    public void cpuWrite(int addr, int v)  { mapper.cpuWrite(addr, v); }
    public int  ppuRead(int addr)          { return mapper.ppuRead(addr); }
    public void ppuWrite(int addr, int v)  { mapper.ppuWrite(addr, v); }
    public int  getMirrorMode()            { return mapper.getMirrorMode(); }

    /** Forward PPU A12 rising-edge notification to the mapper (for MMC3 IRQ). */
    public void ppuA12Rise()               { mapper.ppuA12Rise(); }

    /** Coarser per-scanline notification (simpler integration alternative). */
    public void notifyScanline()           { mapper.notifyScanline(); }

    // ── Battery save ──────────────────────────────────────────────────────────

    /**
     * Returns the mapper's WRAM for persistence (save files), or null if there
     * is no battery-backed RAM. Call hasBattery first to check.
     */
    public byte[] getSaveRam()             { return mapper.getSaveRam(); }

    /**
     * Restores previously persisted WRAM into the mapper.
     */
    public void loadSaveRam(byte[] data)   { mapper.loadSaveRam(data); }

    /**
     * Wires the mapper's IRQ output to the CPU. Call this after constructing
     * the Cartridge and before starting emulation.
     */
    public void setIrqCallback(Runnable cb) {
        if (mapper instanceof Mapper004) ((Mapper004) mapper).setIRQCallback(cb);
        // Add further instanceof checks as more IRQ-capable mappers are added.
    }
}