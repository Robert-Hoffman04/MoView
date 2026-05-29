package com.nes.emulator.input;

import android.view.KeyEvent;

/**
 * NES Controller (joypad) with TV remote key mapping.
 *
 * Physical remote → NES button mapping:
 *
 *   D-pad is treated as rotated 90° clockwise:
 *     Remote RIGHT  → NES UP
 *     Remote LEFT   → NES DOWN
 *     Remote UP     → NES LEFT
 *     Remote DOWN   → NES RIGHT
 *
 *   DPAD_CENTER          → Start
 *   MENU                 → Select
 *   MEDIA_FAST_FORWARD   → A
 *   MEDIA_REWIND         → B
 *   BACK                 → (not mapped — handled by activity to exit)
 *
 * NES shift-register order: A, B, Select, Start, Up, Down, Left, Right
 */
public class Controller {

    // Button bit masks
    public static final int BTN_A      = 0x01;
    public static final int BTN_B      = 0x02;
    public static final int BTN_SELECT = 0x04;
    public static final int BTN_START  = 0x08;
    public static final int BTN_UP     = 0x10;
    public static final int BTN_DOWN   = 0x20;
    public static final int BTN_LEFT   = 0x40;
    public static final int BTN_RIGHT  = 0x80;

    private int state;    // current button state
    private int latch;    // latched state when strobe was last written
    private int shiftReg;
    private boolean strobe;

    public void setButton(int button, boolean pressed) {
        if (pressed) state |= button;
        else         state &= ~button;
    }

    public void write(int val) {
        boolean newStrobe = (val & 1) != 0;
        // Latch the button state on the falling edge (strobe 1→0),
        // matching NES hardware which polls while strobe is high and
        // freezes the shift register when strobe goes low.
        if (strobe && !newStrobe) {
            shiftReg = state;
        }
        strobe = newStrobe;
    }

    public int read() {
        if (strobe) {
            // While strobe is held high, always return the live A button state
            return (state & BTN_A) != 0 ? 1 : 0;
        }
        // Serial read: LSB first (A, B, Select, Start, Up, Down, Left, Right)
        int bit = shiftReg & 1;
        shiftReg = (shiftReg >> 1) | 0x80; // fill with 1s after 8 reads (open bus)
        return bit;
    }

    // ── Android KeyEvent → NES button ─────────────────────────────────────
    public boolean handleKeyDown(int keyCode) {
        int btn = mapKey(keyCode);
        if (btn == 0) return false;
        setButton(btn, true);
        return true;
    }

    public boolean handleKeyUp(int keyCode) {
        int btn = mapKey(keyCode);
        if (btn == 0) return false;
        setButton(btn, false);
        return true;
    }

    private int mapKey(int keyCode) {
        switch (keyCode) {
            // D-pad rotated 90° clockwise:
            // Physical right  → NES up
            // Physical left   → NES down
            // Physical up     → NES left
            // Physical down   → NES right
            case KeyEvent.KEYCODE_DPAD_RIGHT: return BTN_UP;
            case KeyEvent.KEYCODE_DPAD_LEFT:  return BTN_DOWN;
            case KeyEvent.KEYCODE_DPAD_UP:    return BTN_LEFT;
            case KeyEvent.KEYCODE_DPAD_DOWN:  return BTN_RIGHT;

            // A button — fast forward
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_BUTTON_A:   return BTN_A;

            // B button — rewind
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_BUTTON_B:   return BTN_B;

            // Start — D-pad center
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_START: return BTN_START;

            // Select — Menu button
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BUTTON_SELECT: return BTN_SELECT;

            // BACK is intentionally NOT mapped — the activity handles it to exit
            default: return 0;
        }
    }
}
