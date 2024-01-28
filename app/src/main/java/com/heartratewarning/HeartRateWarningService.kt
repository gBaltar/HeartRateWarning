package com.heartratewarning

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import java.time.Instant
import java.util.Arrays
import java.util.Timer
import java.util.TimerTask
import java.util.UUID


class HeartRateWarningService : Service(), SensorEventListener {
    private val TAG : String = "HeartRateWarningService"
    private val notificationChannelId = "HeartRateWarning"
    //private val INTENT_ACTION : String = "com.google.android.clockwork.settings.action.GPS_ACTIVITY"
    //android.location.HIGH_POWER_REQUEST_CHANGE
    //TrainingService.action.TRAINING_STARTED

    private var lower_level : Int = 160
    private var upper_level : Int = 170
    private var broadcast_ble : Boolean = false
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
    private var mBluetoothManager: BluetoothManager? = null
    private var mGattServer : BluetoothGattServer? = null
    private var mCharacteristic : BluetoothGattCharacteristic? = null
    private val mRegisteredDevices = mutableSetOf<BluetoothDevice>()

    // Define UUIDs for service and characteristic
    private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HEART_RATE_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val HEART_RATE_CONTROL_POINT_CHAR_UUID = UUID.fromString("00002a39-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "BLE Advertise Started.")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE Advertise Failed: $errorCode")
        }
    }

    // Implement a GATT server callback
    private val gattServerCallback: BluetoothGattServerCallback =
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Device connected: " + device.address)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Device disconnected: " + device.address)
                    mRegisteredDevices.remove(device)
                }
            }

            @SuppressLint("MissingPermission")
            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                when (HEART_RATE_MEASUREMENT_CHARACTERISTIC_UUID) {
                    characteristic.uuid -> {
                        Log.i(TAG, "Read HEART_RATE_MEASUREMENT_CHARACTERISTIC_UUID")
                        val field = ByteArray(2)
                        val type = 0b0 // BPM as UINT8
                        val contactSupport = 0b1
                        val contactStatus = if (last_accuracy == 3) 0b1 else 0b0
                        field[0] = ((type shl 0) or (contactSupport shl 1) or (contactStatus shl 2)).toByte()
                        field[1] = last_hr.toByte()
                        mGattServer!!.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,field)
                    }
                    else -> {
                        // Invalid characteristic
                        Log.w(TAG, "Invalid Characteristic Read: " + characteristic.uuid)
                        mGattServer!!.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0,
                            null)
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                 descriptor: BluetoothGattDescriptor
            ) {
                if (CLIENT_CHARACTERISTIC_CONFIG_UUID == descriptor.uuid) {
                    Log.d(TAG, "Config descriptor read")
                    val returnValue = if (mRegisteredDevices.contains(device)) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    }
                    mGattServer?.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        returnValue)
                } else {
                    Log.w(TAG, "Unknown descriptor read request")
                    mGattServer?.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0, null)
                }
            }

            @SuppressLint("MissingPermission")
            override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
                                                  descriptor: BluetoothGattDescriptor,
                                                  preparedWrite: Boolean, responseNeeded: Boolean,
                                                  offset: Int, value: ByteArray) {
                if (CLIENT_CHARACTERISTIC_CONFIG_UUID == descriptor.uuid) {
                    if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                        Log.d(TAG, "Subscribe device to notifications: $device")
                        mRegisteredDevices.add(device)
                    } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                        Log.d(TAG, "Unsubscribe device from notifications: $device")
                        mRegisteredDevices.remove(device)
                    }

                    if (responseNeeded) {
                        mGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0, null)
                    }
                } else {
                    Log.w(TAG, "Unknown descriptor write request")
                    if (responseNeeded) {
                        mGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0, null)
                    }
                }
            }
        }

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

    @SuppressLint("ForegroundServiceType")
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
            title = when (timer_state) {
                0 -> "Low HR"
                1 -> "Heightened HR!"
                else -> "Too high HR!"
            }
        }
        val hrStr = if (mSensorManager != null) "HR: $last_hr Acc: $last_accuracy\n" else ""
        val notification: Notification = Notification.Builder(this,notificationChannelId)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSmallIcon(R.mipmap.heart_beat)
            .setContentTitle(title)
            .setContentText("${hrStr}Levels $lower_level/$upper_level")
            .setContentIntent(pendingIntent).build()
        startForeground(1, notification)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        Log.i(TAG, "HeartRateWarningService stopped")
        if (mGattServer != null) {
            mGattServer!!.close()
        }
        if (mBluetoothManager != null) {
            val bluetoothLeAdvertiser: BluetoothLeAdvertiser? =
                mBluetoothManager!!.adapter.bluetoothLeAdvertiser
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) ?: Log.w(TAG, "Failed to create advertiser")
        }
        stopHRWatch()
        stopTimer()
        mLocationManager!!.unregisterGnssStatusCallback(mGnssStatusCallback)
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
            broadcast_ble = intent.getBooleanExtra("broadcast_ble", broadcast_ble)
            watch_gps = intent.getBooleanExtra("watch_gps", watch_gps)
            auto_start = intent.getBooleanExtra("auto_start", auto_start)
            Log.i( TAG,"Apply change: Lower level: $lower_level, Upper level: $upper_level, watch_gps: $watch_gps, auto_start: $auto_start")
            writePreferences()
            updateNotification()

            stopTimer()
            stopHRWatch()
            mLocationManager!!.unregisterGnssStatusCallback(mGnssStatusCallback)

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

            if (broadcast_ble) {
                if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                    Log.w(TAG, "Bluetooth LE is not supported")
                    return START_NOT_STICKY
                }

                mBluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
                if (!mBluetoothManager!!.adapter.isEnabled) {
                    //val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    //startActivity(enableBtIntent)
                    mBluetoothManager!!.adapter.enable()
                }

                mGattServer = mBluetoothManager!!.openGattServer(this, gattServerCallback)
                val service =
                    BluetoothGattService(HEART_RATE_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                mCharacteristic = BluetoothGattCharacteristic(
                    HEART_RATE_MEASUREMENT_CHARACTERISTIC_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY
                    , BluetoothGattCharacteristic.PERMISSION_READ
                )
                service.addCharacteristic(mCharacteristic)
                val configDescriptor = BluetoothGattDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
                mCharacteristic!!.addDescriptor(configDescriptor)
                mGattServer!!.addService(service)

                Log.d(TAG, "GattServerService created.")

                val bluetoothLeAdvertiser: BluetoothLeAdvertiser? =
                    mBluetoothManager!!.adapter.bluetoothLeAdvertiser
                bluetoothLeAdvertiser?.let {
                    val settings = AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                        .setConnectable(true)
                        .setTimeout(0)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                        .build()

                    val data = AdvertiseData.Builder()
                        .setIncludeDeviceName(true)
                        .setIncludeTxPowerLevel(false)
                        .addServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
                        .build()

                    it.startAdvertising(settings, data, advertiseCallback)
                } ?: Log.w(TAG, "Failed to create advertiser")

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
        broadcast_ble = mPreferences!!.getBoolean("broadcast_ble", broadcast_ble)
        watch_gps = mPreferences!!.getBoolean("watch_gps", watch_gps)
        auto_start = mPreferences!!.getBoolean("auto_start", auto_start)
        Log.i(TAG, "Reading lower_level: $lower_level, upper_level: $upper_level, watch_gps: $watch_gps, auto_start: $auto_start")
    }

    private fun writePreferences() {
        val editor = mPreferences!!.edit()
        editor.putInt("lower_level", lower_level)
        editor.putInt("upper_level", upper_level)
        editor.putBoolean("broadcast_ble", broadcast_ble)
        editor.putBoolean("watch_gps", watch_gps)
        editor.putBoolean("auto_start", auto_start)
        editor.apply()
        Log.i(TAG, "Saving lower_level: $lower_level, upper_level: $upper_level, broadcast_ble: $broadcast_ble, watch_gps: $watch_gps, auto_start: $auto_start")
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
            val newState = if (last_hr < lower_level || !isAccuracyGood())
                0
            else if (last_hr in lower_level until upper_level)
                1
            else
                2

            Log.i(TAG, "Received HR: $last_hr, last_accuracy: $last_accuracy, New state: $newState, Old state: $timer_state")

            if (newState != timer_state) {
                stopTimer()
                timer_state = newState

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
            notifyRegisteredDevices()
        }
    }

    fun startHRWatch() {
        last_accuracy = 0
        last_hr = 0
        last_hr_time = 0
        last_acc_good_time = 0
        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        mHeartRateSensor = mSensorManager!!.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        mSensorManager!!.registerListener(this, mHeartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
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

    private fun notifyRegisteredDevices() {
        if (mRegisteredDevices.isEmpty()) {
            return
        }

        Log.i(TAG, "Sending update to ${mRegisteredDevices.size} subscribers")
        for (device in mRegisteredDevices) {
            //bluetoothGattServer?.notifyCharacteristicChanged(device, mCharacteristic, false)
        }
    }
}
