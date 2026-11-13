package io.github.boomboom.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * The app's standing with the system as a music player: it holds the audio focus while it plays, steps aside for
 * calls, alarms and other players, comes back when they are done if it was playing, and stops when headphones are
 * pulled out. The Android side of the iPhone app's `SystemAudio`.
 *
 * Everything here runs on the main thread.
 */
class SystemAudio(private val context: Context) {
    /**
     * Another app (or a call) took the audio (`began`), or gave it back (`shouldResume` says whether to carry on
     * playing).
     */
    var onInterruption: ((began: Boolean, shouldResume: Boolean) -> Unit)? = null

    /** The headphones that were playing went away. */
    var onOutputLost: (() -> Unit)? = null

    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** The audio focus, as the one place that knows whether it is held. */
    private sealed class Focus {
        data object None : Focus()
        data object Held : Focus()

        /** Lost for a moment (a call, a navigation prompt that does not let others duck): it may come back. */
        data object LostForAMoment : Focus()
    }

    private var focus: Focus = Focus.None

    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        // A notification or a navigation prompt that lets others duck: the system turns this app down by itself.
        .setWillPauseWhenDucked(false)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change -> focusChanged(change) }, main)
        .build()

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            BoomTrace.note("audio output lost (headphones out)")
            onOutputLost?.invoke()
        }
    }

    init {
        context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    /** Takes the audio focus before playing. Cheap to call again. False when the system refused (a call is on). */
    fun activate(): Boolean {
        if (focus == Focus.Held) return true
        val result = audio.requestAudioFocus(request)
        focus = if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) Focus.Held else Focus.None
        BoomTrace.note("audio focus ${if (focus == Focus.Held) "taken" else "refused"}")
        return focus == Focus.Held
    }

    /** Gives the focus back (playback stopped for good). */
    fun deactivate() {
        if (focus == Focus.None) return
        audio.abandonAudioFocusRequest(request)
        focus = Focus.None
        BoomTrace.note("audio focus given back")
    }

    private fun focusChanged(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another player took over for good: pause, and do not come back by ourselves.
                BoomTrace.note("audio focus lost")
                focus = Focus.None
                onInterruption?.invoke(true, false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                BoomTrace.note("audio interrupted")
                focus = Focus.LostForAMoment
                onInterruption?.invoke(true, false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // The system lowers the level by itself (setWillPauseWhenDucked(false)); the music carries on.
                BoomTrace.note("audio ducked")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val wasInterrupted = focus == Focus.LostForAMoment
                focus = Focus.Held
                BoomTrace.note("audio focus back, resume=$wasInterrupted")
                if (wasInterrupted) onInterruption?.invoke(false, true)
            }
        }
    }

    /** Where the sound is going, for the details line: "Headphones", "Bluetooth", "Speaker". Null when unknown. */
    val outputDescription: String?
        get() {
            val device = currentOutput() ?: return null
            return when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Speaker"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headphones"
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
                AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                else -> device.productName?.toString()?.takeIf { it.isNotBlank() }
            }
        }

    private fun currentOutput(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= 33) {
            return audio.getAudioDevicesForAttributes(attributes).firstOrNull()?.let { wanted ->
                audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == wanted.type }
            }
        }
        // Before Android 13 there is no direct question: headphones and Bluetooth win over the speaker, as the
        // system routes music.
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val order = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        return order.firstNotNullOfOrNull { type -> outputs.firstOrNull { it.type == type } }
    }
}
