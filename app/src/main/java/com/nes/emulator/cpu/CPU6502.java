package com.nes.emulator.cpu;

import com.nes.emulator.memory.Bus;

/**
 * Cycle-accurate MOS 6502 CPU emulation.
 *
 * Accuracy improvements over previous version:
 *  - BRK (0x00) fully implemented: pushes PC+1, status with B=1, sets I, vectors via $FFFE
 *  - BCD (decimal mode) DISABLED – NES CPU (2A03) ignores the D flag, so ADC/SBC always binary.
 *  - IRQ pending flag always latched; IRQ taken only when I=0.
 *  - Dummy reads on page crossings for indexed reads and on every indexed store.
 *  - Dummy writes for read-modify-write instructions (ASL, LSR, ROL, ROR, INC, DEC).
 *  - Open bus behavior: writes and reads update the open bus value.
 *  - NOP $0C (Absolute) performs a dummy read of the operand address.
 *  - All unofficial/illegal opcodes updated for dummy read/write behaviour.
 */
public class CPU6502 {

    public int A, X, Y, SP, PC;
    public boolean flagC, flagZ, flagI, flagD, flagB, flagV, flagN;

    private final Bus bus;
    private boolean nmiPending, irqPending;
    private int extraCycles;

    public CPU6502(Bus bus) {
        this.bus = bus;
        reset();
    }

    public void reset() {
        A = 0; X = 0; Y = 0; SP = 0xFD;
        flagC = false; flagZ = false; flagI = true;
        flagD = false; flagB = false; flagV = false; flagN = false;
        PC = (bus.cpuRead(0xFFFC) & 0xFF) | ((bus.cpuRead(0xFFFD) & 0xFF) << 8);
    }

    public void triggerNMI() { nmiPending = true; }
    public void triggerIRQ() { irqPending = true; }   // Always latch, don't check I here

    // Bit 5 (unused) is always 1; bit 4 (B) reflects the flagB field only when
    // explicitly pushed by BRK or PHP — hardware interrupts clear it in the pushed copy.
    public int getStatus() {
        return (flagC ? 0x01 : 0) | (flagZ ? 0x02 : 0) | (flagI ? 0x04 : 0) |
                (flagD ? 0x08 : 0) | (flagB ? 0x10 : 0) | 0x20 |
                (flagV ? 0x40 : 0) | (flagN ? 0x80 : 0);
    }

    // When pulling status (PLP, RTI): bit 5 is always set, bit 4 (B) is ignored
    // (B is not a physical register; it only appears in pushed copies).
    public void setStatus(int s) {
        flagC = (s & 0x01) != 0;
        flagZ = (s & 0x02) != 0;
        flagI = (s & 0x04) != 0;
        flagD = (s & 0x08) != 0;
        // Bit 4 (B) is NOT restored from the pulled byte per spec.
        flagV = (s & 0x40) != 0;
        flagN = (s & 0x80) != 0;
    }

    private void push(int v)  { bus.cpuWrite(0x100 + (SP-- & 0xFF), v & 0xFF); SP &= 0xFF; }
    private int  pop()        { SP = (SP + 1) & 0xFF; return bus.cpuRead(0x100 + SP) & 0xFF; }

    private void setZN(int v) { v &= 0xFF; flagZ = (v == 0); flagN = (v & 0x80) != 0; }

