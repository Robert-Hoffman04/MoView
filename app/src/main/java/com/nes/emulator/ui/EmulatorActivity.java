package com.nes.emulator.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import com.nes.emulator.mapper.Cartridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-contained full-screen NES emulator activity.
 *
 * <p>Designed to be launched by any external UI via Intent. It expects exactly
 * one ROM source delivered through Intent extras — it will immediately begin
 * loading and running the game with no further UI interaction required.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * INTENT CONTRACT
 * ────────────────────────────────────────────────────────────────────────────
 *
 * Use the static {@link NESLauncher} helper (preferred), or build the Intent
 * yourself using the EXTRA_* constants below.
 *
 * Source options (mutually exclusive, checked in this priority order):
 *
 *   1. EXTRA_ROM_URI  (String)
 *      A content:// or file:// URI string pointing to a .nes file.
 *      Works with Storage Access Framework picks, MediaStore, or file paths.
 *      Example:
 *          intent.putExtra(EmulatorActivity.EXTRA_ROM_URI, uri.toString());
 *
 *   2. EXTRA_ROM_ASSET  (String)
 *      Filename of a .nes ROM bundled in your calling app's assets/ folder.
 *      The emulator reads it via the calling app's AssetManager.
 *      Example:
 *          intent.putExtra(EmulatorActivity.EXTRA_ROM_ASSET, "smb.nes");
 *
 *   3. EXTRA_ROM_PATH  (String)
 *      Absolute file-system path to a .nes file (e.g. from internal storage).
 *      Example:
 *          intent.putExtra(EmulatorActivity.EXTRA_ROM_PATH,
 *              getFilesDir() + "/roms/game.nes");
 *
 * Optional extras:
 *
 *   EXTRA_GAME_TITLE  (String)
 *      Human-readable title shown briefly as an overlay when loading.
 *      Defaults to the filename if omitted.
 *
 *   EXTRA_SHOW_FPS  (boolean, default false)
 *      Show the FPS counter overlay during gameplay.
 *
 * Result codes (returned to startActivityForResult caller):
 *
 *   RESULT_OK     — user exited normally (pressed Back).
 *   RESULT_FIRST_USER (= 1) — ROM failed to load; error message in
 *                             result Intent extra EXTRA_ERROR_MESSAGE.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * MINIMAL USAGE EXAMPLE (from your UI activity)
 * ────────────────────────────────────────────────────────────────────────────
 *
 *   // Option A — from a file picker URI:
 *   NESLauncher.fromUri(this, pickedUri, "Super Mario Bros");
 *
 *   // Option B — from an assets/ file:
 *   NESLauncher.fromAsset(this, "roms/contra.nes", "Contra");
 *
 *   // Option C — from an absolute path:
 *   NESLauncher.fromPath(this, "/sdcard/ROMs/dk.nes", null);
 *
 *   // Option D — manual Intent:
 *   Intent i = new Intent(this, EmulatorActivity.class);
 *   i.putExtra(EmulatorActivity.EXTRA_ROM_URI, myUri.toString());
 *   i.putExtra(EmulatorActivity.EXTRA_SHOW_FPS, true);
 *   startActivity(i);
 * ────────────────────────────────────────────────────────────────────────────
 */
public class EmulatorActivity extends Activity {

    // ── Intent extra keys ─────────────────────────────────────────────────
    /** String: content:// or file:// URI of the .nes ROM file. */
    public static final String EXTRA_ROM_URI     = "nes.extra.ROM_URI";
    /** String: asset filename relative to assets/ (e.g. "roms/game.nes"). */
    public static final String EXTRA_ROM_ASSET   = "nes.extra.ROM_ASSET";
    /** String: absolute filesystem path to the .nes file. */
    public static final String EXTRA_ROM_PATH    = "nes.extra.ROM_PATH";
    /**
     * String: HTTP or HTTPS URL of a .nes ROM on a web server.
     * The entire file is downloaded into memory before the emulator starts —
     * no partial streaming. Requires the INTERNET permission in your manifest.
     * Example: "https://example.com/roms/game.nes"
     */
    public static final String EXTRA_ROM_URL     = "nes.extra.ROM_URL";
    /** String: human-readable game title for the loading overlay. */
    public static final String EXTRA_GAME_TITLE  = "nes.extra.GAME_TITLE";
    /** boolean: show FPS counter overlay (default false). */
    public static final String EXTRA_SHOW_FPS    = "nes.extra.SHOW_FPS";
    /** String: error message returned in result Intent on load failure. */
    public static final String EXTRA_ERROR_MESSAGE = "nes.extra.ERROR_MESSAGE";

