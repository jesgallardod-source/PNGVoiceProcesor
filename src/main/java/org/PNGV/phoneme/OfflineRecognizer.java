package org.PNGV.phoneme;

/**
 * Implementación "offline" del {@link PhonemeRecognizer} que <b>no necesita
 * modelo de ASR</b>. Útil para:
 * <ul>
 *   <li>Tests unitarios (no requiere binarios nativos de Vosk).</li>
 *   <li>Demo / modo "texto a voz" donde el usuario escribe en vez de hablar.</li>
 * </ul>
 *
 * <p>Usa {@link SyllableSplitter#dominantViseme(String)} para decidir el viseme.
 */
public class OfflineRecognizer implements PhonemeRecognizer {

    private volatile Viseme current = Viseme.NEUTRAL;

    @Override
    public Viseme recognize(String text) {
        Viseme v = SyllableSplitter.dominantViseme(text);
        this.current = v;
        return v;
    }

    @Override
    public Viseme currentViseme() {
        return current;
    }

    @Override
    public void close() {
        this.current = Viseme.NEUTRAL;
    }
}
