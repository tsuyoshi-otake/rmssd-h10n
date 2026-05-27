package dev.otake.rmssdh10n;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;

/**
 * Japanese text-to-speech for the relax-mode voice readout. Owned by
 * {@link MonitorService}; spoken from the engine's 1 Hz tick. Because the
 * service holds a PARTIAL_WAKE_LOCK, this plays with the screen off / app
 * backgrounded / phone in a pocket (the WebView's JS timer cannot).
 *
 * Robust by design: every entry point swallows errors so a flaky system TTS
 * engine can never break the monitor loop. Construct on the main thread (the
 * TextToSpeech callback handler binds to the calling thread); speak() is safe
 * from any thread.
 */
public class TtsSpeaker implements HrvEngine.Speaker {
    private static final String TAG = "TtsSpeaker";
    private static final String UTT_ID = "rmssd-relax";

    private final AudioManager audio;
    private TextToSpeech tts;
    private volatile boolean ready = false;
    private volatile String pending = null;       // spoken once init completes (first-enable confirmation)
    private AudioFocusRequest focusReq;
    private volatile boolean focusHeld = false;

    public TtsSpeaker(Context ctx) {
        Context app = ctx.getApplicationContext();
        this.audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        try {
            tts = new TextToSpeech(app, status -> {
                if (status != TextToSpeech.SUCCESS) { Log.w(TAG, "TTS init failed: " + status); return; }
                try {
                    int r = tts.setLanguage(Locale.JAPANESE);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Japanese voice unavailable (" + r + "); using default locale");
                    }
                    tts.setSpeechRate(0.95f);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        tts.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build());
                    }
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String id) {}
                        @Override public void onDone(String id) { abandonFocus(); }
                        @Override public void onError(String id) { abandonFocus(); }
                    });
                    ready = true;
                    String p = pending; pending = null;
                    if (p != null) speak(p);
                } catch (Throwable t) { Log.w(TAG, "TTS configure error", t); }
            });
        } catch (Throwable t) {
            Log.w(TAG, "TTS construct error", t);
        }
    }

    /** Speak now, replacing anything in progress (readings should never queue up). */
    @Override
    public void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (!ready || tts == null) { pending = text; return; } // play once init completes
        try {
            requestFocus();
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTT_ID);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTT_ID);
        } catch (Throwable t) {
            Log.w(TAG, "speak error", t);
            abandonFocus();
        }
    }

    public void shutdown() {
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        tts = null; ready = false; pending = null;
        abandonFocus();
    }

    // Transient-duck focus around each utterance so background audio lowers, then restores.
    private void requestFocus() {
        if (audio == null || focusHeld) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .build();
                audio.requestAudioFocus(focusReq);
            } else {
                audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
            focusHeld = true;
        } catch (Throwable t) { Log.w(TAG, "focus request error", t); }
    }

    private void abandonFocus() {
        if (audio == null || !focusHeld) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusReq != null) audio.abandonAudioFocusRequest(focusReq);
            } else {
                audio.abandonAudioFocus(null);
            }
        } catch (Throwable ignored) {}
        focusHeld = false;
    }
}
