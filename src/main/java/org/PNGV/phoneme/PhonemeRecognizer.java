package org.PNGV.phoneme;

/**
 * Interfaz de reconocimiento de fonemas/sílabas en tiempo real.
 *
 * <p>Implementaciones:
 * <ul>
 *   <li>{@link VoskRecognizer} — ASR local con Vosk (español).</li>
 *   <li>{@link OfflineRecognizer} — sin modelo, para tests / demo.</li>
 * </ul>
 *
 * <p>El hilo de audio emite fragmentos de texto (palabras) y el reconocedor
 * los transforma en visemes.
 */
public interface PhonemeRecognizer extends AutoCloseable {

    /**
     * Procesa un fragmento de texto (palabra o palabras) y devuelve el viseme
     * que debe mostrarse.
     *
     * @param text texto reconocido (p. ej. "hola", "programación")
     * @return viseme dominante, o {@link Viseme#NEUTRAL}
     */
    Viseme recognize(String text);

    /**
     * Devuelve el viseme actual (el último emitido).
     */
    Viseme currentViseme();

    @Override
    void close();
}