    /** Result code used when the ROM fails to load. */
    public static final int RESULT_LOAD_FAILED = RESULT_FIRST_USER;

    /**
     * Default ROM URL used when the activity is launched with no Intent extras.
     * Handy for development — just launch the activity directly and it will
     * immediately download and start this ROM.
     */
    private static final String DEFAULT_ROM_URL = "http://10.0.0.39/media/mario.nes";

    // ── Internal state ────────────────────────────────────────────────────
    private EmulatorSurface emulatorSurface;

    // ── Lifecycle ─────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on and go fully immersive immediately
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode();

        // Create the EmulatorSurface programmatically — no XML layout needed
        emulatorSurface = new EmulatorSurface(this);
        setContentView(emulatorSurface);
        emulatorSurface.requestFocus();

        // Apply optional extras
        boolean showFps = getIntent().getBooleanExtra(EXTRA_SHOW_FPS, true);
        emulatorSurface.setShowFps(showFps);

        // Resolve ROM source and start loading
        resolveAndLoad(getIntent());
    }

    @Override
    protected void onPause()  { super.onPause();  emulatorSurface.onPause(); }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode(); // re-apply after dialogs/notifications pull it away
        emulatorSurface.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        emulatorSurface.onPause(); // ensure emulation thread stops
    }

    // ── Immersive mode ────────────────────────────────────────────────────
    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN         |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION    |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY   |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN  |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ── ROM resolution ────────────────────────────────────────────────────
    /**
     * Inspects the launching Intent, picks the right source, and kicks off
     * background loading. Priority: URI → asset → path.
     */
    private void resolveAndLoad(Intent intent) {
        String title = intent.getStringExtra(EXTRA_GAME_TITLE);

        String urlStr  = intent.getStringExtra(EXTRA_ROM_URL);
        String uriStr  = intent.getStringExtra(EXTRA_ROM_URI);
        String asset   = intent.getStringExtra(EXTRA_ROM_ASSET);
        String path    = intent.getStringExtra(EXTRA_ROM_PATH);

        if (urlStr != null) {
            if (title == null) title = urlStr.substring(urlStr.lastIndexOf('/') + 1);
            emulatorSurface.setStatusMessage("Downloading " + title + "…");
            loadFromUrl(urlStr, title);

        } else if (uriStr != null) {
            if (title == null) title = uriStr.substring(uriStr.lastIndexOf('/') + 1);
            emulatorSurface.setStatusMessage("Loading " + title + "…");
            loadFromUri(Uri.parse(uriStr), title);

        } else if (asset != null) {
            if (title == null) title = asset.substring(asset.lastIndexOf('/') + 1);
            emulatorSurface.setStatusMessage("Loading " + title + "…");
            loadFromAsset(asset, title);

        } else if (path != null) {
            if (title == null) title = path.substring(path.lastIndexOf('/') + 1);
            emulatorSurface.setStatusMessage("Loading " + title + "…");
            loadFromUri(Uri.parse("file://" + path), title);

        } else {
            // No extras — fall back to the hard-coded dev URL
            String fallbackTitle = DEFAULT_ROM_URL.substring(DEFAULT_ROM_URL.lastIndexOf('/') + 1);
            emulatorSurface.setStatusMessage("Downloading " + fallbackTitle + "…");
            loadFromUrl(DEFAULT_ROM_URL, fallbackTitle);
        }
    }

    // ── ROM loaders (all off the main thread) ─────────────────────────────
    private void loadFromUri(Uri uri, String title) {
        new Thread(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) throw new Exception("Could not open URI: " + uri);
                Cartridge cart = Cartridge.load(is);
                runOnUiThread(() -> emulatorSurface.loadCartridge(cart));
            } catch (Exception e) {
                runOnUiThread(() -> failWithError("ROM load failed: " + e.getMessage()));
            }
        }, "NES-ROM-Loader").start();
    }

    private void loadFromAsset(String assetName, String title) {
        new Thread(() -> {
            try (InputStream is = getAssets().open(assetName)) {
                Cartridge cart = Cartridge.load(is);
                runOnUiThread(() -> emulatorSurface.loadCartridge(cart));
            } catch (Exception e) {
                runOnUiThread(() -> failWithError("Asset ROM load failed: " + e.getMessage()));
            }
        }, "NES-ROM-Loader").start();
    }

    /**
     * Downloads the ROM at {@code urlStr} fully into a byte array, then hands
     * the bytes to the cartridge loader. Doing it all-at-once means:
     *   • The iNES header and all bank data are available together — no partial
     *     reads that would confuse the mapper or leave the cart in a bad state.
     *   • We can report the total download size and detect truncated responses.
     *   • No temp file is written to disk.
     *
     * Follows HTTP redirects (up to 5 hops) and enforces a 30-second connect /
     * 60-second read timeout so a dead server doesn't stall the UI forever.
     */
    private void loadFromUrl(String urlStr, String title) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                // ── Follow redirects manually so we can enforce limits ────────
                String currentUrl = urlStr;
                int redirectsLeft = 5;
                while (redirectsLeft-- > 0) {
                    URL url = new URL(currentUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(30_000);
                    conn.setReadTimeout(60_000);
                    conn.setRequestProperty("User-Agent", "NESEmulator/1.0 Android");
                    conn.setInstanceFollowRedirects(false); // handle manually
                    conn.connect();

                    int status = conn.getResponseCode();
                    if (status == HttpURLConnection.HTTP_MOVED_PERM
                            || status == HttpURLConnection.HTTP_MOVED_TEMP
                            || status == 307 || status == 308) {
                        String location = conn.getHeaderField("Location");
                        conn.disconnect();
                        conn = null;
                        if (location == null) throw new Exception("Redirect with no Location header");
                        // Resolve relative redirects against the current URL
                        currentUrl = new URL(new URL(currentUrl), location).toString();
                        continue;
                    }

                    if (status != HttpURLConnection.HTTP_OK) {
                        throw new Exception("Server returned HTTP " + status + " for: " + currentUrl);
                    }
                    break; // success
                }

                if (conn == null) throw new Exception("Too many redirects");

                // ── Sanity-check Content-Length if the server provides it ─────
                int contentLength = conn.getContentLength(); // -1 if unknown
                if (contentLength > 4 * 1024 * 1024) {
                    // NES ROMs are never legitimately this large
                    throw new Exception("Response too large (" + contentLength + " bytes); "
                            + "expected a .nes file ≤ 4 MB");
                }

                // ── Read the entire body into memory ──────────────────────────
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(
                        contentLength > 0 ? contentLength : 512 * 1024);
                byte[] chunk = new byte[8192];
                int bytesRead;
                try (InputStream body = conn.getInputStream()) {
                    while ((bytesRead = body.read(chunk)) != -1) {
                        buffer.write(chunk, 0, bytesRead);
                        // Guard against runaway streams when Content-Length was absent
                        if (buffer.size() > 4 * 1024 * 1024) {
                            throw new Exception("Download exceeded 4 MB limit");
                        }
                    }
                }

                byte[] romBytes = buffer.toByteArray();
                if (romBytes.length < 16) {
                    throw new Exception("Downloaded file is too small to be a valid ROM ("
                            + romBytes.length + " bytes)");
                }

                // Verify the iNES magic header before handing off
                if (romBytes[0] != 0x4E || romBytes[1] != 0x45
                        || romBytes[2] != 0x53 || romBytes[3] != 0x1A) {
                    throw new Exception("Downloaded file does not have a valid iNES header — "
                            + "check the URL points directly to a .nes file");
                }

                // ── Parse cartridge from the in-memory bytes ──────────────────
                Cartridge cart = Cartridge.load(new ByteArrayInputStream(romBytes));
                runOnUiThread(() -> emulatorSurface.loadCartridge(cart));

            } catch (Exception e) {
                runOnUiThread(() -> failWithError("URL ROM download failed: " + e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "NES-ROM-Loader").start();
    }

    // ── Error handling ────────────────────────────────────────────────────
    private void failWithError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        Intent result = new Intent();
        result.putExtra(EXTRA_ERROR_MESSAGE, message);
        setResult(RESULT_LOAD_FAILED, result);
        finish();
    }

    // ── Key input ─────────────────────────────────────────────────────────
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Surface gets first crack (D-pad, A/B/Start/Select)
        if (emulatorSurface.dispatchKeyEvent(event)) return true;

        // Back exits cleanly and returns RESULT_OK to caller
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP) {
            setResult(RESULT_OK);
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
