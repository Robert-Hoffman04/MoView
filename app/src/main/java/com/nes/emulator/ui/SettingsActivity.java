package com.nes.emulator.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Placeholder – extend with PreferenceFragmentCompat for full settings
        TextView tv = new TextView(this);
        tv.setText("Settings – Coming Soon");
        tv.setTextSize(24f);
        tv.setPadding(64, 64, 64, 64);
        setContentView(tv);
    }
}
