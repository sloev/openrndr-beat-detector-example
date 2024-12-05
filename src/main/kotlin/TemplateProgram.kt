import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.loadFont
import org.openrndr.draw.loadImage
import org.openrndr.draw.tint
import kotlin.math.cos
import kotlin.math.sin
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.jvm.AudioDispatcherFactory
import be.tarsos.dsp.onsets.ComplexOnsetDetector
import be.tarsos.dsp.onsets.OnsetHandler
import org.openrndr.color.ColorHSVa
import kotlin.math.roundToInt

fun main() = application {
    val tempoDetector = TempoDetector()

    configure {
        width = 768
        height = 576

    }
    program.ended.listen {
        tempoDetector.stopDetection()
    }
    program {
        val image = loadImage("data/images/pm5544.png")
        val font = loadFont("data/fonts/default.otf", 64.0)
        var currentTempo = -1
        var beatDetected = false
        tempoDetector.startDetection(
            onTempoUpdate = { bpm ->
                currentTempo = bpm
            },
            onBeatDetected = {
                beatDetected = true

            }
        )
        var sat = 0.0
        extend {


            if (beatDetected) {
                sat = 1.0
            }
            drawer.fill = ColorHSVa(100.0, 1.0, 1.0, sat).toRGBa()

            drawer.circle(cos(seconds) * width / 2.0 + width / 2.0, sin(0.5 * seconds) * height / 2.0 + height / 2.0, 140.0)

            drawer.fontMap = font
            drawer.fill = ColorRGBa.WHITE
            val beatString = if(beatDetected) "beat" else ""
            drawer.text("bpm=$currentTempo, $beatString", 10.0, height / 2.0)
            beatDetected = false
            sat = 0.95*sat
        }
    }
}





class TempoDetector {
    private var dispatcher: AudioDispatcher? = null
    private var lastBeatTime = 0L
    private var beatIntervals = mutableListOf<Long>()
    private val BEAT_HISTORY_SIZE = 8

    // Configuration
    private val sampleRate = 22050
    private val bufferSize = 1024
    private val onsetThreshold = 0.1

    // Callbacks
    private var onTempoUpdate: ((Int) -> Unit)? = null
    private var onBeatDetected: (() -> Unit)? = null

    fun startDetection(
        onTempoUpdate: (Int) -> Unit,
        onBeatDetected: (() -> Unit)? = null
    ) {
        this.onTempoUpdate = onTempoUpdate
        this.onBeatDetected = onBeatDetected

        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(sampleRate, bufferSize, 0)

        val onsetHandler = OnsetHandler { _: Double, _: Double ->
            val currentTime = System.currentTimeMillis()
            onBeatDetected?.invoke()

            if (lastBeatTime > 0) {
                val interval = currentTime - lastBeatTime
                if (interval > 10) {
                    beatIntervals.add(interval)
                    if (beatIntervals.size > BEAT_HISTORY_SIZE) {
                        beatIntervals.removeAt(0)
                    }
                    calculateTempo()
                }
            }
            lastBeatTime = currentTime
        }

        val onsetDetector = ComplexOnsetDetector(bufferSize).apply {
            setThreshold(onsetThreshold)
            setHandler(onsetHandler)
        }

        dispatcher?.addAudioProcessor(onsetDetector)
        Thread(dispatcher, "Audio Thread").start()
    }

    private fun calculateTempo() {
        if (beatIntervals.size >= 4) {
            val mean = beatIntervals.average()
            val standardDeviation = beatIntervals.map { (it - mean) * (it - mean) }.average().let { Math.sqrt(it) }

            val filteredIntervals = beatIntervals.filter { interval ->
                Math.abs(interval - mean) <= 2 * standardDeviation
            }

            val averageInterval = filteredIntervals.average()
            val bpm = (60000.0 / averageInterval).roundToInt()

            if (bpm in 40..220) {
                onTempoUpdate?.invoke(bpm)
            }
        }
    }

    fun stopDetection() {
        dispatcher?.stop()
        dispatcher = null
        beatIntervals.clear()
        lastBeatTime = 0
        onTempoUpdate = null
        onBeatDetected = null
    }
}