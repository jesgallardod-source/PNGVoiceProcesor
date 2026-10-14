package org.PNGV.audio;

/**
 * Callback que procesa cada buffer PCM 16-bit mono emitido por
 * {@link AudioCapture} desde su hilo de captura.
 *
 * <p>Permite conectar consumidores de audio (p. ej. un reconocedor de
 * fonemas como {@link org.PNGV.phoneme.VoskRecognizer}) al pipeline de
 * captura sin acoplar {@code AudioCapture} a ninguna implementación concreta.
 *
 * <p><b>Convenios de thread:</b>
 * <ul>
 *   <li>El método se invoca <b>desde el hilo de audio</b> (no el FX thread).
 *       Las implementaciones deben ser thread-safe o delegar a tipos
 *       thread-safe.</li>
 *   <li>Debe ser <b>rápido</b>: bloquear aquí bloquea la captura.</li>
 *   <li>Las excepciones se capturan y se logean; no se propagan al hilo de audio.</li>
 * </ul>
 */
@FunctionalInterface
public interface AudioFrameProcessor {

    /**
     * Procesa un buffer PCM 16-bit mono.
     *
     * @param pcm    buffer PCM 16-bit mono (little-endian)
     * @param length número de <b>bytes</b> válidos en {@code pcm}
     */
    void process(byte[] pcm, int length);
}
