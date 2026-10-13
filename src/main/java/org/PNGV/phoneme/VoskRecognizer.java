package org.PNGV.phoneme;

import org.PNGV.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Implementación de {@link PhonemeRecognizer} usando <b>Vosk</b> (ASR local).
 *
 * <p>Requiere:
 * <ul>
 *   <li>Modelo descargado en {@link AppConfig#modelPath} (p. ej.
 *       {@code assets/models/vosk-model-small-es-0.42}).
 *       Se descarga de https://alphacephei.com/vosk/models</li>
 *   <li>Librería nativa de Vosk (se extrae del jar automáticamente por la
 *       dependencia {@code com.alphacephei:vosk}).</li>
 * </ul>
 *
 * <p>API de Vosk usada (confirmada contra el demo oficial):
 * <pre>
 *   new Model(dir)
 *   new Recognizer(model, sampleRate)
 *   recognizer.acceptWaveForm(byte[], int) → boolean (true = fin de frase)
 *   recognizer.getResult()      → String (frase completa)
 *   recognizer.getPartialResult() → String (frase parcial)
 *   recognizer.getFinalResult() → String
 * </pre>
 */
public class VoskRecognizer implements PhonemeRecognizer {

    private static final Logger LOG = LoggerFactory.getLogger(VoskRecognizer.class);

    private final Model model;
    private final Recognizer recognizer;
    private volatile Viseme current = Viseme.NEUTRAL;

    /**
     * Crea un reconocedor a partir de la ruta al modelo de Vosk.
     *
     * @param config configuración de la app (contiene {@code modelPath} y {@code sampleRate})
     * @throws IllegalStateException si el modelo no existe
     */
    public VoskRecognizer(AppConfig config) {
        Path modelPath = Paths.get(config.getModelPath());
        if (!Files.exists(modelPath)) {
            throw new IllegalStateException(
                    "Modelo de Vosk no encontrado en: " + modelPath.toAbsolutePath()
                            + "\nDescárgalo desde https://alphacephei.com/vosk/models");
        }
        LibVosk.setLogLevel(LogLevel.INFO);
        LOG.info("Cargando modelo Vosk desde {}", modelPath.toAbsolutePath());
        try {
            this.model = new Model(modelPath.toString());
            this.recognizer = new Recognizer(model, config.getSampleRate());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo cargar el modelo de Vosk", e);
        }
        LOG.info("Modelo Vosk cargado correctamente");
    }

    /**
     * Acepta un buffer PCM 16-bit mono.
     *
     * @param pcm    buffer PCM 16-bit mono
     * @param length longitud en bytes
     * @return true si hubo fin de frase (el texto es final)
     */
    public boolean acceptWaveForm(byte[] pcm, int length) {
        try {
            boolean endOfUtterance = recognizer.acceptWaveForm(pcm, length);
            if (endOfUtterance) {
                String text = recognizer.getResult();
                if (text != null && !text.isBlank()) {
                    updateViseme(text);
                }
            } else {
                String partial = recognizer.getPartialResult();
                if (partial != null && !partial.isBlank()) {
                    updateViseme(partial);
                }
            }
            return endOfUtterance;
        } catch (Exception e) {
            LOG.debug("acceptWaveForm: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Procesa un fragmento de texto (palabra o palabras) y devuelve el viseme
     * que debe mostrarse.
     *
     * @param text texto reconocido (p. ej. "hola", "programación")
     * @return viseme dominante, o {@link Viseme#NEUTRAL}
     */
    @Override
    public Viseme recognize(String text) {
        updateViseme(text);
        return current;
    }

    private void updateViseme(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String lastWord = lastWord(text);
        Viseme v = SyllableSplitter.dominantViseme(lastWord);
        this.current = v;
    }

    @Override
    public Viseme currentViseme() {
        return current;
    }

    @Override
    public void close() {
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Exception ignored) {
            }
        }
        if (model != null) {
            try {
                model.close();
            } catch (Exception ignored) {
            }
        }
        current = Viseme.NEUTRAL;
    }

    // ------------------------------------------------------------------

    private static String lastWord(String text) {
        String trimmed = text.trim();
        int lastSpace = trimmed.lastIndexOf(' ');
        return lastSpace < 0 ? trimmed : trimmed.substring(lastSpace + 1).trim();
    }
}
