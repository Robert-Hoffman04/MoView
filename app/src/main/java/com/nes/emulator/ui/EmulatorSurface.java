package com.nes.emulator.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.nes.emulator.NES;
import com.nes.emulator.input.Controller;
import com.nes.emulator.mapper.Cartridge;

import java.nio.IntBuffer;

/**
 * SurfaceView that renders the NES output.
 * Uses zero-copy rendering by consuming the PPU's direct IntBuffer.
 */
public class EmulatorSurface extends SurfaceView implements SurfaceHolder.Callback {

    private NES nes;
    private final Controller ctrl1 = new Controller();
    private final Controller ctrl2 = new Controller();

    private EmulationThread emulationThread;
    private final Bitmap frameBitmap = Bitmap.createBitmap(256, 240, Bitmap.Config.ARGB_8888);
    private final Rect srcRect = new Rect(0, 0, 256, 240);
    private final Rect dstRect = new Rect();

    private final Paint paint = new Paint();
    private final Paint textPaint = new Paint();

    private volatile boolean running;
    private IntBuffer latestFrame; // Direct buffer from PPU

    private int fpsCounter, fps;
    private long fpsTime;

    private String statusMessage = "Loading…";
    private boolean romLoaded, showFps = false;

    public EmulatorSurface(Context ctx) { this(ctx, null); }
    public EmulatorSurface(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        paint.setFilterBitmap(false); // Sharp pixel art
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28f);
    }

    public void loadCartridge(Cartridge cartridge) {
        stopEmulation();
        nes = new NES(cartridge, ctrl1, ctrl2, pixels -> latestFrame = pixels);
        nes.reset();
        romLoaded = true;
        statusMessage = null;
        startEmulation();
    }

    private void startEmulation() {
        if (!romLoaded || emulationThread != null) return;
        running = true;
        emulationThread = new EmulationThread();
        emulationThread.start();
    }

    private void stopEmulation() {
        running = false;
        if (emulationThread != null) {
            emulationThread.interrupt();
            try { emulationThread.join(1000); } catch (InterruptedException ignored) {}
            emulationThread = null;
        }
        if (nes != null) { nes.destroy(); nes = null; }
        romLoaded = false;
    }

    private class EmulationThread extends Thread {
        @Override
        public void run() {
            final long targetNs = 1_000_000_000L / 60;
            while (running) {
                long start = System.nanoTime();
                if (nes != null) nes.runFrame();
                drawFrame();
                long elapsed = System.nanoTime() - start;
                long remainNs = targetNs - elapsed;
                if (remainNs > 0) {
                    // Use full nanosecond precision — the old integer-ms division
                    // (remainNs / 1_000_000) truncated and slept ~0.5–1 ms too long
                    // per frame, accumulating enough drift to halve the effective FPS.
                    try {
                        Thread.sleep(remainNs / 1_000_000L, (int)(remainNs % 1_000_000L));
                    } catch (InterruptedException e) { break; }
                }
                updateFps();
            }
        }
        private void updateFps() {
            fpsCounter++;
            long now = System.currentTimeMillis();
            if (now - fpsTime >= 1000) { fps = fpsCounter; fpsCounter = 0; fpsTime = now; }
        }
    }

    private void drawFrame() {
        SurfaceHolder holder = getHolder();
        if (!holder.getSurface().isValid()) return;

        // lockHardwareCanvas() (API 26+) uses the hardware-accelerated path and
        // honours the display's full refresh rate (typically 60 Hz).
        // lockCanvas() goes through a software path that many drivers internally
        // throttle to 30 Hz — the most common cause of a hard 30 FPS cap.
        Canvas canvas = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? holder.lockHardwareCanvas()
                : holder.lockCanvas();
        if (canvas == null) return;

        try {
            if (romLoaded && latestFrame != null) {
                // Zero-copy pixel transfer
                latestFrame.position(0);
                frameBitmap.copyPixelsFromBuffer(latestFrame);

                int cw = canvas.getWidth(), ch = canvas.getHeight();
                float scale = Math.min(cw / 256f, ch / 240f);
                int dw = (int)(256 * scale), dh = (int)(240 * scale);
                int dx = (cw - dw) / 2, dy = (ch - dh) / 2;
                dstRect.set(dx, dy, dx + dw, dy + dh);

                canvas.drawColor(Color.BLACK);
                canvas.drawBitmap(frameBitmap, srcRect, dstRect, paint);
                if (showFps) canvas.drawText("FPS: " + fps, 20, 40, textPaint);
            } else {
                drawSplash(canvas);
            }
        } finally {
            holder.unlockCanvasAndPost(canvas);
        }
    }

    private void drawSplash(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(statusMessage != null ? statusMessage : "NES Emulator",
                canvas.getWidth()/2, canvas.getHeight()/2, textPaint);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) return ctrl1.handleKeyDown(event.getKeyCode());
        if (event.getAction() == KeyEvent.ACTION_UP) return ctrl1.handleKeyUp(event.getKeyCode());
        return super.dispatchKeyEvent(event);
    }

    @Override public void surfaceCreated(SurfaceHolder h) { if (romLoaded) startEmulation(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { stopEmulation(); }
    public void onPause() { stopEmulation(); }
    public void onResume() { if (romLoaded) startEmulation(); }
    public void setStatusMessage(String msg) { this.statusMessage = msg; }
    public void setShowFps(boolean show) { this.showFps = show; }
}