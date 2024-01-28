package com.heartratewarning

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Switch

class ServiceConfigActivity : ComponentActivity() {
    private val TAG : String = "ServiceConfigActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        //window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (checkSelfPermission(Manifest.permission.BODY_SENSORS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS),
                    1)
        } else {
            Log.d(TAG, "ALREADY GRANTED")
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    1)
        } else {
            Log.d(TAG, "ALREADY GRANTED")
        }

        var preferences = applicationContext.getSharedPreferences("HeartRateWarningService", 0)
        findViewById<EditText>(R.id.lower_level).setText(preferences.getInt("lower_level", 160).toString())
        findViewById<EditText>(R.id.upper_level).setText(preferences.getInt("upper_level", 170).toString())
        findViewById<Switch>(R.id.watch_gps).isChecked = preferences.getBoolean("watch_gps", false)
        findViewById<Switch>(R.id.auto_start).isChecked = preferences.getBoolean("auto_start", false)

        findViewById<Button>(R.id.start).setOnClickListener {
            val serviceIntent = Intent(this, HeartRateWarningService::class.java).apply {
                putExtra("lower_level",  findViewById<EditText>(R.id.lower_level).text.toString().toInt())
                putExtra("upper_level", findViewById<EditText>(R.id.upper_level).text.toString().toInt())
                putExtra("watch_gps", findViewById<Switch>(R.id.watch_gps).isChecked)
                putExtra("auto_start", findViewById<Switch>(R.id.auto_start).isChecked)
            }
            startService(serviceIntent)
            Log.i(TAG, "Starting service")
            finish()
        }

        findViewById<Button>(R.id.stop).setOnClickListener {
            val serviceIntent = Intent(this, HeartRateWarningService::class.java).apply {
            }
            stopService(serviceIntent)
            Log.i(TAG, "Stopping service")
        }
    }
}
