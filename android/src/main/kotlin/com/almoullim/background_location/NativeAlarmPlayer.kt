package com.almoullim.background_location

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

internal interface AlarmAudio {
    fun start(tripId: String)
    fun stop()
}

/** One player owned by the service, with no Flutter engine or asset dependency. */
internal class NativeAlarmPlayer(
    private val context: Context,
    private val report: (String, String, String?) -> Unit,
    private val makePlayer: () -> MediaPlayer = { MediaPlayer() },
    private val loadAsset: (MediaPlayer) -> Unit = { player ->
        context.resources.openRawResourceFd(R.raw.map_alarm).use {
            player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
        }
    },
) : AlarmAudio {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var focusRequest: AudioFocusRequest? = null
    private var player: MediaPlayer? = null
    private var trip: String? = null
    private var focused = false
    private var prepared = false
    private var fallback = false
    private var attempts = 0
    private val preparationLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MapAlarm:prepare").apply { setReferenceCounted(false) }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (trip != null) {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> { focused = true; playIfReady() }
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    focused = false
                    player?.let { if (it.isPlaying) it.pause() }
                    emit("WAITING_FOR_FOCUS")
                    if (change == AudioManager.AUDIOFOCUS_LOSS) scheduleRetry()
                }
            }
        }
    }

    override fun start(tripId: String) {
        if (trip == tripId) return
        stop()
        trip = tripId
        attempts = 0
        prepare()
    }

    private fun prepare() {
        if (trip == null) return
        attempts++
        try {
            emit("PREPARING")
            preparationLock.acquire(15_000)
            focused = requestFocus() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            val current = makePlayer()
            player = current
            prepared = false
            current.setAudioAttributes(attributes)
            current.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            current.isLooping = true
            current.setVolume(1f, 1f)
            current.setOnPreparedListener {
                if (player !== current || trip == null) return@setOnPreparedListener
                prepared = true
                handler.removeCallbacks(timeout)
                releasePreparationLock()
                playIfReady()
            }
            current.setOnErrorListener { _, what, extra ->
                if (player === current && trip != null) failed("MediaPlayer error $what/$extra")
                true
            }
            if (fallback) current.setDataSource(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            else loadAsset(current)
            current.prepareAsync()
            handler.postDelayed(timeout, 12_000)
        } catch (error: Exception) {
            failed(error.toString())
        }
    }

    private val timeout = Runnable { if (trip != null && !prepared) failed("Audio preparation timed out") }
    private val retry = Runnable {
        if (trip != null) { releasePlayer(); prepare() }
    }

    private fun requestFocus(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusListener, handler).build()
            focusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(focusListener, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun playIfReady() {
        if (trip == null || !prepared) return
        if (!focused) { emit("WAITING_FOR_FOCUS"); scheduleRetry(); return }
        try {
            player?.start()
            handler.removeCallbacks(retry)
            emit("PLAYING")
        } catch (error: Exception) { failed(error.toString()) }
    }

    private fun failed(message: String) {
        emit("ERROR", message)
        releasePlayer()
        fallback = true
        scheduleRetry()
    }

    private fun scheduleRetry() {
        handler.removeCallbacks(retry)
        if (trip != null && attempts < 5) handler.postDelayed(retry, 5_000)
    }

    private fun emit(status: String, error: String? = null) {
        val id = trip ?: return
        Log.i("MapAlarmAudio", "$id $status ${error ?: ""}")
        report(id, status, error)
    }

    private fun releasePreparationLock() { if (preparationLock.isHeld) preparationLock.release() }
    private fun releasePlayer() {
        handler.removeCallbacks(timeout)
        player?.setOnPreparedListener(null)
        player?.setOnErrorListener(null)
        player?.release()
        player = null
        prepared = false
        releasePreparationLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        else { @Suppress("DEPRECATION") manager.abandonAudioFocus(focusListener) }
        focusRequest = null
        focused = false
    }

    override fun stop() {
        trip = null // invalidate all queued callbacks before releasing anything
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        fallback = false
    }
}
