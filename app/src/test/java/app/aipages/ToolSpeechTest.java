package app.aipages;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Locale;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ToolSpeechTest {
    private Voice voice(String name, String tag, boolean online, Set<String> features) {
        return new Voice(name, Locale.forLanguageTag(tag), Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, online, features);
    }

    @Test public void choosesInstalledSpanishWithRequestedAccentOrEnginePreference() {
        Voice spain = voice("Spain", "es-ES", false, Set.of());
        Voice mexico = voice("Mexico", "es-MX", false, Set.of());
        Voice online = voice("Online", "es-MX", true, Set.of());
        Voice missing = voice("Missing", "es-MX", false, Set.of(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
        Voice english = voice("English", "en-US", false, Set.of());
        Set<Voice> voices = Set.of(spain, mexico, online, missing, english);
        assertEquals(mexico, ToolSpeech.selectVoice(voices, Locale.forLanguageTag("es-MX"), spain));
        assertEquals(spain, ToolSpeech.selectVoice(voices, Locale.forLanguageTag("es"), spain));
        assertNull(ToolSpeech.selectVoice(Set.of(online, missing, english), Locale.forLanguageTag("es"), online));
    }
}
