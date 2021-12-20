package com.babycare

import android.content.Context
import com.jlibrosa.audio.JLibrosa
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.log10

class Model(private val context: Context) {
    private val model: Module
    private val jlibrosa = JLibrosa()

    init {
        model = LiteModuleLoader.load(assetFilePath(context, "model.ptl"))
    }

    fun predict(data: FloatArray): Int {
        // test
//        val testData = jlibrosa.loadAndReadAcrossChannels(assetFilePath(context, "audioset_4.311_8nErvD2m8dM_30000.wav"), -1, -1)
//        val melSpec = jlibrosa.generateMelSpectroGram(testData[0], 8000, 1024, 64, 512)


        val melSpec = jlibrosa.generateMelSpectroGram(data, 8000, 1024, 64, 512)
        val melSpecDb = powerToDb(melSpec)

        val melSpecFlatten = melSpecDb.flatMap { it.asList() }.toFloatArray()
        val input = Tensor.fromBlob(melSpecFlatten, longArrayOf(1, 1, 64, 64))
        val output = model.forward(IValue.from(input)).toTensor().dataAsFloatArray
        println("predict prob are: ${output.joinToString(", ")}")
        return output.indexOfFirst { it == output.maxOfOrNull { it2 -> it2 } }
    }

    private fun powerToDb(melS: Array<FloatArray>): Array<FloatArray> {
        // Source: https://github.com/chiachunfu/speech/blob/master/speechandroid/src/org/tensorflow/demo/mfcc/MFCC.java
        // Convert a power spectrogram (amplitude squared) to decibel (dB) units
        //  This computes the scaling ``10 * log10(S / ref)`` in a numerically
        //  stable way.
        val log_spec = Array(melS.size) { FloatArray(melS[0].size) }
        var maxValue = -100.0f
        for (i in melS.indices) {
            for (j in melS[0].indices) {
                val magnitude = abs(melS[i][j])
                if (magnitude > 1e-10) {
                    log_spec[i][j] = 10.0f * log10(magnitude)
                } else {
                    log_spec[i][j] = 10.0f * (-10)
                }
                if (log_spec[i][j] > maxValue) {
                    maxValue = log_spec[i][j]
                }
            }
        }

        //set top_db to 80.0
        for (i in melS.indices) {
            for (j in melS[0].indices) {
                if (log_spec[i][j] < maxValue - 80.0f) {
                    log_spec[i][j] = maxValue - 80.0f
                }
            }
        }
        //ref is disabled, maybe later.
        return log_spec
    }

    private fun assetFilePath(context: Context, asset: String): String {
        val file = File(context.filesDir, asset)

        try {
            val inpStream: InputStream = context.assets.open(asset)
            try {
                val outStream = FileOutputStream(file, false)
                val buffer = ByteArray(4 * 1024)
                var read: Int

                while (true) {
                    read = inpStream.read(buffer)
                    if (read == -1) {
                        break
                    }
                    outStream.write(buffer, 0, read)
                }
                outStream.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

}