    public int step() {
        extraCycles = 0;

        // ── Interrupt handling ────────────────────────────────────────────
        if (nmiPending) {
            push(PC >> 8); push(PC & 0xFF);
            push(getStatus() & ~0x10); // B=0 in pushed copy
            flagI = true; flagB = false;
            PC = (bus.cpuRead(0xFFFA) & 0xFF) | ((bus.cpuRead(0xFFFB) & 0xFF) << 8);
            nmiPending = false;
            return 7;
        }
        if (irqPending && !flagI) {
            push(PC >> 8); push(PC & 0xFF);
            push(getStatus() & ~0x10); // B=0 in pushed copy
            flagI = true; flagB = false;
            PC = (bus.cpuRead(0xFFFE) & 0xFF) | ((bus.cpuRead(0xFFFF) & 0xFF) << 8);
            irqPending = false;
            return 7;
        }

        int opcode = bus.cpuRead(PC++) & 0xFF;
        int cycles = CYCLE_TABLE[opcode];
        int addr, val, tmp;

        switch (opcode) {

            // ── BRK ───────────────────────────────────────────────────────
            case 0x00:
                PC++; // skip padding byte
                push(PC >> 8); push(PC & 0xFF);
                push(getStatus() | 0x10); // B=1 in pushed copy for BRK/PHP
                flagI = true; flagB = false;
                PC = (bus.cpuRead(0xFFFE) & 0xFF) | ((bus.cpuRead(0xFFFF) & 0xFF) << 8);
                break;

            // ── ADC (binary only, decimal mode ignored) ───────────────────
            case 0x69: adc(bus.cpuRead(PC++) & 0xFF); break;
            case 0x65: adc(bus.cpuRead(bus.cpuRead(PC++) & 0xFF) & 0xFF); break;
            case 0x75: adc(bus.cpuRead((bus.cpuRead(PC++) + X) & 0xFF) & 0xFF); break;
            case 0x6D: addr = read16(PC); PC += 2; adc(bus.cpuRead(addr) & 0xFF); break;
            case 0x7D: addr = read16(PC); PC += 2; adc(bus.cpuRead(readAbsIndexed(addr, X))); break;
            case 0x79: addr = read16(PC); PC += 2; adc(bus.cpuRead(readAbsIndexed(addr, Y))); break;
            case 0x61: tmp = (bus.cpuRead(PC++) + X) & 0xFF; adc(bus.cpuRead(read16Bug(tmp)) & 0xFF); break;
            case 0x71: tmp = bus.cpuRead(PC++) & 0xFF; adc(bus.cpuRead(readIndY(tmp))); break;

            // ── AND ───────────────────────────────────────────────────────
            case 0x29: A = (A & (bus.cpuRead(PC++) & 0xFF)) & 0xFF; setZN(A); break;
            case 0x25: A = (A & (bus.cpuRead(bus.cpuRead(PC++) & 0xFF) & 0xFF)) & 0xFF; setZN(A); break;
            case 0x35: A = (A & (bus.cpuRead((bus.cpuRead(PC++) + X) & 0xFF) & 0xFF)) & 0xFF; setZN(A); break;
            case 0x2D: addr = read16(PC); PC += 2; A = (A & bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x3D: addr = read16(PC); PC += 2; A = (A & bus.cpuRead(readAbsIndexed(addr, X))) & 0xFF; setZN(A); break;
            case 0x39: addr = read16(PC); PC += 2; A = (A & bus.cpuRead(readAbsIndexed(addr, Y))) & 0xFF; setZN(A); break;
            case 0x21: tmp = (bus.cpuRead(PC++) + X) & 0xFF; A = (A & bus.cpuRead(read16Bug(tmp))) & 0xFF; setZN(A); break;
            case 0x31: tmp = bus.cpuRead(PC++) & 0xFF; A = (A & bus.cpuRead(readIndY(tmp))) & 0xFF; setZN(A); break;

            // ── ASL (with dummy write) ────────────────────────────────────
            case 0x0A: flagC = (A & 0x80) != 0; A = (A << 1) & 0xFF; setZN(A); break;
            case 0x06: aslWithDummy(bus.cpuRead(PC++) & 0xFF); break;
            case 0x16: aslWithDummy((bus.cpuRead(PC++) + X) & 0xFF); break;
            case 0x0E: addr = read16(PC); PC += 2; aslWithDummy(addr); break;
            case 0x1E: addr = read16(PC); PC += 2; aslWithDummy((addr + X) & 0xFFFF); break;

            // ── BIT ───────────────────────────────────────────────────────
            case 0x24: val = bus.cpuRead(bus.cpuRead(PC++) & 0xFF) & 0xFF; flagZ = (A & val) == 0; flagN = (val & 0x80) != 0; flagV = (val & 0x40) != 0; break;
            case 0x2C: addr = read16(PC); PC += 2; val = bus.cpuRead(addr) & 0xFF; flagZ = (A & val) == 0; flagN = (val & 0x80) != 0; flagV = (val & 0x40) != 0; break;

            // ── Branches ──────────────────────────────────────────────────
            case 0x10: branch(!flagN); break; case 0x30: branch(flagN);  break;
            case 0x50: branch(!flagV); break; case 0x70: branch(flagV);  break;
            case 0x90: branch(!flagC); break; case 0xB0: branch(flagC);  break;
            case 0xD0: branch(!flagZ); break; case 0xF0: branch(flagZ);  break;

            // ── CMP (with dummy reads on page cross) ──────────────────────
            case 0xC9: cmp(A, bus.cpuRead(PC++) & 0xFF); break;
            case 0xC5: cmp(A, bus.cpuRead(bus.cpuRead(PC++) & 0xFF) & 0xFF); break;
            case 0xD5: cmp(A, bus.cpuRead((bus.cpuRead(PC++) + X) & 0xFF) & 0xFF); break;
            case 0xCD: addr = read16(PC); PC += 2; cmp(A, bus.cpuRead(addr) & 0xFF); break;
            case 0xDD: addr = read16(PC); PC += 2; cmp(A, bus.cpuRead(readAbsIndexed(addr, X))); break;
            case 0xD9: addr = read16(PC); PC += 2; cmp(A, bus.cpuRead(readAbsIndexed(addr, Y))); break;
            case 0xC1: tmp = (bus.cpuRead(PC++) + X) & 0xFF; cmp(A, bus.cpuRead(read16Bug(tmp)) & 0xFF); break;
            case 0xD1: tmp = bus.cpuRead(PC++) & 0xFF; cmp(A, bus.cpuRead(readIndY(tmp))); break;

            // ── CPX ───────────────────────────────────────────────────────
            case 0xE0: cmp(X, bus.cpuRead(PC++) & 0xFF); break;
            case 0xE4: cmp(X, bus.cpuRead(bus.cpuRead(PC++) & 0xFF) & 0xFF); break;
            case 0xEC: addr = read16(PC); PC += 2; cmp(X, bus.cpuRead(addr) & 0xFF); break;

            // ── CPY ───────────────────────────────────────────────────────
            case 0xC0: cmp(Y, bus.cpuRead(PC++) & 0xFF); break;
            case 0xC4: cmp(Y, bus.cpuRead(bus.cpuRead(PC++) & 0xFF) & 0xFF); break;
            case 0xCC: addr = read16(PC); PC += 2; cmp(Y, bus.cpuRead(addr) & 0xFF); break;

            // ── DEC (with dummy write) ────────────────────────────────────
            case 0xC6: decWithDummy(bus.cpuRead(PC++) & 0xFF); break;
            case 0xD6: decWithDummy((bus.cpuRead(PC++) + X) & 0xFF); break;
            case 0xCE: addr = read16(PC); PC += 2; decWithDummy(addr); break;
            case 0xDE: addr = read16(PC); PC += 2; decWithDummy((addr + X) & 0xFFFF); break;
            case 0xCA: X = (X - 1) & 0xFF; setZN(X); break;
            case 0x88: Y = (Y - 1) & 0xFF; setZN(Y); break;

            // ── EOR (with dummy reads) ────────────────────────────────────
            case 0x49: A = (A ^ (bus.cpuRead(PC++) & 0xFF)) & 0xFF; setZN(A); break;
            case 0x45: A = (A ^ (bus.cpuRead(bus.cpuRead(PC++) & 0xFF) & 0xFF)) & 0xFF; setZN(A); break;
            case 0x55: A = (A ^ (bus.cpuRead((bus.cpuRead(PC++) + X) & 0xFF) & 0xFF)) & 0xFF; setZN(A); break;
            case 0x4D: addr = read16(PC); PC += 2; A = (A ^ bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x5D: addr = read16(PC); PC += 2; A = (A ^ bus.cpuRead(readAbsIndexed(addr, X))) & 0xFF; setZN(A); break;
            case 0x59: addr = read16(PC); PC += 2; A = (A ^ bus.cpuRead(readAbsIndexed(addr, Y))) & 0xFF; setZN(A); break;
            case 0x41: tmp = (bus.cpuRead(PC++) + X) & 0xFF; A = (A ^ bus.cpuRead(read16Bug(tmp))) & 0xFF; setZN(A); break;
            case 0x51: tmp = bus.cpuRead(PC++) & 0xFF; A = (A ^ bus.cpuRead(readIndY(tmp))) & 0xFF; setZN(A); break;

            // ── INC (with dummy write) ────────────────────────────────────
            case 0xE6: incWithDummy(bus.cpuRead(PC++) & 0xFF); break;
            case 0xF6: incWithDummy((bus.cpuRead(PC++) + X) & 0xFF); break;
            case 0xEE: addr = read16(PC); PC += 2; incWithDummy(addr); break;
            case 0xFE: addr = read16(PC); PC += 2; incWithDummy((addr + X) & 0xFFFF); break;
            case 0xE8: X = (X + 1) & 0xFF; setZN(X); break;
            case 0xC8: Y = (Y + 1) & 0xFF; setZN(Y); break;

            // ── JMP ───────────────────────────────────────────────────────
            case 0x4C: PC = read16(PC); break;
            case 0x6C: PC = read16Bug(read16(PC)); break;

            // ── JSR ───────────────────────────────────────────────────────
            case 0x20: {
                int lo = bus.cpuRead(PC++) & 0xFF;
                push(PC >> 8); push(PC & 0xFF);
                int hi = bus.cpuRead(PC) & 0xFF;
                PC = lo | (hi << 8);
                break;
            }

            // ── LDA (with dummy reads) ────────────────────────────────────
            case 0xA9: A = bus.cpuRead(PC++) & 0xFF; setZN(A); break;
            case 0xA5: A = bus.cpuRead(bus.cpuRead(PC++) & 0xFF) & 0xFF; setZN(A); break;
            case 0xB5: A = bus.cpuRead((bus.cpuRead(PC++) + X) & 0xFF) & 0xFF; setZN(A); break;
            case 0xAD: addr = read16(PC); PC += 2; A = bus.cpuRead(addr) & 0xFF; setZN(A); break;
            case 0xBD: addr = read16(PC); PC += 2; A = bus.cpuRead(readAbsIndexed(addr, X)); setZN(A); break;
            case 0xB9: addr = read16(PC); PC += 2; A = bus.cpuRead(readAbsIndexed(addr, Y)); setZN(A); break;
            case 0xA1: tmp = (bus.cpuRead(PC++) + X) & 0xFF; A = bus.cpuRead(read16Bug(tmp)) & 0xFF; setZN(A); break;
            case 0xB1: tmp = bus.cpuRead(PC++) & 0xFF; A = bus.cpuRead(readIndY(tmp)); setZN(A); break;

            // ── LDX (with dummy reads) ────────────────────────────────────
            case 0xA2: X = bus.cpuRead(PC++) & 0xFF; setZN(X); break;
            case 0xA6: X = bus.cpuRead(bus.cpuRead(PC++) & 0xFF) & 0xFF; setZN(X); break;
            case 0xB6: X = bus.cpuRead((bus.cpuRead(PC++) + Y) & 0xFF) & 0xFF; setZN(X); break;
            case 0xAE: addr = read16(PC); PC += 2; X = bus.cpuRead(addr) & 0xFF; setZN(X); break;
            case 0xBE: addr = read16(PC); PC += 2; X = bus.cpuRead(readAbsIndexed(addr, Y)); setZN(X); break;

            // ── LDY (with dummy reads) ────────────────────────────────────
            case 0xA0: Y = bus.cpuRead(PC++) & 0xFF; setZN(Y); break;
            case 0xA4: Y = bus.cpuRead(bus.cpuRead(PC++) & 0xFF) & 0xFF; setZN(Y); break;
            case 0xB4: Y = bus.cpuRead((bus.cpuRead(PC++) + X) & 0xFF) & 0xFF; setZN(Y); break;
            case 0xAC: addr = read16(PC); PC += 2; Y = bus.cpuRead(addr) & 0xFF; setZN(Y); break;
            case 0xBC: addr = read16(PC); PC += 2; Y = bus.cpuRead(readAbsIndexed(addr, X)); setZN(Y); break;

            // ── LSR (with dummy write) ────────────────────────────────────
            case 0x4A: flagC = (A & 0x01) != 0; A >>= 1; setZN(A); break;
            case 0x46: lsrWithDummy(bus.cpuRead(PC++) & 0xFF); break;
            case 0x56: lsrWithDummy((bus.cpuRead(PC++) + X) & 0xFF); break;
            case 0x4E: addr = read16(PC); PC += 2; lsrWithDummy(addr); break;
            case 0x5E: addr = read16(PC); PC += 2; lsrWithDummy((addr + X) & 0xFFFF); break;

            // ── NOP (official + unofficial) with correct dummy reads ───────
            case 0xEA:                                                         break; // NOP implied
            case 0x1A: case 0x3A: case 0x5A: case 0x7A: case 0xDA: case 0xFA: break; // NOP implied (unofficial)
            case 0x80: case 0x82: case 0x89: case 0xC2: case 0xE2:            bus.cpuRead(PC++); break; // NOP #imm dummy read
            case 0x04: case 0x44: case 0x64:                                   addr = bus.cpuRead(PC++) & 0xFF; bus.cpuRead(addr); break;
            case 0x14: case 0x34: case 0x54: case 0x74: case 0xD4: case 0xF4:  addr = (bus.cpuRead(PC++) + X) & 0xFF; bus.cpuRead(addr); break;
            case 0x0C:                                                         addr = read16(PC); PC += 2; bus.cpuRead(addr); break; // NOP abs dummy read
            case 0x1C: case 0x3C: case 0x5C: case 0x7C: case 0xDC: case 0xFC:  addr = read16(PC); PC += 2; readAbsIndexed(addr, X); break;

            // ── ORA (with dummy reads) ────────────────────────────────────
            case 0x09: A = (A | (bus.cpuRead(PC++) & 0xFF)) & 0xFF; setZN(A); break;
            case 0x05: A = (A | (bus.cpuRead(bus.cpuRead(PC++) & 0xFF) & 0xFF)) & 0xFF; setZN(A); break;
            case 0x15: A = (A | (bus.cpuRead((bus.cpuRead(PC++) + X) & 0xFF) & 0xFF)) & 0xFF; setZN(A); break;
            case 0x0D: addr = read16(PC); PC += 2; A = (A | bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x1D: addr = read16(PC); PC += 2; A = (A | bus.cpuRead(readAbsIndexed(addr, X))) & 0xFF; setZN(A); break;
            case 0x19: addr = read16(PC); PC += 2; A = (A | bus.cpuRead(readAbsIndexed(addr, Y))) & 0xFF; setZN(A); break;
            case 0x01: tmp = (bus.cpuRead(PC++) + X) & 0xFF; A = (A | bus.cpuRead(read16Bug(tmp))) & 0xFF; setZN(A); break;
            case 0x11: tmp = bus.cpuRead(PC++) & 0xFF; A = (A | bus.cpuRead(readIndY(tmp))) & 0xFF; setZN(A); break;

            // ── ROL (with dummy write) ────────────────────────────────────
            case 0x2A: { int c = flagC ? 1 : 0; flagC = (A & 0x80) != 0; A = ((A << 1) | c) & 0xFF; setZN(A); break; }
            case 0x26: rolWithDummy(bus.cpuRead(PC++) & 0xFF); break;
            case 0x36: rolWithDummy((bus.cpuRead(PC++) + X) & 0xFF); break;
            case 0x2E: addr = read16(PC); PC += 2; rolWithDummy(addr); break;
            case 0x3E: addr = read16(PC); PC += 2; rolWithDummy((addr + X) & 0xFFFF); break;

            // ── ROR (with dummy write) ────────────────────────────────────
            case 0x6A: { int c = flagC ? 0x80 : 0; flagC = (A & 0x01) != 0; A = (A >> 1) | c; setZN(A); break; }
            case 0x66: rorWithDummy(bus.cpuRead(PC++) & 0xFF); break;
            case 0x76: rorWithDummy((bus.cpuRead(PC++) + X) & 0xFF); break;
            case 0x6E: addr = read16(PC); PC += 2; rorWithDummy(addr); break;
            case 0x7E: addr = read16(PC); PC += 2; rorWithDummy((addr + X) & 0xFFFF); break;

            // ── RTI ───────────────────────────────────────────────────────
            case 0x40: { int s = pop(); setStatus(s); int lo = pop(); int hi = pop(); PC = lo | (hi << 8); break; }

            // ── RTS ───────────────────────────────────────────────────────
            case 0x60: { int lo = pop(); int hi = pop(); PC = ((lo | (hi << 8)) + 1) & 0xFFFF; break; }

            // ── SBC (binary only, decimal mode ignored) ───────────────────
            case 0xE9: case 0xEB: sbc(bus.cpuRead(PC++) & 0xFF); break;
            case 0xE5: sbc(bus.cpuRead(bus.cpuRead(PC++) & 0xFF) & 0xFF); break;
            case 0xF5: sbc(bus.cpuRead((bus.cpuRead(PC++) + X) & 0xFF) & 0xFF); break;
            case 0xED: addr = read16(PC); PC += 2; sbc(bus.cpuRead(addr) & 0xFF); break;
            case 0xFD: addr = read16(PC); PC += 2; sbc(bus.cpuRead(readAbsIndexed(addr, X))); break;
            case 0xF9: addr = read16(PC); PC += 2; sbc(bus.cpuRead(readAbsIndexed(addr, Y))); break;
            case 0xE1: tmp = (bus.cpuRead(PC++) + X) & 0xFF; sbc(bus.cpuRead(read16Bug(tmp)) & 0xFF); break;
            case 0xF1: tmp = bus.cpuRead(PC++) & 0xFF; sbc(bus.cpuRead(readIndY(tmp))); break;

            // ── STA (indexed store forms perform dummy reads) ─────────────
            case 0x85: bus.cpuWrite(bus.cpuRead(PC++) & 0xFF, A); break;
            case 0x95: writeZpIndexed(bus.cpuRead(PC++) & 0xFF, X, A); break;
            case 0x8D: addr = read16(PC); PC += 2; bus.cpuWrite(addr, A); break;
            case 0x9D: addr = read16(PC); PC += 2; writeAbsIndexed(addr, X, A); break;
            case 0x99: addr = read16(PC); PC += 2; writeAbsIndexed(addr, Y, A); break;
            case 0x81: tmp = (bus.cpuRead(PC++) + X) & 0xFF; bus.cpuWrite(read16Bug(tmp), A); break;
            case 0x91: tmp = bus.cpuRead(PC++) & 0xFF; writeIndY(tmp, A); break;

            // ── STX ───────────────────────────────────────────────────────
            case 0x86: bus.cpuWrite(bus.cpuRead(PC++) & 0xFF, X); break;
            case 0x96: writeZpIndexed(bus.cpuRead(PC++) & 0xFF, Y, X); break;
            case 0x8E: addr = read16(PC); PC += 2; bus.cpuWrite(addr, X); break;

            // ── STY ───────────────────────────────────────────────────────
            case 0x84: bus.cpuWrite(bus.cpuRead(PC++) & 0xFF, Y); break;
            case 0x94: writeZpIndexed(bus.cpuRead(PC++) & 0xFF, X, Y); break;
            case 0x8C: addr = read16(PC); PC += 2; bus.cpuWrite(addr, Y); break;

            // ── Flag ops ──────────────────────────────────────────────────
            case 0x18: flagC = false; break; case 0x38: flagC = true;  break;
            case 0xD8: flagD = false; break; case 0xF8: flagD = true;  break;
            case 0x58: flagI = false; break; case 0x78: flagI = true;  break;
            case 0xB8: flagV = false; break;

            // ── Stack ops ─────────────────────────────────────────────────
            case 0x48: push(A); break;
            case 0x68: A = pop(); setZN(A); break;
            case 0x08: push(getStatus() | 0x10); break;
            case 0x28: setStatus(pop()); break;

            // ── Register transfers ────────────────────────────────────────
            case 0xAA: X = A; setZN(X); break;
            case 0xA8: Y = A; setZN(Y); break;
            case 0xBA: X = SP; setZN(X); break;
            case 0x8A: A = X; setZN(A); break;
            case 0x9A: SP = X; break;
            case 0x98: A = Y; setZN(A); break;

            // ══════════════════════════════════════════════════════════════
            // UNOFFICIAL / ILLEGAL OPCODES (updated for dummy reads/writes)
            // ══════════════════════════════════════════════════════════════

            // ── LAX = LDA + LDX (with dummy reads) ────────────────────────
            case 0xA7: val = bus.cpuRead(bus.cpuRead(PC++) & 0xFF) & 0xFF; A = val; X = val; setZN(val); break;
            case 0xB7: val = bus.cpuRead((bus.cpuRead(PC++) + Y) & 0xFF) & 0xFF; A = val; X = val; setZN(val); break;
            case 0xAF: addr = read16(PC); PC += 2; val = bus.cpuRead(addr) & 0xFF; A = val; X = val; setZN(val); break;
            case 0xBF: addr = read16(PC); PC += 2; val = bus.cpuRead(readAbsIndexed(addr, Y)); A = val; X = val; setZN(val); break;
            case 0xA3: tmp = (bus.cpuRead(PC++) + X) & 0xFF; val = bus.cpuRead(read16Bug(tmp)) & 0xFF; A = val; X = val; setZN(val); break;
            case 0xB3: tmp = bus.cpuRead(PC++) & 0xFF; val = bus.cpuRead(readIndY(tmp)); A = val; X = val; setZN(val); break;

            // ── SAX = store A & X (no dummy write needed for stores) ──────
            case 0x87: bus.cpuWrite(bus.cpuRead(PC++) & 0xFF, A & X); break;
            case 0x97: bus.cpuWrite((bus.cpuRead(PC++) + Y) & 0xFF, A & X); break;
            case 0x8F: addr = read16(PC); PC += 2; bus.cpuWrite(addr, A & X); break;
            case 0x83: tmp = (bus.cpuRead(PC++) + X) & 0xFF; bus.cpuWrite(read16Bug(tmp), A & X); break;

            // ── DCP = DEC then CMP (DEC already has dummy write) ──────────
            case 0xC7: addr = bus.cpuRead(PC++) & 0xFF; decWithDummy(addr); cmp(A, bus.cpuRead(addr) & 0xFF); break;
            case 0xD7: addr = (bus.cpuRead(PC++) + X) & 0xFF; decWithDummy(addr); cmp(A, bus.cpuRead(addr) & 0xFF); break;
            case 0xCF: addr = read16(PC); PC += 2; decWithDummy(addr); cmp(A, bus.cpuRead(addr) & 0xFF); break;
            case 0xDF: addr = (read16(PC) + X) & 0xFFFF; PC += 2; decWithDummy(addr); cmp(A, bus.cpuRead(addr) & 0xFF); break;
            case 0xDB: addr = (read16(PC) + Y) & 0xFFFF; PC += 2; decWithDummy(addr); cmp(A, bus.cpuRead(addr) & 0xFF); break;
            case 0xC3: tmp = (bus.cpuRead(PC++) + X) & 0xFF; addr = read16Bug(tmp); decWithDummy(addr); cmp(A, bus.cpuRead(addr) & 0xFF); break;
            case 0xD3: tmp = bus.cpuRead(PC++) & 0xFF; addr = (read16Bug(tmp) + Y) & 0xFFFF; decWithDummy(addr); cmp(A, bus.cpuRead(addr) & 0xFF); break;

            // ── ISB (ISC) = INC then SBC (INC has dummy write) ────────────
            case 0xE7: addr = bus.cpuRead(PC++) & 0xFF; incWithDummy(addr); sbc(bus.cpuRead(addr) & 0xFF); break;
            case 0xF7: addr = (bus.cpuRead(PC++) + X) & 0xFF; incWithDummy(addr); sbc(bus.cpuRead(addr) & 0xFF); break;
            case 0xEF: addr = read16(PC); PC += 2; incWithDummy(addr); sbc(bus.cpuRead(addr) & 0xFF); break;
            case 0xFF: addr = (read16(PC) + X) & 0xFFFF; PC += 2; incWithDummy(addr); sbc(bus.cpuRead(addr) & 0xFF); break;
            case 0xFB: addr = (read16(PC) + Y) & 0xFFFF; PC += 2; incWithDummy(addr); sbc(bus.cpuRead(addr) & 0xFF); break;
            case 0xE3: tmp = (bus.cpuRead(PC++) + X) & 0xFF; addr = read16Bug(tmp); incWithDummy(addr); sbc(bus.cpuRead(addr) & 0xFF); break;
            case 0xF3: tmp = bus.cpuRead(PC++) & 0xFF; addr = (read16Bug(tmp) + Y) & 0xFFFF; incWithDummy(addr); sbc(bus.cpuRead(addr) & 0xFF); break;

            // ── SLO = ASL then ORA (ASL has dummy write) ──────────────────
            case 0x07: addr = bus.cpuRead(PC++) & 0xFF; aslWithDummy(addr); A = (A | bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x17: addr = (bus.cpuRead(PC++) + X) & 0xFF; aslWithDummy(addr); A = (A | bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x0F: addr = read16(PC); PC += 2; aslWithDummy(addr); A = (A | bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x1F: addr = (read16(PC) + X) & 0xFFFF; PC += 2; aslWithDummy(addr); A = (A | bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x1B: addr = (read16(PC) + Y) & 0xFFFF; PC += 2; aslWithDummy(addr); A = (A | bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x03: tmp = (bus.cpuRead(PC++) + X) & 0xFF; addr = read16Bug(tmp); aslWithDummy(addr); A = (A | bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x13: tmp = bus.cpuRead(PC++) & 0xFF; addr = (read16Bug(tmp) + Y) & 0xFFFF; aslWithDummy(addr); A = (A | bus.cpuRead(addr)) & 0xFF; setZN(A); break;

            // ── RLA = ROL then AND (ROL has dummy write) ──────────────────
            case 0x27: addr = bus.cpuRead(PC++) & 0xFF; rolWithDummy(addr); A = (A & bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x37: addr = (bus.cpuRead(PC++) + X) & 0xFF; rolWithDummy(addr); A = (A & bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x2F: addr = read16(PC); PC += 2; rolWithDummy(addr); A = (A & bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x3F: addr = (read16(PC) + X) & 0xFFFF; PC += 2; rolWithDummy(addr); A = (A & bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x3B: addr = (read16(PC) + Y) & 0xFFFF; PC += 2; rolWithDummy(addr); A = (A & bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x23: tmp = (bus.cpuRead(PC++) + X) & 0xFF; addr = read16Bug(tmp); rolWithDummy(addr); A = (A & bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x33: tmp = bus.cpuRead(PC++) & 0xFF; addr = (read16Bug(tmp) + Y) & 0xFFFF; rolWithDummy(addr); A = (A & bus.cpuRead(addr)) & 0xFF; setZN(A); break;

            // ── SRE = LSR then EOR (LSR has dummy write) ──────────────────
            case 0x47: addr = bus.cpuRead(PC++) & 0xFF; lsrWithDummy(addr); A = (A ^ bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x57: addr = (bus.cpuRead(PC++) + X) & 0xFF; lsrWithDummy(addr); A = (A ^ bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x4F: addr = read16(PC); PC += 2; lsrWithDummy(addr); A = (A ^ bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x5F: addr = (read16(PC) + X) & 0xFFFF; PC += 2; lsrWithDummy(addr); A = (A ^ bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x5B: addr = (read16(PC) + Y) & 0xFFFF; PC += 2; lsrWithDummy(addr); A = (A ^ bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x43: tmp = (bus.cpuRead(PC++) + X) & 0xFF; addr = read16Bug(tmp); lsrWithDummy(addr); A = (A ^ bus.cpuRead(addr)) & 0xFF; setZN(A); break;
            case 0x53: tmp = bus.cpuRead(PC++) & 0xFF; addr = (read16Bug(tmp) + Y) & 0xFFFF; lsrWithDummy(addr); A = (A ^ bus.cpuRead(addr)) & 0xFF; setZN(A); break;

            // ── RRA = ROR then ADC (ROR has dummy write) ──────────────────
            case 0x67: addr = bus.cpuRead(PC++) & 0xFF; rorWithDummy(addr); adc(bus.cpuRead(addr) & 0xFF); break;
            case 0x77: addr = (bus.cpuRead(PC++) + X) & 0xFF; rorWithDummy(addr); adc(bus.cpuRead(addr) & 0xFF); break;
            case 0x6F: addr = read16(PC); PC += 2; rorWithDummy(addr); adc(bus.cpuRead(addr) & 0xFF); break;
            case 0x7F: addr = (read16(PC) + X) & 0xFFFF; PC += 2; rorWithDummy(addr); adc(bus.cpuRead(addr) & 0xFF); break;
            case 0x7B: addr = (read16(PC) + Y) & 0xFFFF; PC += 2; rorWithDummy(addr); adc(bus.cpuRead(addr) & 0xFF); break;
            case 0x63: tmp = (bus.cpuRead(PC++) + X) & 0xFF; addr = read16Bug(tmp); rorWithDummy(addr); adc(bus.cpuRead(addr) & 0xFF); break;
            case 0x73: tmp = bus.cpuRead(PC++) & 0xFF; addr = (read16Bug(tmp) + Y) & 0xFFFF; rorWithDummy(addr); adc(bus.cpuRead(addr) & 0xFF); break;

            // ── ANC = AND #imm then copy N to C ──────────────────────────
            case 0x0B: case 0x2B:
                A = (A & (bus.cpuRead(PC++) & 0xFF)) & 0xFF; setZN(A); flagC = flagN; break;

            // ── ALR = AND #imm then LSR A ─────────────────────────────────
            case 0x4B:
                A = (A & (bus.cpuRead(PC++) & 0xFF)) & 0xFF;
                flagC = (A & 0x01) != 0; A >>= 1; setZN(A); break;

            // ── ARR = AND #imm then ROR A (special flags) ─────────────────
            case 0x6B: {
                A = (A & (bus.cpuRead(PC++) & 0xFF)) & 0xFF;
                int c = flagC ? 0x80 : 0;
                A = (A >> 1) | c;
                setZN(A);
                flagC = (A & 0x40) != 0;
                flagV = ((A & 0x40) ^ ((A & 0x20) << 1)) != 0;
                break;
            }

            // ── XAA = A = X & #imm ────────────────────────────────────────
            case 0x8B:
                A = (X & (bus.cpuRead(PC++) & 0xFF)) & 0xFF; setZN(A); break;

            // ── LAS/LAE = (mem & SP) → A, X, SP (with dummy read) ─────────
            case 0xBB:
                addr = read16(PC); PC += 2;
                val = bus.cpuRead(readAbsIndexed(addr, Y)) & SP;
                A = val; X = val; SP = val; setZN(val); break;

            // ── TAS/SHS = SP = A & X, store SP & (addr_hi+1) ──────────────
            case 0x9B:
                addr = read16(PC); PC += 2;
                SP = A & X;
                writeUnstableAbsIndexed(addr, Y, SP, X, false); break;

            // ── SHA/AHX = store A & X & (addr_hi+1) abs,Y / (ind),Y ───────
            case 0x9F:
                addr = read16(PC); PC += 2;
                writeUnstableAbsIndexed(addr, Y, A & X, X, false); break;
            case 0x93:
                tmp = bus.cpuRead(PC++) & 0xFF;
                writeUnstableIndY(tmp, A & X, X); break;

            // ── SHX = store X & (addr_hi+1) abs,Y ─────────────────────────
            case 0x9E:
                addr = read16(PC); PC += 2;
                writeUnstableAbsIndexed(addr, Y, X, X, true); break;

            // ── SHY = store Y & (addr_hi+1) abs,X ─────────────────────────
            case 0x9C:
                addr = read16(PC); PC += 2;
                writeUnstableAbsIndexed(addr, X, Y, Y, true); break;

            default: break; // remaining undocumented opcodes treated as NOP
        }
        return cycles + extraCycles;
    }

    // ── Memory helpers ────────────────────────────────────────────────────────

    private int read16(int a) {
        return (bus.cpuRead(a) & 0xFF) | ((bus.cpuRead(a + 1) & 0xFF) << 8);
    }

    /** 6502 page-wrap bug: high byte address wraps within the same page. */
    private int read16Bug(int a) {
        int lo = bus.cpuRead(a) & 0xFF;
        int hi = bus.cpuRead((a & 0xFF00) | ((a + 1) & 0xFF)) & 0xFF;
        return lo | (hi << 8);
    }

    private boolean pageCross(int a, int b) {
        return (a & 0xFF00) != (b & 0xFF00);
    }

    // ── Branch helper ─────────────────────────────────────────────────────────

    private void branch(boolean cond) {
        int rel = (byte) bus.cpuRead(PC++);
        if (cond) {
            extraCycles++;
            int newPC = (PC + rel) & 0xFFFF;
            if (pageCross(PC, newPC)) extraCycles++;
            PC = newPC;
        }
    }

    // ── Helpers for dummy reads on page crossings ────────────────────────────

    /** For absolute,X and absolute,Y addressing – perform dummy read if page crossed. */
    private int readAbsIndexed(int baseAddr, int index) {
        int effective = (baseAddr + index) & 0xFFFF;
        if (pageCross(baseAddr, effective)) {
            extraCycles++;
            int dummyAddr = (baseAddr & 0xFF00) | ((baseAddr + index) & 0xFF);
            bus.cpuRead(dummyAddr);
        }
        return effective;
    }

    /** For (indirect),Y addressing – perform dummy read if page crossed. */
    private int readIndY(int pointerAddr) {
        int addr = read16Bug(pointerAddr);
        int effective = (addr + Y) & 0xFFFF;
        if (pageCross(addr, effective)) {
            extraCycles++;
            int dummyAddr = (addr & 0xFF00) | ((addr + Y) & 0xFF);
            bus.cpuRead(dummyAddr);
        }
        return effective;
    }


    /** Zero-page indexed writes read the unindexed zero-page address before writing. */
    private void writeZpIndexed(int baseAddr, int index, int val) {
        baseAddr &= 0xFF;
        bus.cpuRead(baseAddr);
        bus.cpuWrite((baseAddr + index) & 0xFF, val);
    }

    /** Absolute indexed writes always perform the 6502 dummy read before writing. */
    private void writeAbsIndexed(int baseAddr, int index, int val) {
        int dummyAddr = (baseAddr & 0xFF00) | ((baseAddr + index) & 0xFF);
        bus.cpuRead(dummyAddr);
        bus.cpuWrite((baseAddr + index) & 0xFFFF, val);
    }

    /** (Indirect),Y writes always perform the pre-carry dummy read before writing. */
    private void writeIndY(int pointerAddr, int val) {
        int baseAddr = read16Bug(pointerAddr & 0xFF);
        int dummyAddr = (baseAddr & 0xFF00) | ((baseAddr + Y) & 0xFF);
        bus.cpuRead(dummyAddr);
        bus.cpuWrite((baseAddr + Y) & 0xFFFF, val);
    }


    /**
     * Unstable SHx/TAS stores use hardware behavior 2 observed by AccuracyCoin:
     * the target page is ANDed with X/Y on page crossing, and the stored value is
     * normally ANDed with H. For SHX/SHY, a DMC/RDY halt on the dummy read makes
     * the opcode behave like STX/STY. SHA/SHS keep their H mask in this emulator
     * because DMC fetch timing is coarse-grained per instruction.
     */
    private void writeUnstableAbsIndexed(int baseAddr, int index, int val, int highMask, boolean rdyCanCancelH) {
        int effective = (baseAddr + index) & 0xFFFF;
        int dummyAddr = (baseAddr & 0xFF00) | ((baseAddr + index) & 0xFF);
        bus.cpuRead(dummyAddr);
        boolean rdyHalted = rdyCanCancelH && bus.consumeDmcDmaReadSinceCpu();
        int target = (!rdyHalted && pageCross(baseAddr, effective))
                ? (effective & 0x00FF) | (((effective >> 8) & highMask & 0xFF) << 8)
                : effective;
        bus.cpuWrite(target, unstableStoreValue(baseAddr, val, rdyHalted));
    }

    private int unstableStoreValue(int baseAddr, int val, boolean rdyHalted) {
        if (rdyHalted) return val & 0xFF;
        return val & (((baseAddr >> 8) + 1) & 0xFF);
    }

    /** Same unstable target-page behavior as SHA absolute,Y, using an indirect base. */
    private void writeUnstableIndY(int pointerAddr, int val, int highMask) {
        int baseAddr = read16Bug(pointerAddr & 0xFF);
        int effective = (baseAddr + Y) & 0xFFFF;
        int dummyAddr = (baseAddr & 0xFF00) | ((baseAddr + Y) & 0xFF);
        bus.cpuRead(dummyAddr);
        boolean rdyHalted = false;
        int target = (!rdyHalted && pageCross(baseAddr, effective))
                ? (effective & 0x00FF) | (((effective >> 8) & highMask & 0xFF) << 8)
                : effective;
        bus.cpuWrite(target, unstableStoreValue(baseAddr, val, rdyHalted));
    }


    // ── RMW helpers with dummy write (write original value before modifying) ──

    private void aslWithDummy(int addr) {
        int v = bus.cpuRead(addr) & 0xFF;
        bus.cpuWrite(addr, v);               // dummy write
        flagC = (v & 0x80) != 0;
        v = (v << 1) & 0xFF;
        bus.cpuWrite(addr, v);
        setZN(v);
    }

    private void lsrWithDummy(int addr) {
        int v = bus.cpuRead(addr) & 0xFF;
        bus.cpuWrite(addr, v);
        flagC = (v & 0x01) != 0;
        v >>= 1;
        bus.cpuWrite(addr, v);
        setZN(v);
    }

    private void rolWithDummy(int addr) {
        int v = bus.cpuRead(addr) & 0xFF;
        bus.cpuWrite(addr, v);
        int c = flagC ? 1 : 0;
        flagC = (v & 0x80) != 0;
        v = ((v << 1) | c) & 0xFF;
        bus.cpuWrite(addr, v);
        setZN(v);
    }

    private void rorWithDummy(int addr) {
        int v = bus.cpuRead(addr) & 0xFF;
        bus.cpuWrite(addr, v);
        int c = flagC ? 0x80 : 0;
        flagC = (v & 0x01) != 0;
        v = (v >> 1) | c;
        bus.cpuWrite(addr, v);
        setZN(v);
    }

    private void incWithDummy(int addr) {
        int v = bus.cpuRead(addr) & 0xFF;
        bus.cpuWrite(addr, v);
        v = (v + 1) & 0xFF;
        bus.cpuWrite(addr, v);
        setZN(v);
    }

    private void decWithDummy(int addr) {
        int v = bus.cpuRead(addr) & 0xFF;
        bus.cpuWrite(addr, v);
        v = (v - 1) & 0xFF;
        bus.cpuWrite(addr, v);
        setZN(v);
    }

    // ── ALU helpers (binary only, decimal mode ignored) ───────────────────────

    private void adc(int val) {
        int r = A + val + (flagC ? 1 : 0);
        flagV = ((~(A ^ val) & (A ^ r)) & 0x80) != 0;
        flagC = r > 0xFF;
        A = r & 0xFF;
        setZN(A);
    }

    private void sbc(int val) {
        adc(val ^ 0xFF);
    }

    private void cmp(int reg, int val) {
        int r = (reg & 0xFF) - (val & 0xFF);
        flagC = (reg & 0xFF) >= (val & 0xFF);
        setZN(r);
    }

    // ── Cycle table ───────────────────────────────────────────────────────────
    private static final int[] CYCLE_TABLE = {
            //0  1  2  3  4  5  6  7  8  9  A  B  C  D  E  F
            7, 6, 2, 8, 3, 3, 5, 5, 3, 2, 2, 2, 4, 4, 6, 6,  // 0x00
            2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,  // 0x10
            6, 6, 2, 8, 3, 3, 5, 5, 4, 2, 2, 2, 4, 4, 6, 6,  // 0x20
            2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,  // 0x30
            6, 6, 2, 8, 3, 3, 5, 5, 3, 2, 2, 2, 3, 4, 6, 6,  // 0x40
            2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,  // 0x50
            6, 6, 2, 8, 3, 3, 5, 5, 4, 2, 2, 2, 5, 4, 6, 6,  // 0x60
            2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,  // 0x70
            2, 6, 2, 6, 3, 3, 3, 3, 2, 2, 2, 2, 4, 4, 4, 4,  // 0x80
            2, 6, 2, 6, 4, 4, 4, 4, 2, 5, 2, 5, 5, 5, 5, 5,  // 0x90
            2, 6, 2, 6, 3, 3, 3, 3, 2, 2, 2, 2, 4, 4, 4, 4,  // 0xA0
            2, 5, 2, 5, 4, 4, 4, 4, 2, 4, 2, 4, 4, 4, 4, 4,  // 0xB0
            2, 6, 2, 8, 3, 3, 5, 5, 2, 2, 2, 2, 4, 4, 6, 6,  // 0xC0
            2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,  // 0xD0
            2, 6, 2, 8, 3, 3, 5, 5, 2, 2, 2, 2, 4, 4, 6, 6,  // 0xE0
            2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,  // 0xF0
    };
}