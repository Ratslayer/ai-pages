package app.aipages;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;

/** One lazily initialized speech engine for the active tool. All state is owned by the UI thread. */
final class ToolSpeech {
    private record Request(String id, String text, Locale locale, BiConsumer<String, String> reply) {}
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private TextToSpeech engine;
    private boolean ready;
    private boolean closed;
    private Request current;

    ToolSpeech(Context context) {
        this.context = context.getApplicationContext();
    }

    void speak(String id, String text, String language, BiConsumer<String, String> reply) {
        Locale locale = Locale.forLanguageTag(language);
        if (text.trim().isEmpty() || text.length() > TextToSpeech.getMaxSpeechInputLength()
                || locale.getLanguage().isEmpty()) {
            reply.accept(null, "Provide text and a valid language for pronunciation.");
            return;
        }
        stop();
        current = new Request(id, text, locale, reply);
        if (engine == null) {
            engine = new TextToSpeech(context, status -> main.post(() -> initialized(status)));
        } else if (ready) {
            play();
        }
    }

    private void initialized(int status) {
        if (closed) return;
        if (status != TextToSpeech.SUCCESS) {
            finish(null, "Enable a text-to-speech engine in Android settings and try again.");
            engine.shutdown();
            engine = null;
            return;
        }
        ready = true;
        engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { completed(id, "spoken", null); }
            @Override public void onStop(String id, boolean interrupted) { completed(id, "stopped", null); }
            @Override public void onError(String id) { completed(id, null, "Could not play pronunciation."); }
            @Override public void onError(String id, int error) {
                if (error == TextToSpeech.ERROR_NOT_INSTALLED_YET) completed(id, "missing-voice", null);
                else onError(id);
            }
        });
        if (current != null) play();
    }

    private void completed(String id, String result, String error) {
        main.post(() -> {
            if (current != null && current.id().equals(id)) finish(result, error);
        });
    }

    private void play() {
        Locale locale = current.locale();
        int available = engine.setLanguage(locale);
        if (available == TextToSpeech.LANG_NOT_SUPPORTED) {
            finish(null, "Your Android speech engine does not support " + locale.getDisplayLanguage() + ".");
            return;
        }
        Set<Voice> voices = engine.getVoices();
        Voice voice = selectVoice(voices, locale, engine.getVoice());
        if (voice == null) {
            finish("missing-voice", null);
            return;
        }
        if (engine.setVoice(voice) != TextToSpeech.SUCCESS ||
                engine.speak(current.text(), TextToSpeech.QUEUE_FLUSH, null, current.id()) != TextToSpeech.SUCCESS) {
            finish(null, "Could not play pronunciation.");
        }
    }

    static Voice selectVoice(Set<Voice> voices, Locale locale, Voice preferred) {
        if (voices == null) return null;
        return voices.stream().filter(voice -> voice.getLocale().getLanguage().equals(locale.getLanguage())
                && !voice.isNetworkConnectionRequired()
                && (voice.getFeatures() == null || !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)))
                .max(Comparator.comparing((Voice voice) -> voice.getLocale().equals(locale))
                        .thenComparing(voice -> voice.equals(preferred))
                        .thenComparingInt(Voice::getQuality)
                        .thenComparing(Voice::getName)).orElse(null);
    }

    private void finish(String result, String error) {
        Request request = current;
        current = null;
        if (request != null) request.reply().accept(result, error);
    }

    void stop() {
        if (current == null) return;
        finish("stopped", null);
        if (ready) engine.stop();
    }

    void close() {
        closed = true;
        stop();
        if (engine != null) engine.shutdown();
    }
}
