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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>API de Vosk usada (confirmada contra el jar 0.3.45 del proyecto):
 * <pre>
 *   new Model(dir)
 *   new Recognizer(model, sampleRate)
 *   recognizer.setMaxAlternatives(5)   // --nbest=5 (Fase 2.1 Opción B)
 *   recognizer.setPartialWords(true)   // timestamps por palabra
 *   recognizer.acceptWaveForm(byte[], int) → boolean (true = fin de frase)
 *   recognizer.getResult()      → JSON (1 alternativa o "alternatives":[...])
 *   recognizer.getPartialResult() → JSON (idem, campo "partial")
 *   recognizer.getFinalResult() → JSON
 * </pre>
 *
 * <p><b>Ojo:</b> los resultados son JSON, no texto plano. Este reconocedor los
 * parsea en {@link #extractTextBest(String)} para obtener el texto real de la
 * alternativa más coherente antes de mapearlo a un viseme.
 *
 * <p><b>Fase 2.1 (Opción B) del PLAN:</b> mantener el modelo {@code small}
 * pero pedir a Vosk <b>5 alternativas</b> (nbest=5) por cada resultado,
 * eligiendo la de mayor confianza. Esto mejora la coherencia del texto
 * reconocido sin cambiar de modelo. Además se activan
 * <b>palabras con timestamps</b> ({@code setPartialWords(true)}) para tener
 * la información temporal que usaremos en la Fase 4 (sincronización).
 *
 * <p><b>Fase 2.2 del PLAN:</b> parsear el <b>JSON extendido</b> que devuelve
 * Vosk cuando {@code setPartialWords(true)} está activo. En este jar
 * ({@code com.alphacephei:vosk:0.3.45}) <b>no existe</b> el método
 * {@code getPartialJson()} (verificado con {@code javap}); los métodos
 * {@code getResult()} / {@code getPartialResult()} ya devuelven el JSON
 * extendido:
 * <pre>
 * {
 *   "partial": "hola mun",
 *   "words": [
 *     {"word": "hola", "start": 0.12, "end": 0.45, "conf": 0.92},
 *     {"word": "mun",  "start": 0.61, "end": 0.78, "conf": 0.71}
 *   ]
 * }
 * </pre>
 * El método {@link #parseWords(String)} extrae esas palabras con sus
 * timestamps (segundos desde el inicio de la frase) y confianza en el record
 * {@link WordTiming}, que es la base para la {@code VisemeTimeline} de la
 * Fase 4 (sincronización viseme↔tiempo).
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

    // ------------------------------------------------------------------
    // FASE 2.2 – WordTiming: timestamps por palabra desde el JSON extendido
    // ------------------------------------------------------------------

    /**
     * Representa una palabra reconocida por Vosk con su <b>timing</b>
     * (segundos desde el inicio de la frase) y <b>confianza</b>.
     *
     * <p><b>Origen:</b> cuando {@code setPartialWords(true)} está activo,
     * {@code getPartialResult()} / {@code getResult()} devuelven un JSON
     * extendido con un array de palabras:
     * <pre>
     * {
     *   "partial": "hola mun",
     *   "result": [
     *     {"word": "hola", "start": 0.12, "end": 0.45, "conf": 0.92},
     *     {"word": "mun",  "start": 0.61, "end": 0.78, "conf": 0.71}
     *   ]
     * }
     * </pre>
     * Este record encapsula cada entrada de ese array.
     *
     * <p><b>Uso:</b> la Fase 4 (VisemeTimeline) usará {@code start} y {@code end}
     * para calcular la duración real de cada palabra y asignar visemes en el
     * momento correcto, en vez de asumir una duración fija por sílaba.
     *
     * @param word   la palabra reconocida (p. ej. "hola")
     * @param start  inicio en segundos desde el inicio de la frase (p. ej. 0.12)
     * @param end    fin en segundos desde el inicio de la frase (p. ej. 0.45)
     * @param conf   confianza de reconocimiento (0.0 – 1.0), o -1 si no está disponible
     */
    public record WordTiming(String word, double start, double end, double conf) {

        /**
         * Duración de la palabra en <b>segundos</b> ({@code end - start}).
         *
         * @return duración en segundos, siempre &ge; 0
         */
        public double duration() {
            return Math.max(0.0, end - start);
        }

        /**
         * Duración de la palabra en <b>milisegundos</b>.
         *
         * @return duración en ms, siempre &ge; 0
         */
        public long durationMs() {
            return Math.round(duration() * 1000);
        }
    }

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
            // --- FASE 2.1 (Opción B): nbest + timestamps por palabra ---
            // Pedimos N alternativas por resultado para poder elegir la más
            // coherente (máxima confianza) en vez de aceptar ciegamente la
            // primera. Esto reduce alucinaciones y mejora la precisión
            // sin cambiar de modelo.
            int nbest = Math.max(1, config.getNbest());
            this.recognizer.setMaxAlternatives(nbest);
            // Habilitamos timestamps por palabra (word.start / word.end) para
            // que la Fase 4 (VisemeTimeline) pueda sincronizar visemes con
            // el tiempo real de pronunciación.
            this.recognizer.setPartialWords(true);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo cargar el modelo de Vosk", e);
        }
        LOG.info("Modelo Vosk cargado correctamente (a {} Hz, nbest={}, partialWords=true)",
                EXPECTED_SAMPLE_RATE, Math.max(1, config.getNbest()));
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
            long t0 = System.currentTimeMillis();
            boolean endOfUtterance = recognizer.acceptWaveForm(pcm, length);

            // --- LOGGING DETALLADO (Fase 1.1 del PLAN) ---
            // Objetivo: ver exactamente qué JSON/texto produce Vosk al hablar
            // frases conocidas, para diagnosticar la calidad del reconocimiento
            // y la sincronización letra→viseme.
            String rawJson;
            String label;
            if (endOfUtterance) {
                // --- FASE 2.3: usar getFinalResult() (confirmado, con timestamps) ---
                // getFinalResult() devuelve el resultado DEFINITIVO de la frase
                // con timestamps y confianza confirmados. A diferencia de
                // getResult() que puede devolver un estado intermedio,
                // getFinalResult() garantiza que la frase está completa.
                rawJson = recognizer.getFinalResult();
                label = "FINAL_CONFIRMED";
            } else {
                rawJson = recognizer.getPartialResult();
                label = "PARTIAL";
            }
            long elapsedMs = System.currentTimeMillis() - t0;

            // --- FASE 2.1 (Opción B): elegir la mejor alternativa ---
            // Si hay alternativas (nbest > 1), extractTextBest elige la de
            // mayor confianza. Si no, delega en extractText (comportamiento
            // anterior).
            String text = extractTextBest(rawJson);
            boolean hasContent = text != null && !text.isBlank();

            // --- LOGGING SELECTIVO (reducción de ruido) ---
            // Solo se loguea el rawJson cuando realmente contiene texto o un
            // resultado parcial (es decir, cuando el usuario está hablando).
            // Los buffers sin contenido (silencio / sin reconocimiento) ya no
            // generan ruido en los logs.
            if (hasContent) {
                LOG.debug("[VOSK] {} | elapsed={}ms | rawJson={}", label, elapsedMs, rawJson);
                updateViseme(text);

                // --- FASE 2.2: parsear palabras con timestamps ---
                // Con setPartialWords(true) activo, el JSON de Vosk incluye
                // un array de palabras con start/end/conf. parseWords() extrae
                // esos datos para que la Fase 4 (VisemeTimeline) pueda
                // sincronizar visemes con el tiempo real de pronunciación.
                List<WordTiming> words = parseWords(rawJson);
                if (!words.isEmpty()) {
                    LOG.debug("[VOSK] {} | words={}", label, formatWords(words));
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
     * Extrae el <b>mejor</b> texto reconocible del JSON devuelto por Vosk,
     * eligiendo la alternativa de mayor confianza cuando hay múltiples.
     *
     * <p><b>Formatos soportados:</b>
     *
     * <ol>
     *   <li><b>Con alternativas</b> (nbest &gt; 1):
     *       <pre>{@code
     *       {
     *         "alternatives": [
     *           {"text": "hola mundo", "confidence": 0.95, "tokens": [...]},
     *           {"text": "ola mundo",  "confidence": 0.70, "tokens": [...]},
     *           ...
     *         ]
     *       }
     *       }</pre>
     *       Se elige la alternativa con el campo {@code "text"} o {@code "partial"}
     *       de mayor {@code confidence}.
     *   </li>
     *   <li><b>Final simple</b> (nbest=1):
     *       <pre>{@code {"text" : "hola mundo"}}</pre>
     *   </li>
     *   <li><b>Parcial simple</b> (nbest=1):
     *       <pre>{@code {"partial" : "hola mun"}}</pre>
     *   </li>
     *   <li><b>Texto plano</b> (defensivo): se devuelve tal cual.</li>
     * </ol>
     *
     * <p>Este método es <b>estático y testable sin modelo Vosk</b>: solo
     * opera sobre cadenas JSON.
     *
     * @param rawJson cadena JSON de Vosk (o texto plano), puede ser {@code null}
     * @return el texto de la mejor alternativa, o {@code null} si no se pudo extraer
     */
    static String extractTextBest(String rawJson) {
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

            // --- Formato con alternativas (nbest > 1) ---
            JsonNode alternatives = node.path("alternatives");
            if (alternatives.isArray() && alternatives.size() > 0) {
                return pickBestAlternative(alternatives);
            }

            // --- Formato simple (nbest=1) ---
            return extractText(rawJson);
        } catch (Exception e) {
            LOG.debug("extractTextBest: no se pudo parsear el JSON de Vosk: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Elige la alternativa de mayor confianza de un array JSON de
     * {@code "alternatives"}.
     *
     * <p>Cada alternativa puede tener campo {@code "text"} (resultado final)
     * o {@code "partial"} (resultado en curso), y un campo opcional
     * {@code "confidence"} (0.0 – 1.0).
     *
     * <p>Si ninguna alternativa tiene {@code confidence}, se elige la primera
     * (Vosk ya las ordena de mejor a peor).
     *
     * @param alternatives array JSON de alternativas
     * @return el texto de la mejor alternativa, o {@code null} si no hay texto útil
     */
    private static String pickBestAlternative(JsonNode alternatives) {
        String bestWithConfidence = null;
        double bestConfidence = -1.0;
        String firstWithoutConfidence = null;

        for (JsonNode alt : alternatives) {
            // Extraer texto: "text" (final) o "partial" (parcial)
            String text = null;
            JsonNode textNode = alt.path("text");
            if (textNode.isTextual() && !textNode.asText().isBlank()) {
                text = textNode.asText();
            } else {
                JsonNode partialNode = alt.path("partial");
                if (partialNode.isTextual() && !partialNode.asText().isBlank()) {
                    text = partialNode.asText();
                }
            }
            if (text == null) {
                continue;
            }

            // Leer confianza (opcional)
            JsonNode confNode = alt.path("confidence");
            if (confNode.isNumber()) {
                double conf = confNode.asDouble();
                if (conf > bestConfidence) {
                    bestConfidence = conf;
                    bestWithConfidence = text;
                }
            } else if (firstWithoutConfidence == null) {
                // Fallback: primera alternativa sin confianza
                firstWithoutConfidence = text;
            }
        }

        // Priorizar la de mayor confianza; si ninguna la tiene, usar la primera
        return bestWithConfidence != null ? bestWithConfidence : firstWithoutConfidence;
    }

    // ------------------------------------------------------------------
    // FASE 2.2 – parseWords: extraer palabras con timestamps del JSON
    // ------------------------------------------------------------------

    /**
     * Extrae la <b>lista de palabras con timestamps</b> del JSON extendido
     * que devuelve Vosk cuando {@code setPartialWords(true)} está activo.
     *
     * <p><b>Formato de entrada</b> (resultado parcial):
     * <pre>
     * {
     *   "partial": "hola mun",
     *   "result": [
     *     {"word": "hola", "start": 0.12, "end": 0.45, "conf": 0.92},
     *     {"word": "mun",  "start": 0.61, "end": 0.78, "conf": 0.71}
     *   ]
     * }
     * </pre>
     *
     * <p><b>Formato de entrada</b> (resultado final):
     * <pre>
     * {
     *   "text": "hola mundo",
     *   "result": [
     *     {"word": "hola", "start": 0.12, "end": 0.45, "conf": 0.92},
     *     {"word": "mundo","start": 0.61, "end": 0.95, "conf": 0.88}
     *   ]
     * }
     * </pre>
     *
     * <p><b>Formato con alternativas</b> (nbest &gt; 1): el array de palabras
     * puede estar dentro de cada alternativa:
     * <pre>
     * {
     *   "alternatives": [
     *     {"text": "hola mundo", "confidence": 0.95,
     *      "tokens": [{"word":"hola","start":0.1,"end":0.4,"conf":0.92}, ...]}
     *   ]
     * }
     * </pre>
     * En ese caso se elige la alternativa de mayor confianza y se parsean
     * sus tokens.
     *
     * <p><b>Comportamiento:</b>
     * <ul>
     *   <li>Si el JSON no tiene palabras (campo ausente, vacío, o no es JSON),
     *       devuelve una <b>lista vacía</b> (no {@code null}).</li>
     *   <li>Es tolerante a fallos: cualquier excepción se loguea y se devuelve
     *       una lista vacía.</li>
     *   <li>El campo de palabras puede llamarse {@code "result"} (formato
     *       estándar de Vosk) o {@code "tokens"} (formato alternativo).
     *       Se intenta ambos.</li>
     * </ul>
     *
     * <p>Este método es <b>estático y testable sin modelo Vosk</b>: solo
     * opera sobre cadenas JSON.
     *
     * @param rawJson cadena JSON de Vosk (parcial o final), puede ser {@code null}
     * @return lista de {@link WordTiming} en orden cronológico, vacía si no hay palabras
     */
    static List<WordTiming> parseWords(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Collections.emptyList();
        }
        String trimmed = rawJson.trim();

        // Caso defensivo: no es JSON → no hay palabras.
        if (!trimmed.startsWith("{")) {
            return Collections.emptyList();
        }

        try {
            JsonNode node = JSON.readTree(trimmed);

            // --- Formato con alternativas (nbest > 1) ---
            JsonNode alternatives = node.path("alternatives");
            if (alternatives.isArray() && alternatives.size() > 0) {
                return parseWordsFromAlternatives(alternatives);
            }

            // --- Formato estándar: "result" o "tokens" en el raíz ---
            JsonNode wordsArray = node.path("result");
            if (!wordsArray.isArray() || wordsArray.size() == 0) {
                // Intentar campo alternativo "tokens"
                wordsArray = node.path("tokens");
            }
            if (!wordsArray.isArray() || wordsArray.size() == 0) {
                return Collections.emptyList();
            }

            return parseWordsArray(wordsArray);
        } catch (Exception e) {
            LOG.debug("parseWords: no se pudo parsear el JSON de Vosk: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Extrae palabras con timestamps de un array JSON de alternativas.
     * Elige la alternativa de mayor confianza y parsea sus tokens.
     *
     * @param alternatives array JSON de alternativas
     * @return lista de {@link WordTiming}, vacía si no se encontraron
     */
    private static List<WordTiming> parseWordsFromAlternatives(JsonNode alternatives) {
        JsonNode bestAlt = null;
        double bestConfidence = -1.0;

        for (JsonNode alt : alternatives) {
            JsonNode confNode = alt.path("confidence");
            double conf = confNode.isNumber() ? confNode.asDouble() : 0.0;
            if (conf > bestConfidence) {
                bestConfidence = conf;
                bestAlt = alt;
            }
        }

        if (bestAlt == null) {
            return Collections.emptyList();
        }

        // Buscar tokens: "result" (estándar) o "tokens" (alternativo)
        JsonNode wordsArray = bestAlt.path("result");
        if (!wordsArray.isArray() || wordsArray.size() == 0) {
            wordsArray = bestAlt.path("tokens");
        }
        if (!wordsArray.isArray() || wordsArray.size() == 0) {
            return Collections.emptyList();
        }

        return parseWordsArray(wordsArray);
    }

    /**
     * Parsea un array JSON de palabras en una lista de {@link WordTiming}.
     *
     * @param wordsArray array JSON donde cada elemento tiene
     *                   {@code word}, {@code start}, {@code end}, {@code conf}
     * @return lista de {@link WordTiming} en orden
     */
    private static List<WordTiming> parseWordsArray(JsonNode wordsArray) {
        List<WordTiming> result = new java.util.ArrayList<>();
        for (JsonNode w : wordsArray) {
            String word = w.path("word").asText("");
            if (word.isBlank()) {
                continue;
            }
            double start = w.path("start").asDouble(0.0);
            double end = w.path("end").asDouble(start);
            double conf = w.path("conf").asDouble(-1.0);
            result.add(new WordTiming(word, start, end, conf));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Viseme: mapeo texto → viseme
    // ------------------------------------------------------------------

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
        // --- FASE: viseme por sílaba dominante ---
        // En lugar de tomar la primera letra de la palabra (Viseme.fromWord),
        // se usa la sílaba dominante calculada por
        // {@link #transformarTextoASilabasDominantes(String)} para obtener el
        // viseme: así el avatar muestra la forma de boca de la sílaba tónica
        // de la palabra, que es la que más se "percibe" al escuchar.
        String silabaDominante = transformarTextoASilabasDominantes(lastWord);
        Viseme v = obtenerVisemeDeSilabaDominante(silabaDominante);
        this.current = v;
        // --- LOGGING DETALLADO (Fase 1.1 del PLAN) ---
        // Loguear la última palabra, la sílaba dominante y el viseme
        // resultante para ver el mapeo sílaba→viseme en cada buffer.
        LOG.debug("[VOSK] text='{}' | lastWord='{}' | silabaDominante='{}' | viseme={} | lastQueued={}",
                text, lastWord, silabaDominante, v, lastQueued);
        // Encola el viseme para que la UI lo consuma en orden (tick). Evitamos
        // repetir el mismo viseme en seguida para no saturar la cola.
        if (v != lastQueued) {
            lastQueued = v;
            visemeQueue.offer(v);
            LOG.debug("[VOSK] → encolado viseme {} en la cola", v);
        } else {
            LOG.debug("[VOSK] → viseme {} ya era el último (no se encola de nuevo)", v);
        }
    }

    /**
     * Obtiene el viseme de la <b>sílaba dominante</b> de una palabra.
     *
     * <p>La sílaba dominante (tónica) es la que más se percibe al escuchar la
     * palabra, por lo que su forma de boca es la más representativa. Se toma
     * la <b>primera letra</b> de la sílaba (el "ataque") y se mapea con
     * {@link Viseme#fromLetter(char)}; si la sílaba empieza por consonante
     * del grupo S, el resultado será el viseme de ese grupo, y así
     * sucesivamente.
     *
     * <p><b>Ejemplos:</b>
     * <ul>
     *   <li>{@code "hola"} → sílaba dominante {@code "la"} → viseme {@link Viseme#L}</li>
     *   <li>{@code "programación"} → sílaba dominante {@code "ción"} (o la tónica
     *       según acentuación) → viseme de la primera letra mapeable.</li>
     *   <li>{@code "mundo"} → sílaba dominante {@code "mun"} → viseme {@link Viseme#BMP}</li>
     * </ul>
     *
     * <p>Si la sílaba no contiene ninguna letra mapeable, devuelve
     * {@link Viseme#NEUTRAL}.
     *
     * @param silabaDominante la sílaba dominante (resultado de
     *                        {@link #transformarTextoASilabasDominantes(String)})
     * @return viseme correspondiente, o {@link Viseme#NEUTRAL} si no hay letras
     */
    static Viseme obtenerVisemeDeSilabaDominante(String silabaDominante) {
        if (silabaDominante == null) {
            return Viseme.NEUTRAL;
        }
        for (int i = 0; i < silabaDominante.length(); i++) {
            char c = silabaDominante.charAt(i);
            if (Character.isLetter(c)) {
                return Viseme.fromLetter(c);
            }
        }
        return Viseme.NEUTRAL;
    }

    /**
     * Obtiene una <b>letra aleatoria</b> del resultado de
     * {@link #transformarTextoASilabasDominantes(String)} y la mapea a un
     * viseme existente.
     *
     * <p><b>Uso:</b> cuando la sílaba dominante produce un viseme poco
     * expresivo (p. ej. una consonante del grupo S que agrupa muchas letras),
     * o para añadir "variación" al habla, esta función elige una letra al
     * azar entre todas las letras presentes en la sílaba dominante y devuelve
     * su viseme.
     *
     * <p><b>Comportamiento:</b>
     * <ul>
     *   <li>Si el texto no contiene ninguna letra, devuelve
     *       {@link Viseme#NEUTRAL}.</li>
     *   <li>Si todas las letras mapean al mismo viseme (p. ej. sílaba
     *       "la" → solo 'l' y 'a'), la elección aleatoria entre ellas puede
     *       producir visemes distintos (L o A), dando variación.</li>
     *   <li>Es <b>determinística en tests</b> si se inyecta una
     *       {@link java.util.Random} (ver sobrecarga).</li>
     * </ul>
     *
     * @param texto la palabra o frase sobre la que se calcula la sílaba
     *             dominante (se pasa a {@link #transformarTextoASilabasDominantes(String)})
     * @return viseme de la letra aleatoria, o {@link Viseme#NEUTRAL}
     */
    public static Viseme obtenerVisemeLetraAleatoria(String texto) {
        return obtenerVisemeLetraAleatoria(texto, new java.util.Random());
    }

    /**
     * Sobrecarga de {@link #obtenerVisemeLetraAleatoria(String)} que permite
     * inyectar una {@link java.util.Random} para tests determinísticos.
     *
     * @param texto  la palabra o frase (se pasa a
     *               {@link #transformarTextoASilabasDominantes(String)})
     * @param random generador aleatorio a usar para elegir la letra
     * @return viseme de la letra aleatoria, o {@link Viseme#NEUTRAL}
     */
    public static Viseme obtenerVisemeLetraAleatoria(String texto, java.util.Random random) {
        if (texto == null || texto.isBlank() || random == null) {
            return Viseme.NEUTRAL;
        }
        String silabaDominante = transformarTextoASilabasDominantes(texto);
        // Recopilar todas las letras presentes en la sílaba dominante.
        List<Character> letras = new ArrayList<>();
        for (int i = 0; i < silabaDominante.length(); i++) {
            char c = silabaDominante.charAt(i);
            if (Character.isLetter(c)) {
                letras.add(c);
            }
        }
        if (letras.isEmpty()) {
            return Viseme.NEUTRAL;
        }
        char elegida = letras.get(random.nextInt(letras.size()));
        Viseme v = Viseme.fromLetter(elegida);
        LOG.debug("[VOSK] letraAleatoria: texto='{}' → silaba='{}' → letra='{}' → viseme={}",
                texto, silabaDominante, elegida, v);
        return v;
    }

    @Override
    public Viseme currentViseme() {
        return current;
    }

    /**
     * Reinicia el estado del reconocedor de Vosk y la cola de visemes
     * internos. Útil para tests de reconocimiento donde se quiere que cada
     * frase se reconozca de forma aislada, sin que el contexto de la frase
     * anterior influya en la siguiente.
     *
     * <p><b>Nota:</b> cualquier texto <i>parcial</i> acumulado hasta este
     * momento se descarta (Vosk no expone el partial al resetear). Los
     * resultados <i>previamente emitidos como finales</i> ya fueron
     * registrados en los logs, así que no se pierde información útil.
     */
    public void resetState() {
        try {
            recognizer.reset();
        } catch (Exception e) {
            LOG.debug("resetState: recognizer.reset() falló: {}", e.getMessage());
        }
        visemeQueue.clear();
        lastQueued = Viseme.NEUTRAL;
        current = Viseme.NEUTRAL;
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

    /**
     * Formatea una lista de {@link WordTiming} para logging legible.
     * Ejemplo: {@code [hola(0.12→0.45, 0.92), mun(0.61→0.78, 0.71)]}
     *
     * @param words lista de palabras con timing
     * @return cadena legible
     */
    static String formatWords(List<WordTiming> words) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < words.size(); i++) {
            WordTiming w = words.get(i);
            if (i > 0) sb.append(", ");
            sb.append(w.word())
              .append('(')
              .append(String.format("%.2f", w.start()))
              .append("→")
              .append(String.format("%.2f", w.end()))
              .append(", ");
            if (w.conf() >= 0) {
                sb.append(String.format("%.2f", w.conf()));
            } else {
                sb.append("?");
            }
            sb.append(')');
        }
        sb.append(']');
        return sb.toString();
    }


    private static final String VOCALES_FUERTES = "aeoAEOáéóÁÉÓ";
    private static final String VOCALES_DEBILES_CON_TILDE = "íúÍÚ";
    private static final String VOCALES_TODAS = "aeiouAEIOUáéíóúÁÉÍÓÚ";
    private static final String VOCALES_CON_TILDE = "áéíóúÁÉÍÓÚ";

    public static String transformarTextoASilabasDominantes(String texto) {
        if (texto == null || texto.isEmpty()) {
            return "";
        }

        // Expresión regular para identificar palabras individuales (incluyendo letras con acentos y ñ)
        Pattern pattern = Pattern.compile("[a-zA-ZáéíóúÁÉÍÓÚñÑüÜ]+");
        Matcher matcher = pattern.matcher(texto);

        StringBuilder resultado = new StringBuilder();
        int ultimoIndiceCopiado = 0;

        while (matcher.find()) {
            // Copiar los elementos intermedios (espacios, comas, puntos) tal como estaban
            resultado.append(texto, ultimoIndiceCopiado, matcher.start());

            // Extraer la palabra encontrada y calcular su sílaba dominante
            String palabra = matcher.group();
            String silabaDominante = obtenerSilabaDominanteDePalabra(palabra);
            resultado.append(silabaDominante);

            ultimoIndiceCopiado = matcher.end();
        }

        // Copiar cualquier residuo final del texto original (como un punto final)
        if (ultimoIndiceCopiado < texto.length()) {
            resultado.append(texto.substring(ultimoIndiceCopiado));
        }

        return resultado.toString();
    }

    private static String obtenerSilabaDominanteDePalabra(String palabra) {
        List<String> silabas = dividirEnSilabas(palabra);
        int totalSilabas = silabas.size();

        // Regla 1: Buscar tilde explícita
        for (String silaba : silabas) {
            for (char c : silaba.toCharArray()) {
                if (VOCALES_CON_TILDE.indexOf(c) != -1 || VOCALES_DEBILES_CON_TILDE.indexOf(c) != -1) {
                    return silaba;
                }
            }
        }

        // Regla 2: Acentuación natural
        char ultimaLetra = Character.toLowerCase(palabra.charAt(palabra.length() - 1));
        int indiceDominante;

        if ("aeiouns".indexOf(ultimaLetra) != -1) {
            indiceDominante = Math.max(0, totalSilabas - 2); // Penúltima
        } else {
            indiceDominante = totalSilabas - 1; // Última
        }

        return silabas.get(indiceDominante);
    }

    private static List<String> dividirEnSilabas(String palabra) {
        List<String> silabas = new ArrayList<>();
        StringBuilder silabaActual = new StringBuilder();
        int i = 0;
        int n = palabra.length();

        while (i < n) {
            char c = palabra.charAt(i);
            silabaActual.append(c);

            if (i < n - 1) {
                char siguiente = palabra.charAt(i + 1);

                // Hiatos
                if ((VOCALES_FUERTES.indexOf(c) != -1 && VOCALES_FUERTES.indexOf(siguiente) != -1) ||
                        (VOCALES_FUERTES.indexOf(c) != -1 && VOCALES_DEBILES_CON_TILDE.indexOf(siguiente) != -1) ||
                        (VOCALES_DEBILES_CON_TILDE.indexOf(c) != -1 && VOCALES_FUERTES.indexOf(siguiente) != -1)) {
                    silabas.add(silabaActual.toString());
                    silabaActual = new StringBuilder();
                }
                // Consonante intervocálica estándar
                else if (VOCALES_TODAS.indexOf(c) != -1 && VOCALES_TODAS.indexOf(siguiente) == -1) {
                    if (i < n - 2) {
                        char despuesDeSiguiente = palabra.charAt(i + 2);
                        if (VOCALES_TODAS.indexOf(despuesDeSiguiente) != -1) {
                            if (!esGrupoInseparable(siguiente, despuesDeSiguiente)) {
                                silabas.add(silabaActual.toString());
                                silabaActual = new StringBuilder();
                            }
                        }
                    } else {
                        silabas.add(silabaActual.toString());
                        silabaActual = new StringBuilder();
                    }
                }
            }
            i++;
        }

        if (silabaActual.length() > 0) {
            if (silabas.isEmpty()) {
                silabas.add(silabaActual.toString());
            } else {
                int ultimo = silabas.size() - 1;
                silabas.set(ultimo, silabas.get(ultimo) + silabaActual.toString());
            }
        }
        return silabas;
    }

    private static boolean esGrupoInseparable(char c1, char c2) {
        String grupo = ("" + c1 + c2).toLowerCase();
        if (grupo.equals("ch") || grupo.equals("ll") || grupo.equals("rr")) return true;
        return "bcdfghjptv".indexOf(Character.toLowerCase(c1)) != -1 && "lr".indexOf(Character.toLowerCase(c2)) != -1;
    }

}
