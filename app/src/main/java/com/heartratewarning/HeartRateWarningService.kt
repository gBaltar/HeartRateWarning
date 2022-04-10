package com.heartratewarning

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import java.time.Instant
import java.util.*


class HeartRateWarningService : Service(), SensorEventListener {
    private val TAG : String = "HeartRateWarningService"
    private val notificationChannelId = "HeartRateWarning"
    //private val INTENT_ACTION : String = "com.google.android.clockwork.settings.action.GPS_ACTIVITY"
    //android.location.HIGH_POWER_REQUEST_CHANGE
    //TrainingService.action.TRAINING_STARTED

    private var lower_level : Int = 160
    private var upper_level : Int = 170
    private var watch_gps : Boolean = false
    private var auto_start : Boolean = false

    private var timer_state : Int = 0
    private var last_hr : Int = 0
    private var last_accuracy : Int = 0
    private var last_hr_time : Long = 0
    private var last_acc_good_time : Long = 0

    private var mPreferences : SharedPreferences? = null
    //private var mBroadCastReceiver : BroadcastReceiver? = null
    private var mTimer : Timer? = null
    private var mTimerTask : TimerTask? = null
    private var mSensorManager : SensorManager? = null
    private var mHeartRateSensor : Sensor? = null
    private var mLocationManager : LocationManager? = null
    private var mVibratorService : Vibrator? = null

    private val mGnssStatusCallback : GnssStatus.Callback = object : GnssStatus.Callback() {
        override fun onStarted() {
            Log.i(TAG, "GNSS Started")
            startHRWatch()
        }
        override fun onStopped() {
            Log.i(TAG, "GNSS Stopped")
            stopTimer()
            stopHRWatch()
        }
        override fun onFirstFix(ttffMillis: Int) {}
        override fun onSatelliteStatusChanged(status: GnssStatus) {}
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(notificationChannelId, notificationChannelId, NotificationManager.IMPORTANCE_MIN)
        channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val notificationIntent = Intent(this, ServiceConfigActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT
        )

        var title = "Inactive"
        if (mSensorManager != null) {
            if (timer_state == 0) title = "Low HR"
            else if (timer_state == 1) title = "Heightened HR!"
            else title = "Too high HR!"
        }
        val hr_str = if (mSensorManager != null) "HR: $last_hr Acc: $last_accuracy\n" else ""
        val notification: Notification = Notification.Builder(this,notificationChannelId)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSmallIcon(R.mipmap.heart_beat)
            .setContentTitle(title)
            .setContentText("${hr_str}Levels $lower_level/$upper_level")
            .setContentIntent(pendingIntent).build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        Log.i(TAG, "HeartRateWarningService stopped")
        stopHRWatch()
        stopTimer()
        mLocationManager!!.unregisterGnssStatusCallback(mGnssStatusCallback);
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HeartRateWarningService created")
        mPreferences = applicationContext.getSharedPreferences("HeartRateWarningService", 0)
        readPreferences()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        updateNotification()

        mTimer = Timer()
        mVibratorService = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        mLocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        /*mBroadCastReceiver = object : BroadcastReceiver() {
            override fun onReceive(contxt: Context?, intent: Intent?) {
                Log.e(TAG, "onReceive: " + intent.toString())
            }
        }
        val filter = IntentFilter(INTENT_ACTION).apply {
            addAction(INTENT_ACTION)
        }
        registerReceiver(mBroadCastReceiver, filter)*/
    }

