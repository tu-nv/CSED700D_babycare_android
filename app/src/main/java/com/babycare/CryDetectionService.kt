package com.babycare

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jetbrains.kotlinx.multik.api.abs
import java.lang.Math.min
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.timerTask
import kotlin.math.*

class CryDetectionService : Service(), SensorEventListener {
    companion object {
        var isDetecting = AtomicBoolean(false)
        var isCrying = MutableLiveData(false)
        var needToSleep = AtomicBoolean(true)
        var soothingMode = R.id.rad_btn_play_auto
        var soundChoice = R.raw.twinkle
    }

    private lateinit var mediaPlayer: MediaPlayer

    private val ONGOING_NOTIFICATION_ID = 1
    private val CHANNEL_ID = "CryDetectionServiceChannelId"
    private val CHANNEL_NAME = "CryDetectionServiceChannel"

    private val DETECTION_PERIOD_MS = 4000L

    // for raw audio, use MediaRecorder.AudioSource.UNPROCESSED, see note in MediaRecorder section
    private val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
    private val SAMPLE_RATE = 8000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT
    private val NUM_SAMPLES = 32256 // output from training
    private val SOUND_THRESHOLD = 0.2


    private lateinit var audioRecord : AudioRecord

    private lateinit var mSensorManager : SensorManager
    private lateinit var mAccel : Sensor
    private val LIGHT_THRESHOLD = 30

    private lateinit var timer : Timer
    private var detectionCnt = 0
    private var sumDelay = 0.0

    private var stopCryCount = 0

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var model: Model

    override fun onBind(intent: Intent): IBinder {
        TODO("Return the communication channel to the service.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || isDetecting.get()) return START_NOT_STICKY

        makeForeground()
        initAudioRecord()

        mediaPlayer = MediaPlayer.create(this, soundChoice)
        mediaPlayer.isLooping = true


        // init light sensor
        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        mAccel = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        mSensorManager.registerListener(this, mAccel, 2_000_000)

        startTimerDetectCry()
        isDetecting.set(true)

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ActionClassifier::EnergyMonitorTag").apply {
                acquire(30*60*1000L /*30 minutes*/)
            }
        }

        return START_NOT_STICKY
    }

    private fun makeForeground() {
        val pendingIntent: PendingIntent =
            Intent(this, CryDetectionService::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, 0)
            }
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setContentText("Cry Detection running ...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(ONGOING_NOTIFICATION_ID, notification)
    }



    private fun startMusic() {
        if (!mediaPlayer.isPlaying) {
            mediaPlayer.start()
        }
    }

    private fun destroyMusic() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.stop()
            mediaPlayer.reset()
        }
    }

    private fun startToy() {
        GlobalScope.launch(Dispatchers.IO) {
//            print("Start toy")
            TuyaSignin.setBabycareToyState(true)
        }
    }

    private fun stopToy() {
        GlobalScope.launch(Dispatchers.IO) {
//            print("Stop toy")
            TuyaSignin.setBabycareToyState(false)
        }
    }

    private fun initAudioRecord() {

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val toastText = "This app require Audio Recording permission to work"
            Toast.makeText(applicationContext, toastText, Toast.LENGTH_LONG).show()
            return
        }

        val bufferSizeRec = NUM_SAMPLES * 4 // each sample is 32 bit float (4 bytes)
        audioRecord = AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSizeRec)
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) { // check for proper initialization
            Toast.makeText(applicationContext, "Init audio record error", Toast.LENGTH_LONG).show()
            return
        }

        audioRecord.startRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isDetecting.get()) {
            isDetecting.set(false)
            wakeLock.release()
            audioRecord.stop()
            timer.cancel()
            timer.purge()
            mSensorManager.unregisterListener(this, mAccel)
            if (mediaPlayer.isPlaying) {
                mediaPlayer.stop()
                mediaPlayer.reset()
            }
            mediaPlayer.release()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null && event.sensor?.type == Sensor.TYPE_LIGHT) {
//            print("light sensor value: ${event.values[0]}")
            needToSleep.set(event.values[0] <= LIGHT_THRESHOLD)
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}

    private fun startTimerDetectCry() {
        timer = Timer()
        model = Model(this)
        val data = FloatArray(NUM_SAMPLES)
        timer.scheduleAtFixedRate(timerTask {
            val startTime = System.nanoTime()
            var predClass = 1

            audioRecord.read(data, 0, data.size, AudioRecord.READ_BLOCKING)

            val maxAmplitude = max(abs(data.maxOrNull() ?: 0.0f), abs(data.minOrNull() ?: 0.0f))
            if (maxAmplitude < SOUND_THRESHOLD) {
                predClass = 1
            } else {
                predClass = model.predict(data)
//                println("sound exceed threshold. predict the sound type ...")
            }

//            println("Predicted class: ${predClass} maxAmplitude: ${maxAmplitude}, energy: ${calEngergy(data)}")

            if (predClass == 0) {
                stopCryCount = 0
                when (soothingMode) {
                    R.id.rad_btn_play_sound -> {
//                        print("play music")
                        startMusic()

                    }
                    R.id.rad_btn_play_toy -> {
                        startToy()
//                        print("play toy")
                    }
                    else -> {
                        if (needToSleep.get()) {
//                            print("play music 2")
                            startMusic()
                        } else {
//                            print("play toy 2")
                            startToy()
                        }
                    }
                }
            } else {
                // only consider stop crying after 5 stop cry detected in a row
                if (stopCryCount < 500) stopCryCount += 1
                if (stopCryCount == 5) {
                    destroyMusic()
                    stopToy()
                }
            }


            // measure detection time
            if (detectionCnt < 50) {
                val detectionTimeSec = (System.nanoTime() - startTime) / 1_000_000_000.0
                sumDelay += detectionTimeSec
                detectionCnt++
            } else if (detectionCnt == 50) {
//                println("average inference delay over ${detectionCnt} detection times is: ${sumDelay/detectionCnt}")
                detectionCnt++
            }


        }, 1000, DETECTION_PERIOD_MS)
    }

    private fun calEngergy(data: FloatArray, limit: Int = 1000): Float {
        var sum = 0.0f
        val maxItems = kotlin.math.min(limit, data.size)
        for (i in 0..maxItems) {
            sum += data[i] * data[i]
        }
        return sqrt(sum)/maxItems
    }
}