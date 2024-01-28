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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (checkSelfPermission(Manifest.permission.BODY_SENSORS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS),
                    1)
        } else {
            Log.d(TAG, "BODY_SENSORS ALREADY GRANTED")
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                1)
        } else {
            Log.d(TAG, "ACCESS_COARSE_LOCATION ALREADY GRANTED")
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    1)
        } else {
            Log.d(TAG, "ACCESS_FINE_LOCATION ALREADY GRANTED")
        }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH),
                1)
        } else {
            Log.d(TAG, "BLUETOOTH ALREADY GRANTED")
        }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                1)
        } else {
            Log.d(TAG, "BLUETOOTH_CONNECT ALREADY GRANTED")
        }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE),
                1)
        } else {
            Log.d(TAG, "BLUETOOTH_ADVERTISE ALREADY GRANTED")
        }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADMIN),
                1)
        } else {
            Log.d(TAG, "BLUETOOTH_ADMIN ALREADY GRANTED")
        }

        val preferences = applicationContext.getSharedPreferences("HeartRateWarningService", 0)
        findViewById<EditText>(R.id.lower_level).setText(preferences.getInt("lower_level", 160).toString())
        findViewById<EditText>(R.id.upper_level).setText(preferences.getInt("upper_level", 170).toString())
        findViewById<Switch>(R.id.broadcast_ble).isChecked = preferences.getBoolean("broadcast_ble", true)
        findViewById<Switch>(R.id.watch_gps).isChecked = preferences.getBoolean("watch_gps", false)
        findViewById<Switch>(R.id.auto_start).isChecked = preferences.getBoolean("auto_start", false)

        findViewById<Button>(R.id.start).setOnClickListener {
            val serviceIntent = Intent(this, HeartRateWarningService::class.java).apply {
                putExtra("lower_level",  findViewById<EditText>(R.id.lower_level).text.toString().toInt())
                putExtra("upper_level", findViewById<EditText>(R.id.upper_level).text.toString().toInt())
                putExtra("broadcast_ble", findViewById<Switch>(R.id.broadcast_ble).isChecked)
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
