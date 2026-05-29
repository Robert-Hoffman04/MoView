package com.nes.emulator.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * Convenience helper for launching {@link EmulatorActivity} from any external
 * Activity or Fragment. Import this single class — you do not need to know the
 * Intent extra key names or the component class name.
 *
 * <pre>
 * // From a web URL (downloaded fully before the emulator starts):
 * NESLauncher.fromUrl(this, "https://example.com/roms/game.nes", "My Game");
 *
 * // From a file picker result:
 * NESLauncher.fromUri(this, pickedUri, "Contra");
 *
 * // From an asset bundled in your APK:
 * NESLauncher.fromAsset(this, "roms/smb.nes", "Super Mario Bros");
 *
 * // From an absolute path on disk:
 * NESLauncher.fromPath(this, file.getAbsolutePath(), null);
 *
 * // With result callback (to know if the ROM failed):
 * NESLauncher.fromUrl(this, url, "Game", MY_REQUEST_CODE);
 *
 * // In onActivityResult:
 * if (resultCode == EmulatorActivity.RESULT_LOAD_FAILED) {
 *     String err = data.getStringExtra(EmulatorActivity.EXTRA_ERROR_MESSAGE);
 * }
 * </pre>
 */
public final class NESLauncher {

    private NESLauncher() {}

    // ── Simple fire-and-forget launchers ──────────────────────────────────

    /**
     * Launch from an HTTP or HTTPS URL pointing directly to a .nes ROM file.
     * The entire file is downloaded into memory before the emulator starts —
     * no streaming, no temp files. Requires {@code <uses-permission
     * android:name="android.permission.INTERNET"/>} in your manifest.
     *
     * @param context   calling Activity or Context
     * @param romUrl    full URL, e.g. {@code "https://example.com/roms/game.nes"}
     * @param gameTitle optional display name shown while downloading (null = filename)
     */
    public static void fromUrl(Context context, String romUrl, String gameTitle) {
        context.startActivity(buildUrlIntent(context, romUrl, gameTitle));
    }

    /**
     * Launch immediately from a content:// or file:// URI (e.g. from
     * {@link android.app.Activity#startActivityForResult} with
     * {@link Intent#ACTION_OPEN_DOCUMENT}).
     *
     * @param context   calling Activity or Context
     * @param romUri    URI to the .nes file
     * @param gameTitle optional display name (null = derived from filename)
     */
    public static void fromUri(Context context, Uri romUri, String gameTitle) {
        context.startActivity(buildUriIntent(context, romUri, gameTitle));
    }

    /**
     * Launch from a .nes file bundled in your app's {@code assets/} folder.
     *
     * @param context    calling Activity or Context
     * @param assetPath  path relative to assets/ (e.g. {@code "roms/game.nes"})
     * @param gameTitle  optional display name
     */
    public static void fromAsset(Context context, String assetPath, String gameTitle) {
        context.startActivity(buildAssetIntent(context, assetPath, gameTitle));
    }

    /**
     * Launch from an absolute filesystem path.
     *
     * @param context    calling Activity or Context
     * @param absPath    absolute path to the .nes file
     * @param gameTitle  optional display name
     */
    public static void fromPath(Context context, String absPath, String gameTitle) {
        context.startActivity(buildPathIntent(context, absPath, gameTitle));
    }

    // ── startActivityForResult variants ───────────────────────────────────

    /** startActivityForResult variant for URL source. */
    public static void fromUrl(Activity activity, String romUrl, String gameTitle, int requestCode) {
        activity.startActivityForResult(buildUrlIntent(activity, romUrl, gameTitle), requestCode);
    }

    /**
     * Same as {@link #fromUri} but uses
     * {@link Activity#startActivityForResult} so you receive
     * {@link EmulatorActivity#RESULT_OK} or
     * {@link EmulatorActivity#RESULT_LOAD_FAILED} in {@code onActivityResult}.
     */
    public static void fromUri(Activity activity, Uri romUri, String gameTitle, int requestCode) {
        activity.startActivityForResult(buildUriIntent(activity, romUri, gameTitle), requestCode);
    }

    /** startActivityForResult variant for asset source. */
    public static void fromAsset(Activity activity, String assetPath, String gameTitle, int requestCode) {
        activity.startActivityForResult(buildAssetIntent(activity, assetPath, gameTitle), requestCode);
    }

    /** startActivityForResult variant for path source. */
    public static void fromPath(Activity activity, String absPath, String gameTitle, int requestCode) {
        activity.startActivityForResult(buildPathIntent(activity, absPath, gameTitle), requestCode);
    }

    // ── Intent builders (use these if you need to add your own extras) ────

    public static Intent buildUrlIntent(Context context, String romUrl, String gameTitle) {
        return base(context)
                .putExtra(EmulatorActivity.EXTRA_ROM_URL, romUrl)
                .putExtra(EmulatorActivity.EXTRA_GAME_TITLE, gameTitle);
    }

    public static Intent buildUriIntent(Context context, Uri romUri, String gameTitle) {
        return base(context)
                .putExtra(EmulatorActivity.EXTRA_ROM_URI, romUri.toString())
                .putExtra(EmulatorActivity.EXTRA_GAME_TITLE, gameTitle);
    }

    public static Intent buildAssetIntent(Context context, String assetPath, String gameTitle) {
        return base(context)
                .putExtra(EmulatorActivity.EXTRA_ROM_ASSET, assetPath)
                .putExtra(EmulatorActivity.EXTRA_GAME_TITLE, gameTitle);
    }

    public static Intent buildPathIntent(Context context, String absPath, String gameTitle) {
        return base(context)
                .putExtra(EmulatorActivity.EXTRA_ROM_PATH, absPath)
                .putExtra(EmulatorActivity.EXTRA_GAME_TITLE, gameTitle);
    }

    // ── Builder for advanced configuration ───────────────────────────────

    /** Fluent builder when you want to set optional flags before launching. */
    public static Builder builder(Context context) { return new Builder(context); }

    public static final class Builder {
        private final Context context;
        private final Intent intent;

        private Builder(Context ctx) {
            this.context = ctx;
            this.intent  = base(ctx);
        }

        public Builder romUrl(String url)       { intent.putExtra(EmulatorActivity.EXTRA_ROM_URL,   url);            return this; }
        public Builder romUri(Uri uri)           { intent.putExtra(EmulatorActivity.EXTRA_ROM_URI,   uri.toString()); return this; }
        public Builder romAsset(String path)     { intent.putExtra(EmulatorActivity.EXTRA_ROM_ASSET, path);           return this; }
        public Builder romPath(String path)      { intent.putExtra(EmulatorActivity.EXTRA_ROM_PATH,  path);           return this; }
        public Builder title(String title)       { intent.putExtra(EmulatorActivity.EXTRA_GAME_TITLE, title);         return this; }
        public Builder showFps(boolean show)     { intent.putExtra(EmulatorActivity.EXTRA_SHOW_FPS,  show);           return this; }

        public void launch()                             { context.startActivity(intent); }
        public void launch(Activity a, int requestCode)  { a.startActivityForResult(intent, requestCode); }
        public Intent build()                            { return intent; }
    }

    // ── Private helpers ───────────────────────────────────────────────────
    private static Intent base(Context context) {
        return new Intent(context, EmulatorActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
}