    override fun onStartCommand(intent: Intent?, startid: Int, startId: Int): Int {
        if (intent != null) {
            lower_level = intent.getIntExtra("lower_level", lower_level)
            upper_level = intent.getIntExtra("upper_level", upper_level)
            watch_gps = intent.getBooleanExtra("watch_gps", watch_gps)
            auto_start = intent.getBooleanExtra("auto_start", auto_start)
            Log.i( TAG,"Apply change: Lower level: $lower_level, Upper level: $upper_level, watch_gps: $watch_gps, auto_start: $auto_start")
            writePreferences()
            updateNotification()

            stopTimer()
            stopHRWatch()
            mLocationManager!!.unregisterGnssStatusCallback(mGnssStatusCallback);

            val gpsActive = false
            //mLocationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)

            if (!watch_gps || gpsActive) {
                startHRWatch()
            }

            if (watch_gps) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                    mLocationManager!!.registerGnssStatusCallback(mGnssStatusCallback)
                else
                    Log.e(TAG, "Missing permission")
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.i(TAG, "onBind")
        return null
    }

    private fun readPreferences() {
        lower_level = mPreferences!!.getInt("lower_level", lower_level)
        upper_level = mPreferences!!.getInt("upper_level", upper_level)
        watch_gps = mPreferences!!.getBoolean("watch_gps", watch_gps)
        auto_start = mPreferences!!.getBoolean("auto_start", auto_start)
        Log.i(TAG, "Reading lower_level: $lower_level, upper_level: $upper_level, watch_gps: $watch_gps, auto_start: $auto_start")
    }

    private fun writePreferences() {
        val editor = mPreferences!!.edit()
        editor.putInt("lower_level", lower_level)
        editor.putInt("upper_level", upper_level)
        editor.putBoolean("watch_gps", watch_gps)
        editor.putBoolean("auto_start", auto_start)
        editor.commit();
        Log.i(TAG, "Saving lower_level: $lower_level, upper_level: $upper_level, watch_gps: $watch_gps, auto_start: $auto_start")
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor == mHeartRateSensor) {
            Log.i(TAG, "onAccuracyChanged - accuracy: $accuracy")
            if (accuracy == 3 || last_accuracy == 3) {
                last_acc_good_time = Instant.now().epochSecond
            }
            last_accuracy = accuracy
        }
    }

    fun isAccuracyGood() : Boolean {
        return last_accuracy == 3 || ( last_accuracy == 2 && Instant.now().epochSecond - last_acc_good_time < 30)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_HEART_RATE) {
            last_hr = event.values[0].toInt()
            last_hr_time = Instant.now().epochSecond
            var new_state = if (last_hr < lower_level || !isAccuracyGood())
                0
            else if (last_hr >= lower_level && last_hr < upper_level)
                1
            else
                2

            Log.i(TAG, "Received HR: $last_hr, last_accuracy: $last_accuracy, New state: $new_state, Old state: $timer_state")

            if (new_state != timer_state) {
                stopTimer()
                timer_state = new_state

                if (timer_state > 0) {
                    Log.i(TAG, "Starting timer task")
                    mTimerTask = object : TimerTask() {
                        override fun run() {
                            val now = Instant.now().epochSecond
                            if (timer_state == 0) {
                                Log.i(TAG, "Timer canceled, state is 0")
                                stopTimer()
                            }
                            else if (now - last_hr_time > 1200000) {
                                Log.i(TAG, "Timer canceled, last HR too old")
                                stopTimer()
                            }
                            else if (now - last_hr_time > 30000) {
                                Log.i(TAG, "Not vibrating, last HR too old")
                            }
                            else if (!isAccuracyGood()) {
                                Log.i(TAG, "Not vibrating, accuracy too low")
                            }
                            else
                                vibrate()
                        }
                    }
                    mTimer!!.scheduleAtFixedRate(mTimerTask, 1000, (15000 - 5000 * timer_state).toLong())
                }
            }
            updateNotification()
        }
    }

    fun startHRWatch() {
        last_accuracy = 0
        last_hr = 0
        last_hr_time = 0
        last_acc_good_time = 0
        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        mHeartRateSensor = mSensorManager!!.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        mSensorManager!!.registerListener(this, mHeartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
        vibrate()
    }

    fun stopHRWatch() {
        if (mSensorManager != null) {
            mSensorManager!!.unregisterListener(this)
            mSensorManager = null
            mHeartRateSensor = null
        }
        updateNotification()
    }

    fun stopTimer() {
        if (mTimerTask != null) {
            Log.i(TAG, "Stopping timer task")
            mTimerTask!!.cancel()
            mTimer!!.purge()
            timer_state = 0
            mTimerTask = null
        }
    }

    fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mVibratorService!!.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            mVibratorService!!.vibrate(500)
        }
    }
}
