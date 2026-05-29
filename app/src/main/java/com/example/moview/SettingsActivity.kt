package com.example.moview

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.serialization.json.Json

class SettingsActivity : AppCompatActivity()
{
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this /* LifecycleOwner */, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed()
            {
                saveSettings(applicationContext, "settings.json", MainActivity.settings)
                setResult(Activity.RESULT_OK) // Signal result
                // Finish the activity
                finish()
                // Apply fade-in and fade-out animation
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        })
        findViewById<TextView>(R.id.ipTextBox).text = MainActivity.settings.ip
    }


    private fun saveSettings(context: Context, fileName: String, settings: Settings)
    {
        MainActivity.settings.ip = findViewById<TextView>(R.id.ipTextBox).text.toString()

        try {
            val jsonString = Json.encodeToString(Settings.serializer(), settings)
            context.openFileOutput(fileName, Context.MODE_PRIVATE).use { output ->
                output.write(jsonString.toByteArray())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}