package org.PNGV.phoneme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.PNGV.audio.AudioFrameProcessor;
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
import java.util.concurrent.ConcurrentLinkedQueue;

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
 *   recognizer.getResult()      → JSON {"text":"..."}   (frase completa)
 *   recognizer.getPartialResult() → JSON {"partial":"..."} (frase parcial)
 *   recognizer.getFinalResult() → JSON
 * </pre>
 *
 * <p><b>Ojo:</b> los resultados son JSON, no texto plano. Este reconocedor los
 * parsea en {@link #extractText(String)} para obtener el texto real antes de
 * mapearlo a un viseme.
 */
public class VoskRecognizer implements PhonemeRecognizer, AudioFrameProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(VoskRecognizer.class);

    /**
     * Frecuencia de muestreo (Hz) con la que está entrenado el modelo de
     * Vosk que se usa por defecto ({@code vosk-model-small-es-0.42}).
     * Se puede confirmar en el archivo
     * {@code conf/mfcc.conf} del modelo: {@code --sample-frequency=16000.0}.
     *
     * <p><b>Importante:</b> el {@link Recognizer} de Vosk <b>no hace
     * re-muestreo internamente</b>: asume que la señal PCM que recibe ya está
     * a la frecuencia con la que fue creado. Si se le pasa audio a otra
     * frecuencia (p. ej. 44100 Hz), el modelo interpreta la señal acelerada
     * y produce texto aleatorio / fin de frase inmediato. La app debe capturar
     * el audio a esta frecuencia.
     */
    public static final int EXPECTED_SAMPLE_RATE = 16000;

    private final Model model;
    private final Recognizer recognizer;
    private volatile Viseme current = Viseme.NEUTRAL;

    /**
     * Parser JSON para los resultados de Vosk.
     *
     * <p><b>IMPORTANTE:</b> {@code Recognizer.getResult()} y
     * {@code Recognizer.getPartialResult()} <b>no devuelven texto plano</b>,
     * devuelven un JSON:
     * <ul>
     *   <li>Final:   {@code {"text" : "hola mundo"}}</li>
     *   <li>Parcial: {@code {"partial" : "hola mun"}}</li>
     * </ul>
     * Hay que extraer el campo {@code text} o {@code partial} para obtener el
     * texto real. Si se usa la cadena JSON tal cual, {@link Viseme#fromWord}
     * no encuentra letras (el JSON empieza por {@code '{'}) y siempre devuelve
     * {@link Viseme#NEUTRAL}.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Cola de visemes emitidos por el reconocedor, en orden de aparición.
     * La UI (Application Thread) la consume vía {@link #pollViseme()} en cada
     * frame del {@code AnimationTimer}.
     *
     * <p>Usamos {@link ConcurrentLinkedQueue} porque el productor es el hilo de
     * audio y el consumidor es el hilo JavaFX: no hay bloqueo ni contención.
     */
    private final ConcurrentLinkedQueue<Viseme> visemeQueue = new ConcurrentLinkedQueue<>();

    /**
     * Último viseme encolado. Sirve para no encolar dos veces seguidas el mismo
     * viseme (los resultados parciales se procesan muchas veces para la misma
     * palabra y producirían "cuellos de botella" de visemes repetidos).
     */
    private volatile Viseme lastQueued = Viseme.NEUTRAL;

    /**
     * Crea un reconocedor a partir de la ruta al modelo de Vosk.
     *
     * @param config configuración de la app (contiene {@code modelPath} y {@code sampleRate})
     * @throws IllegalStateException si el modelo no existe
     */
    /**
     * Indica si el modelo de Vosk configurado existe en disco. Útil para
     * decidir si se activa el reconocimiento real o se usa el fallback por
     * volumen.
     *
     * @param config configuración (contiene {@code modelPath})
     * @return true si el directorio del modelo existe
     */
    public static boolean isModelAvailable(AppConfig config) {
        if (config == null || config.getModelPath() == null || config.getModelPath().isBlank()) {
            return false;
        }
        return Files.isDirectory(Paths.get(config.getModelPath()));
    }

    public VoskRecognizer(AppConfig config) {
        Path modelPath = Paths.get(config.getModelPath());
        if (!Files.exists(modelPath)) {
            throw new IllegalStateException(
                    "Modelo de Vosk no encontrado en: " + modelPath.toAbsolutePath()
                            + "\nDescárgalo desde https://alphacephei.com/vosk/models");
        }

        // El modelo se entrena a una frecuencia concreta (ver EXPECTED_SAMPLE_RATE
        // y conf/mfcc.conf del modelo). Si la config usa otra frecuencia, Vosk
        // NO re-muestrea internamente y el reconocimiento sale "aleatorio".
        // Avisamos para que el usuario lo corrija (y sugerimos el valor correcto).
        int sr = config.getSampleRate();
        if (sr != EXPECTED_SAMPLE_RATE) {
            LOG.warn("La frecuencia de muestreo configurada es {} Hz, pero el modelo "
                            + "Vosk espera {} Hz. Vosk no re-muestrea internamente, "
                            + "así que el reconocimiento producirá texto aleatorio. "
                            + "Cambia 'sampleRate' a {} en config/app.json.",
                    sr, EXPECTED_SAMPLE_RATE, EXPECTED_SAMPLE_RATE);
        }

        LibVosk.setLogLevel(LogLevel.INFO);
        LOG.info("Cargando modelo Vosk desde {} (sampleRate={})",
                modelPath.toAbsolutePath(), sr);
        try {
            this.model = new Model(modelPath.toString());
            this.recognizer = new Recognizer(model, EXPECTED_SAMPLE_RATE);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo cargar el modelo de Vosk", e);
        }
        LOG.info("Modelo Vosk cargado correctamente (a {} Hz)", EXPECTED_SAMPLE_RATE);
    }

    /**
     * Callback {@link AudioFrameProcessor}: se invoca desde el hilo de audio
     * con cada buffer PCM. Delega en {@link #acceptWaveForm(byte[], int)}.
     */
    @Override
    public void process(byte[] pcm, int length) {
        acceptWaveForm(pcm, length);
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
                // Resultado final: JSON con campo "text".
                String text = extractText(recognizer.getResult());
                if (text != null && !text.isBlank()) {
                    updateViseme(text);
                }
            } else {
                // Resultado parcial: JSON con campo "partial".
                String partial = extractText(recognizer.getPartialResult());
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
     * Extrae el texto reconocible del JSON devuelto por Vosk.
     *
     * <p>Vosk devuelve:
     * <ul>
     *   <li>Resultado final:   {@code {"text" : "hola mundo"}} → extrae {@code text}</li>
     *   <li>Resultado parcial: {@code {"partial" : "hola mun"}} → extrae {@code partial}</li>
     * </ul>
     *
     * <p>Es tolerante a fallos: si el JSON no se puede parsear o no contiene
     * ninguno de los dos campos, devuelve {@code null} (el llamador ya trata
     * {@code null} como "nada que actualizar"). También soporta el caso (poco
     * habitual) de que el resultado venga como texto plano, por si algún modelo
     * o versión lo devuelve así.
     *
     * @param rawJson cadena JSON de Vosk (o texto plano), puede ser {@code null}
     * @return el texto reconocible, o {@code null} si no se pudo extraer
     */
    static String extractText(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        String trimmed = rawJson.trim();
        // Caso defensivo: ya es texto plano (no empieza por '{').
        if (!trimmed.startsWith("{")) {
            return trimmed;
        }
        try {
            JsonNode node = JSON.readTree(trimmed);
            // "text" = resultado final, "partial" = resultado en curso.
            String candidate = null;
            JsonNode textNode = node.path("text");
            if (!textNode.isMissingNode() && !textNode.isNull() && textNode.isTextual()) {
                candidate = textNode.asText();
            } else {
                JsonNode partialNode = node.path("partial");
                if (!partialNode.isMissingNode() && !partialNode.isNull() && partialNode.isTextual()) {
                    candidate = partialNode.asText();
                }
            }
            // Normalizamos a null si no hay texto útil (vacio/blank).
            return (candidate != null && !candidate.isBlank()) ? candidate : null;
        } catch (Exception e) {
            LOG.debug("extractText: no se pudo parsear el JSON de Vosk: {}", e.getMessage());
            return null;
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
        Viseme v = Viseme.fromWord(lastWord);
        this.current = v;
        // Encola el viseme para que la UI lo consuma en orden (tick). Evitamos
        // repetir el mismo viseme en seguida para no saturar la cola.
        if (v != lastQueued) {
            lastQueued = v;
            visemeQueue.offer(v);
        }
    }

    @Override
    public Viseme currentViseme() {
        return current;
    }

    /**
     * Extrae (y elimina) el siguiente viseme pendiente de la cola, o {@code null}
     * si no hay ninguno. La UI lo llama desde el hilo JavaFX en cada frame del
     * {@code AnimationTimer}; la cola es thread-safe por lo que no hay contención
     * con el hilo de audio que la encola.
     *
     * @return el siguiente viseme reconocido, o {@code null} si la cola está vacía
     */
    public Viseme pollViseme() {
        return visemeQueue.poll();
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
