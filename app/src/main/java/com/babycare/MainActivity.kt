package com.babycare

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    var mediaPlayer: MediaPlayer? = null
    val RAD_SOUND_SELECTION = "rad_sound_selection"
    val RAD_SOOTHING_MODE_SELECTION = "rad_soothing_mode_selection"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestAudioRecordPermission()

        val cryDetectionServiceIntent = Intent(this, CryDetectionService::class.java)
        val btnCryDetection = findViewById<Button>(R.id.btnCryDetection)
        btnCryDetection.setOnClickListener {
            if (!CryDetectionService.isDetecting.get()) {
                ContextCompat.startForegroundService(this, cryDetectionServiceIntent)
                btnCryDetection.text = "Stop detecting"
            } else {
                stopService(cryDetectionServiceIntent)
                btnCryDetection.text = "Start detecting"
            }
        }

        val sharedPref = getPreferences(Context.MODE_PRIVATE)

        val radSoundSelection = sharedPref.getInt(RAD_SOUND_SELECTION, 0)
        findViewById<RadioGroup>(R.id.rad_sound_selection).check(radSoundSelection)
        CryDetectionService.soundChoice = getSoundFileName()

        findViewById<RadioGroup>(R.id.rad_sound_selection).setOnCheckedChangeListener { radioGroup, i ->
            with (sharedPref.edit()) {
                putInt(RAD_SOUND_SELECTION, i)
                apply()
            }
            CryDetectionService.soundChoice = getSoundFileName()
        }

        val radSoothingModeSelection = sharedPref.getInt(RAD_SOOTHING_MODE_SELECTION, 0)
        findViewById<RadioGroup>(R.id.rad_soothing_mode_selection).check(radSoothingModeSelection)
        CryDetectionService.soothingMode = findViewById<RadioGroup>(R.id.rad_soothing_mode_selection).checkedRadioButtonId

        findViewById<RadioGroup>(R.id.rad_soothing_mode_selection).setOnCheckedChangeListener { radioGroup, i ->
            with (sharedPref.edit()) {
                putInt(RAD_SOOTHING_MODE_SELECTION, i)
                apply()
            }
            CryDetectionService.soothingMode = findViewById<RadioGroup>(R.id.rad_soothing_mode_selection).checkedRadioButtonId
        }


        val energyMonServiceIntent = Intent(this, EnergyMonitorService::class.java)
        val btnMonitorEnergy = findViewById<Button>(R.id.btn_monitor_energy)
        // the service will stop and destroy itself after finish monitoring
        btnMonitorEnergy.setOnClickListener {
            ContextCompat.startForegroundService(this, energyMonServiceIntent)
        }


        EnergyMonitorService.energyMonResult.observe(this, { result ->
            findViewById<TextView>(R.id.tv_energy_result).text = result
        })


//        CryDetectionService.isCrying.observe(this, {
//            if (it == true) {
//                when (findViewById<RadioGroup>(R.id.rad_soothing_mode_selection).checkedRadioButtonId) {
//                    R.id.rad_btn_play_sound -> startMusic()
//                    R.id.rad_btn_play_toy -> startToy()
//                    else -> {
//                        if (CryDetectionService.needToSleep.get()) {
//                            startMusic()
//                        } else {
//                            startToy()
//                        }
//                    }
//                }
//            } else {
//                destroyMusic()
//            }
//        })
    }

    private fun startMusic() {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(this, getSoundFileName())
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
        }
    }

    private fun destroyMusic() {
        mediaPlayer?.stop()
        mediaPlayer?.reset()
        mediaPlayer?.release()
    }

    private fun startToy() {
        GlobalScope.launch(Dispatchers.IO) {
            print("Start toy")
            TuyaSignin.setBabycareToyState(true)
        }
    }


    private fun requestAudioRecordPermission() {
        // permission check and request: https://developer.android.com/training/permissions/requesting
        val requestPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted: Boolean ->
                if (!isGranted) {
                    val toastText = "This app require Audio Recording permission to work"
                    Toast.makeText(applicationContext, toastText, Toast.LENGTH_LONG).show()
                }
            }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }


    private fun getSoundFileName(): Int {
        return when (findViewById<RadioGroup>(R.id.rad_sound_selection).checkedRadioButtonId) {
            R.id.rad_btn_lulaby_1 -> R.raw.twinkle
            R.id.rad_btn_lulaby_2 -> R.raw.rockabyebaby
            R.id.rad_btn_noise_1 -> R.raw.rain
            R.id.rad_btn_noise_2 -> R.raw.whitenoise
            else ->  R.raw.twinkle
        }
    }

}