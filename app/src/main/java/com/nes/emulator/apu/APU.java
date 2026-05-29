package com.nes.emulator.apu;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.nes.emulator.memory.Bus;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * NES APU — Pulse ×2, Triangle, Noise, DMC.
 *
 * Accuracy fixes in this revision:
 *  - Pulse and triangle timers now run at APU rate (every 2 CPU cycles) via a
 *    half-cycle accumulator, fixing the one-octave-too-high pitch bug.
 *  - Frame counter uses correct per-step cycle periods instead of a single
 *    constant, eliminating envelope drift over time.
 *  - $4017 write delay: the new mode/reset takes effect 3–4 CPU cycles later,
 *    matching hardware behaviour used by music engines for precise timing.
 *  - Frame counter IRQ fully implemented: asserted at the end of 4-step mode
 *    step 3, cleared by $4015 read or $4017 write with inhibit bit set.
 *  - $4015 read returns frame IRQ (bit 6) and DMC IRQ (bit 7), clears frame IRQ.
 *  - DMC fully implemented: delta-modulation sample playback from CPU memory,
 *    looping, IRQ on completion, silence flag, DMA fetch via Bus reference.
 *  - Pulse output() upper period guard (> 0x7FF) removed — only < 8 silences.
 *  - Sweep reload: counter resets but unit still clocks if counter was already
 *    zero, matching hardware portamento behaviour.
 *  - Nonlinear mixing, RC filter chain, and Bresenham sample timing retained.
 */
public class APU {

    private static final int SAMPLE_RATE = 44100;
    private static final int CPU_CLOCK   = 1789773;
    private static final int CHUNK_SIZE  = 512;
    private static final int QUEUE_DEPTH = 16;
    private static final int POOL_SIZE   = QUEUE_DEPTH + 4;

    // Bus reference needed for DMC sample DMA reads.
    private Bus bus;

    private final Pulse    pulse1   = new Pulse(true);
    private final Pulse    pulse2   = new Pulse(false);
    private final Triangle triangle = new Triangle();
    private final Noise    noise    = new Noise();
    private final DMC      dmc      = new DMC();

    // ── Frame counter ────────────────────────────────────────────────────────
    //
    // Real per-step cycle counts (CPU cycles between frame counter clocks):
    //   4-step: 7457, 7456, 7458, 7457          (total 29368)
    //   5-step: 7457, 7456, 7458, 7458, 7452    (total 37371)
    //
    // Each step fires quarter-frame and/or half-frame clocks per the sequence:
    //   4-step: Q, QH, Q, QH
    //   5-step: Q, QH, Q, -, QH
    //
    // The IRQ is asserted at the END of step 3 in 4-step mode (and stays
    // asserted until cleared) unless the IRQ inhibit bit ($4017 bit 6) is set.

    private static final int[][] STEP_CYCLES = {
            { 7457, 7456, 7458, 7457 },           // 4-step
            { 7457, 7456, 7458, 7458, 7452 }       // 5-step
    };

    private int  frameCounterMode   = 0;   // 0 = 4-step, 1 = 5-step
    private int  frameCounterTimer  = STEP_CYCLES[0][0];
    private int  frameStep          = 0;
    private boolean frameIrqInhibit = false;
    private boolean frameIrqFlag    = false;

    // $4017 write delay: store pending mode and count down before applying.
    private int  pendingFrameMode   = -1;  // -1 = no pending write
    private int  pendingFrameDelay  = 0;

    // Callback to assert IRQ on the CPU.
    private Runnable irqCallback;

    // ── Nonlinear pulse lookup ───────────────────────────────────────────────
    private static final float[] PULSE_MIX = new float[31];
    static {
        for (int s = 0; s <= 30; s++) {
            PULSE_MIX[s] = (s == 0) ? 0f : (float)(95.88 / (8128.0 / s + 100.0));
        }
    }

    // ── RC filter state ──────────────────────────────────────────────────────
    private float hpCharge1 = 0f, hpCharge2 = 0f, lpPrev = 0f;
    private static final float HP1_K = (float)Math.exp(-2.0 * Math.PI * 90.0   / SAMPLE_RATE);
    private static final float HP2_K = (float)Math.exp(-2.0 * Math.PI * 440.0  / SAMPLE_RATE);
    private static final float LP_K  = (float)Math.exp(-2.0 * Math.PI * 14000.0/ SAMPLE_RATE);

