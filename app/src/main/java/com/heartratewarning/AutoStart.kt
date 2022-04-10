package com.heartratewarning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class AutoStart : BroadcastReceiver() {
    override fun onReceive(context: Context, arg1: Intent) {
        val preferences = context.getSharedPreferences("HeartRateWarningService", 0)
        if (preferences.getBoolean("auto_start", false) && preferences.getBoolean("watch_gps", false)) {
            val intent = Intent(context, HeartRateWarningService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i("AutoStart", "started")
        }
    }
}