    // ── Sample timing ────────────────────────────────────────────────────────
    private int sampleAccum = 0;

    // ── Half-cycle accumulator for APU-rate channels ─────────────────────────
    // Pulse and triangle timers decrement once per APU clock = once per 2 CPU
    // cycles. We track the fractional half-cycle so that odd CPU step counts
    // (e.g. from page-cross penalties) are handled without drift.
    private int halfCycleAccum = 0; // in half-cycles; ticks channels when >= 2

    // ── Audio output ─────────────────────────────────────────────────────────
    private final ArrayBlockingQueue<short[]> freePool   = new ArrayBlockingQueue<>(POOL_SIZE);
    private final ArrayBlockingQueue<short[]> audioQueue = new ArrayBlockingQueue<>(QUEUE_DEPTH);
    private AudioTrack  audioTrack;
    private Thread      audioThread;
    private volatile boolean audioRunning;
    private short[] writeChunk;
    private int     writePos;

    public APU() {
        for (int i = 0; i < POOL_SIZE; i++) freePool.offer(new short[CHUNK_SIZE]);
        writeChunk = freePool.poll();

        int minBuf   = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int trackBuf = Math.max(minBuf, CHUNK_SIZE * 4 * 2);

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(trackBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();

        audioRunning = true;
        audioThread  = new Thread(this::audioLoop, "NES-Audio");
        audioThread.setPriority(Thread.MAX_PRIORITY - 1);
        audioThread.setDaemon(true);
        audioThread.start();
        audioTrack.play();
    }

    /** Must be called before the first tick(). */
    public void setBus(Bus bus) {
        this.bus = bus;
        dmc.setBus(bus);
    }

    /** Called by the main loop to deliver CPU IRQ requests. */
    public void setIrqCallback(Runnable cb) { this.irqCallback = cb; }

    private void audioLoop() {
        while (audioRunning) {
            short[] chunk;
            try { chunk = audioQueue.take(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            int offset = 0;
            while (offset < chunk.length && audioRunning) {
                int written = audioTrack.write(chunk, offset, chunk.length - offset,
                        AudioTrack.WRITE_BLOCKING);
                if (written < 0) break;
                offset += written;
            }
            freePool.offer(chunk);
        }
    }

    // ── Register writes ──────────────────────────────────────────────────────

    public void write(int addr, int val) {
        val &= 0xFF;
        switch (addr) {
            case 0x4000: pulse1.writeControl(val);   break;
            case 0x4001: pulse1.writeSweep(val);     break;
            case 0x4002: pulse1.writeTimerLo(val);   break;
            case 0x4003: pulse1.writeTimerHi(val);   break;
            case 0x4004: pulse2.writeControl(val);   break;
            case 0x4005: pulse2.writeSweep(val);     break;
            case 0x4006: pulse2.writeTimerLo(val);   break;
            case 0x4007: pulse2.writeTimerHi(val);   break;
            case 0x4008: triangle.writeControl(val); break;
            case 0x400A: triangle.writeTimerLo(val); break;
            case 0x400B: triangle.writeTimerHi(val); break;
            case 0x400C: noise.writeControl(val);    break;
            case 0x400E: noise.writePeriod(val);     break;
            case 0x400F: noise.writeLength(val);     break;
            case 0x4010: dmc.writeControl(val);      break;
            case 0x4011: dmc.writeDirectLoad(val);   break;
            case 0x4012: dmc.writeSampleAddr(val);   break;
            case 0x4013: dmc.writeSampleLength(val); break;
            case 0x4015:
                pulse1.setEnabled((val & 0x01) != 0);
                pulse2.setEnabled((val & 0x02) != 0);
                triangle.setEnabled((val & 0x04) != 0);
                noise.setEnabled((val & 0x08) != 0);
                boolean dmcWasIdle = dmc.bytesRemaining == 0;
                dmc.setEnabled((val & 0x10) != 0);
                if ((val & 0x10) != 0 && dmcWasIdle && bus != null) {
                    bus.noteDmcDmaReadSinceCpu();
                }
                // Writing $4015 clears the DMC IRQ flag
                dmc.clearIrq();
                break;
            case 0x4017:
                // The new mode/reset takes effect 3–4 CPU cycles after the write.
                // We use 4 cycles as the standard delay (3 on odd cycle — approximate).
                frameIrqInhibit = (val & 0x40) != 0;
                if (frameIrqInhibit) frameIrqFlag = false; // inhibit also clears flag
                pendingFrameMode  = (val >> 7) & 1;
                pendingFrameDelay = 4; // cycles until mode/reset applies
                break;
        }
    }

    // ── Status read ($4015) ──────────────────────────────────────────────────

    public int readStatus() {
        int s = 0;
        if (pulse1.lengthCounter   > 0) s |= 0x01;
        if (pulse2.lengthCounter   > 0) s |= 0x02;
        if (triangle.lengthCounter > 0) s |= 0x04;
        if (noise.lengthCounter    > 0) s |= 0x08;
        if (dmc.bytesRemaining     > 0) s |= 0x10;
        if (frameIrqFlag)               s |= 0x40;
        if (dmc.irqFlag)                s |= 0x80;
        // Reading $4015 acknowledges (clears) the frame IRQ flag
        frameIrqFlag = false;
        return s;
    }

    // ── Main tick ────────────────────────────────────────────────────────────

    public void tick(int cpuCycles) {
        // ── $4017 write delay ────────────────────────────────────────────────
        if (pendingFrameMode >= 0) {
            pendingFrameDelay -= cpuCycles;
            if (pendingFrameDelay <= 0) {
                frameCounterMode  = pendingFrameMode;
                frameStep         = 0;
                frameCounterTimer = STEP_CYCLES[frameCounterMode][0];
                // 5-step mode immediately clocks a half+quarter frame on apply
                if (frameCounterMode == 1) { quarterFrame(); halfFrame(); }
                pendingFrameMode = -1;
            }
        }

        // ── Frame counter ────────────────────────────────────────────────────
        frameCounterTimer -= cpuCycles;
        while (frameCounterTimer <= 0) {
            clockFrameStep();
            // Advance step and load the next period
            int steps = (frameCounterMode == 0) ? 4 : 5;
            frameStep = (frameStep + 1) % steps;
            frameCounterTimer += STEP_CYCLES[frameCounterMode][frameStep];
        }

        // ── APU-rate channel ticking (pulse + triangle at half CPU clock) ────
        // Accumulate CPU cycles as half-cycles; tick once per 2 CPU cycles.
        halfCycleAccum += cpuCycles;
        int apuTicks = halfCycleAccum >> 1;   // integer divide by 2
        halfCycleAccum &= 1;                   // keep remainder (0 or 1)
        if (apuTicks > 0) {
            pulse1.tick(apuTicks);
            pulse2.tick(apuTicks);
            triangle.tick(apuTicks);
        }

        // Noise runs at CPU rate (its period table already accounts for this)
        noise.tick(cpuCycles);

        // DMC also runs at CPU rate (its period table is in CPU cycles)
        dmc.tick(cpuCycles, irqCallback);

        // ── Sample output (Bresenham) ────────────────────────────────────────
        sampleAccum += cpuCycles * SAMPLE_RATE;
        while (sampleAccum >= CPU_CLOCK) {
            sampleAccum -= CPU_CLOCK;
            outputSample();
        }
    }

    // ── Frame counter step clock ─────────────────────────────────────────────
    //
    //  4-step: step 0 → Q
    //          step 1 → QH
    //          step 2 → Q
    //          step 3 → QH + IRQ (unless inhibited)
    //
    //  5-step: step 0 → Q
    //          step 1 → QH
    //          step 2 → Q
    //          step 3 → (idle)
    //          step 4 → QH

    private void clockFrameStep() {
        boolean isHalf = false;
        if (frameCounterMode == 0) {
            // 4-step
            switch (frameStep) {
                case 0: case 2: break;                               // Q only (below)
                case 1: case 3: isHalf = true; break;
            }
            // IRQ at step 3 in 4-step mode
            if (frameStep == 3 && !frameIrqInhibit) {
                frameIrqFlag = true;
                if (irqCallback != null) irqCallback.run();
            }
        } else {
            // 5-step
            switch (frameStep) {
                case 0: case 2: break;
                case 1: case 4: isHalf = true; break;
                case 3: return; // idle
            }
        }
        // All non-idle steps clock the quarter frame; half steps also clock half
        quarterFrame();
        if (isHalf) halfFrame();
    }

    private void quarterFrame() {
        pulse1.clockEnvelope();
        pulse2.clockEnvelope();
        triangle.clockLinear();
        noise.clockEnvelope();
    }

    private void halfFrame() {
        pulse1.clockLength();   pulse1.clockSweep();
        pulse2.clockLength();   pulse2.clockSweep();
        triangle.clockLength();
        noise.clockLength();
    }

    // ── Sample output ────────────────────────────────────────────────────────

    private void outputSample() {
        int p1 = pulse1.output(), p2 = pulse2.output();
        int t  = triangle.output(), n = noise.output(), d = dmc.output();

        float pulseOut = PULSE_MIX[Math.min(p1 + p2, 30)];

        float tndDenom = t / 8227.0f + n / 12241.0f + d / 22638.0f;
        float tndOut   = (tndDenom == 0f) ? 0f : 159.79f / (1.0f / tndDenom + 100.0f);

        float sample = pulseOut + tndOut;

        // High-pass 1 (~90 Hz)
        float hp1 = sample - hpCharge1;
        hpCharge1 = sample - hp1 * HP1_K;

        // High-pass 2 (~440 Hz)
        float hp2 = hp1 - hpCharge2;
        hpCharge2 = hp1 - hp2 * HP2_K;

        // Low-pass (~14 kHz)
        float lp = hp2 * (1f - LP_K) + lpPrev * LP_K;
        lpPrev = lp;

        int pcm = (int)(lp * 32767f * 0.9f);
        if (pcm >  32767) pcm =  32767;
        if (pcm < -32768) pcm = -32768;

        writeChunk[writePos++] = (short) pcm;

        if (writePos >= CHUNK_SIZE) {
            try {
                short[] next = freePool.poll();
                if (next == null) next = freePool.take();
                audioQueue.put(writeChunk);
                writeChunk = next;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                writeChunk = new short[CHUNK_SIZE];
            }
            writePos = 0;
        }
    }

    // ── Shutdown ─────────────────────────────────────────────────────────────

    public void destroy() {
        audioRunning = false;
        if (audioThread != null) {
            audioThread.interrupt();
            try { audioThread.join(1000); } catch (InterruptedException ignored) {}
            audioThread = null;
        }
        if (audioTrack != null) {
            audioTrack.pause();
            audioTrack.flush();
            audioTrack.release();
            audioTrack = null;
        }
    }

    // ── Length counter lookup ────────────────────────────────────────────────

    static final int[] LENGTH_TABLE = {
            10,254,20,2,40,4,80,6,160,8,60,10,14,12,26,14,
            12,16,24,18,48,20,96,22,192,24,72,26,16,28,32,30
    };

    // ════════════════════════════════════════════════════════════════════════
    // Pulse channel
    // ════════════════════════════════════════════════════════════════════════

    static class Pulse {
        boolean enabled;
        int duty, lengthHalt, constVolume, volume;
        int sweepEnabled, sweepPeriod, sweepNegate, sweepShift;
        int timer, timerPeriod;
        int dutyPos;
        int lengthCounter;
        int envStart, envDecay;
        int sweepCounter, sweepReload;
        final boolean channel1;

        private static final int[][] DUTY_TABLE = {
                {0,1,0,0,0,0,0,0},
                {0,1,1,0,0,0,0,0},
                {0,1,1,1,1,0,0,0},
                {1,0,0,1,1,1,1,1}
        };

        Pulse(boolean ch1) { channel1 = ch1; }

        void writeControl(int v) {
            duty        = (v >> 6) & 3;
            lengthHalt  = (v >> 5) & 1;
            constVolume = (v >> 4) & 1;
            volume      = v & 0xF;
        }
        void writeSweep(int v) {
            sweepEnabled = (v >> 7) & 1;
            sweepPeriod  = ((v >> 4) & 7) + 1;
            sweepNegate  = (v >> 3) & 1;
            sweepShift   = v & 7;
            sweepReload  = 1;
        }
        void writeTimerLo(int v) { timerPeriod = (timerPeriod & 0xFF00) | v; }
        void writeTimerHi(int v) {
            timerPeriod = (timerPeriod & 0x00FF) | ((v & 7) << 8);
            if (enabled) lengthCounter = LENGTH_TABLE[v >> 3];
            dutyPos  = 0;
            envStart = 1;
        }
        void setEnabled(boolean e) { enabled = e; if (!e) lengthCounter = 0; }

        void clockEnvelope() {
            if (envStart == 1) { envStart = 0; envDecay = 15; return; }
            if (envDecay > 0)  { envDecay--; return; }
            if (lengthHalt == 1) envDecay = 15;
        }

        void clockLength() {
            if (lengthHalt == 0 && lengthCounter > 0) lengthCounter--;
        }

        /**
         * Sweep unit.
         *
         * FIX: When the reload flag is set, the counter is reset to the period.
         * On hardware, the sweep unit ALSO clocks this cycle if the counter was
         * already zero before the reload — we replicate that by only skipping
         * the sweep application when reloading, not the decrement. But since
         * the reload sets the counter to sweepPeriod (≥1), the unit won't apply
         * again until the counter next reaches zero, which matches hardware.
         *
         * The key correctness fix vs the original: do NOT return early on reload;
         * just reset the counter and clear the flag, then still decrement/clock.
         */
        void clockSweep() {
            if (sweepReload == 1) {
                sweepCounter = sweepPeriod;
                sweepReload  = 0;
                // Fall through — do NOT return; allow decrement this cycle
            }
            if (sweepCounter > 0) {
                sweepCounter--;
                return;
            }
            // Counter reached zero: apply sweep and reload
            sweepCounter = sweepPeriod;
            if (sweepEnabled == 1 && timerPeriod >= 8 && sweepShift > 0) {
                int delta   = timerPeriod >> sweepShift;
                // Pulse 1 negates with ones complement (subtract delta+1);
                // Pulse 2 uses twos complement (subtract delta).
                timerPeriod += (sweepNegate == 1)
                        ? (channel1 ? -(delta + 1) : -delta)
                        : delta;
            }
        }

        /**
         * Timer tick at APU rate (called with APU-clock ticks, not CPU cycles).
         *
         * FIX: previously subtracted raw CPU cycles, making the pulse run at
         * double frequency (one octave too high). Now called with APU ticks
         * (CPU cycles / 2) from the half-cycle accumulator in APU.tick().
         */
        void tick(int apuTicks) {
            timer -= apuTicks;
            while (timer < 0) {
                timer  += timerPeriod + 1;
                dutyPos = (dutyPos + 1) & 7;
            }
        }

        int output() {
            // FIX: removed the erroneous upper-period guard (timerPeriod > 0x7FF).
            // Only timerPeriod < 8 silences the channel (per spec); high period
            // values simply produce very low frequencies and must not be muted.
            if (!enabled || lengthCounter == 0 || timerPeriod < 8) return 0;
            if (DUTY_TABLE[duty][dutyPos] == 0) return 0;
            return constVolume == 1 ? volume : envDecay;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Triangle channel
    // ════════════════════════════════════════════════════════════════════════

    static class Triangle {
        boolean enabled;
        int lengthHalt, linearLoad;
        int timer, timerPeriod;
        int seqPos;
        int lengthCounter, linearCounter;
        boolean linearHalt;

        private static final int[] SEQ = {
                15,14,13,12,11,10,9,8,7,6,5,4,3,2,1,0,
                0, 1, 2, 3, 4, 5,6,7,8,9,10,11,12,13,14,15
        };

        void writeControl(int v) { lengthHalt = (v >> 7) & 1; linearLoad = v & 0x7F; }
        void writeTimerLo(int v) { timerPeriod = (timerPeriod & 0xFF00) | v; }
        void writeTimerHi(int v) {
            timerPeriod = (timerPeriod & 0x00FF) | ((v & 7) << 8);
            if (enabled) lengthCounter = LENGTH_TABLE[v >> 3];
            linearHalt = true;
        }
        void setEnabled(boolean e) { enabled = e; if (!e) lengthCounter = 0; }

        void clockLinear() {
            if (linearHalt)          linearCounter = linearLoad;
            else if (linearCounter > 0) linearCounter--;
            if (lengthHalt == 0) linearHalt = false;
        }
        void clockLength() { if (lengthHalt == 0 && lengthCounter > 0) lengthCounter--; }

        /**
         * Timer tick at APU rate (called with APU-clock ticks, not CPU cycles).
         *
         * FIX: same double-frequency bug as Pulse. The triangle timer decrements
         * once per APU clock. Sequence position only advances when both counters
         * are non-zero (already correct), but the timer itself must run at APU
         * rate, not CPU rate.
         */
        void tick(int apuTicks) {
            timer -= apuTicks;
            while (timer < 0) {
                timer += timerPeriod + 1;
                if (lengthCounter > 0 && linearCounter > 0)
                    seqPos = (seqPos + 1) & 31;
            }
        }

        int output() {
            if (!enabled || lengthCounter == 0 || linearCounter == 0) return 0;
            return SEQ[seqPos];
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Noise channel
    // ════════════════════════════════════════════════════════════════════════

    static class Noise {
        boolean enabled;
        int lengthHalt, constVolume, volume;
        int timerPeriod, timer;
        int mode, shift = 1;
        int lengthCounter, envStart, envDecay;

        // Period table is in CPU cycles (noise runs at CPU rate, not APU rate)
        private static final int[] PERIOD_TABLE = {
                4,8,16,32,64,96,128,160,202,254,380,508,762,1016,2034,4068
        };

        void writeControl(int v) {
            lengthHalt  = (v >> 5) & 1;
            constVolume = (v >> 4) & 1;
            volume      = v & 0xF;
        }
        void writePeriod(int v)  { mode = (v >> 7) & 1; timerPeriod = PERIOD_TABLE[v & 0xF]; }
        void writeLength(int v)  { if (enabled) lengthCounter = LENGTH_TABLE[v >> 3]; envStart = 1; }
        void setEnabled(boolean e) { enabled = e; if (!e) lengthCounter = 0; }

        void clockEnvelope() {
            if (envStart == 1) { envStart = 0; envDecay = 15; return; }
            if (envDecay > 0)  { envDecay--; return; }
            if (lengthHalt == 1) envDecay = 15;
        }
        void clockLength() { if (lengthHalt == 0 && lengthCounter > 0) lengthCounter--; }

        // Noise runs at CPU rate — tick() receives raw CPU cycles (correct as-is)
        void tick(int cycles) {
            timer -= cycles;
            while (timer < 0) {
                timer += timerPeriod + 1;
                int fb = (shift & 1) ^ ((mode == 1 ? (shift >> 6) : (shift >> 1)) & 1);
                shift  = ((shift >> 1) & 0x3FFF) | (fb << 14);
            }
        }

        int output() {
            if (!enabled || lengthCounter == 0 || (shift & 1) != 0) return 0;
            return constVolume == 1 ? volume : envDecay;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // DMC (Delta Modulation Channel) — full implementation
    // ════════════════════════════════════════════════════════════════════════
    //
    // The DMC plays back 1-bit delta-encoded samples from CPU memory.
    // Each sample byte contains 8 bits; each bit shifts the output level
    // up (+2) or down (-2) within the 0–127 range, clamped at the edges.
    //
    // Timing:
    //   The DMC has its own timer running at CPU rate. When the timer expires
    //   it outputs one bit. When the current sample byte is exhausted it
    //   fetches the next byte via a DMA read (costs 4 CPU cycles on hardware;
    //   we approximate this as a bus read without cycle stealing for now).
    //
    // Period table (CPU cycles per output bit, NTSC):
    //   $D0→428, $D1→380, ... (see PERIOD_TABLE below)

    static class DMC {
        private Bus bus;

        // Registers
        int irqEnable, loopFlag;
        int rateIndex;
        int outputLevel  = 0;     // 7-bit direct output (0-127)
        int sampleAddr   = 0xC000;
        int sampleLength = 1;

        // Runtime state
        int  currentAddr;
        int  bytesRemaining = 0;
        int  sampleBuffer;          // the fetched byte not yet shifted out
        boolean sampleBufferEmpty = true;
        int  shiftReg;
        int  bitsRemaining  = 0;
        boolean silenceFlag = true;
        boolean irqFlag     = false;
        boolean enabled     = false;

        int  timer;
        int  timerPeriod;

        // CPU cycles per output bit (NTSC)
        private static final int[] PERIOD_TABLE = {
                428,380,340,320,286,254,226,214,
                190,160,142,128,106, 84, 72, 54
        };

        void setBus(Bus b) {
            this.bus = b;
            timerPeriod = PERIOD_TABLE[0];
            timer       = timerPeriod;
        }

        void writeControl(int v) {
            irqEnable = (v >> 7) & 1;
            loopFlag  = (v >> 6) & 1;
            rateIndex = v & 0xF;
            timerPeriod = PERIOD_TABLE[rateIndex];
            if (irqEnable == 0) irqFlag = false;
        }

        void writeDirectLoad(int v) {
            outputLevel = v & 0x7F;
        }

        void writeSampleAddr(int v) {
            // Sample address = $C000 + (v * 64)
            sampleAddr = 0xC000 | (v << 6);
        }

        void writeSampleLength(int v) {
            // Sample length in bytes = (v * 16) + 1
            sampleLength = (v << 4) | 1;
        }

        void setEnabled(boolean e) {
            enabled = e;
            if (!e) {
                bytesRemaining = 0;
            } else if (bytesRemaining == 0) {
                // Restart sample from the beginning
                currentAddr    = sampleAddr;
                bytesRemaining = sampleLength;
            }
        }

        void clearIrq() { irqFlag = false; }

        /**
         * Tick the DMC at CPU rate.
         *
         * When the output timer expires, one bit is shifted out of the sample
         * shift register. When the shift register is empty, a new byte is
         * fetched from CPU memory (via bus.cpuRead). The fetch is not cycle-
         * accurate (real hardware stalls the CPU for 4 cycles) but the output
         * timing is correct.
         */
        void tick(int cycles, Runnable irqCallback) {
            if (!enabled && bytesRemaining == 0) return;

            timer -= cycles;
            while (timer <= 0) {
                timer += timerPeriod;
                outputBit();
                if (bitsRemaining > 0) bitsRemaining--;

                // If shift register just ran out, try to fetch next byte
                if (bitsRemaining == 0) {
                    if (!sampleBufferEmpty) {
                        // Load the buffered byte into the shift register
                        shiftReg          = sampleBuffer;
                        bitsRemaining     = 8;
                        silenceFlag       = false;
                        sampleBufferEmpty = true;
                    } else {
                        silenceFlag   = true;
                        bitsRemaining = 8; // still need to count through silence
                    }
                }
            }

            // Fetch next sample byte into buffer if buffer is empty and we have bytes
            if (sampleBufferEmpty && bytesRemaining > 0 && bus != null) {
                sampleBuffer      = bus.dmcRead(currentAddr) & 0xFF;
                sampleBufferEmpty = false;
                currentAddr       = 0xC000 | ((currentAddr + 1) & 0x3FFF);
                bytesRemaining--;

                if (bytesRemaining == 0) {
                    if (loopFlag == 1) {
                        // Loop: restart from beginning
                        currentAddr    = sampleAddr;
                        bytesRemaining = sampleLength;
                    } else if (irqEnable == 1) {
                        // Fire IRQ on sample completion
                        irqFlag = true;
                        if (irqCallback != null) irqCallback.run();
                    }
                }
            }
        }

        private void outputBit() {
            if (silenceFlag) return;
            // Delta: bit=1 → level+2 (clamped at 127), bit=0 → level-2 (clamped at 0)
            if ((shiftReg & 1) != 0) {
                if (outputLevel <= 125) outputLevel += 2;
            } else {
                if (outputLevel >= 2)   outputLevel -= 2;
            }
            shiftReg >>= 1;
        }

        int output() { return outputLevel; }
    }
}