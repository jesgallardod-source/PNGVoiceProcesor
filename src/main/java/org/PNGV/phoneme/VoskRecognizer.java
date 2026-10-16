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
     * Último texto logueado. Sirve para evitar repetir el log cuando
     * {@code getPartialResult()} devuelve el mismo texto buffer tras buffer.
     */
    private volatile String lastLoggedText = "";

    /**
     * Última palabra ya procesada desde resultados parciales. Sirve para
     * detectar cuándo aparece una <b>nueva palabra</b> al final del texto
     * parcial y encolar sus sílabas en tiempo real (sin esperar al FINAL).
     */
    private volatile String lastProcessedPartialWord = "";

    /**
     * Última palabra reconocida (ya sea por parcial o por final). Se usa
     * únicamente para <b>logging</b>: registrar cada vez que el reconocedor
     * detecta una palabra nueva, de modo que sea fácil diagnosticar cuándo la
     * imagen de pronunciación NO se está actualizando.
     *
     * <p><b>Diagnóstico de "la imagen no se actualiza":</b> si en el log no
     * aparecen líneas {@code [VOSK] WORD ...}, entonces el reconocedor NO está
     * detectando nuevas palabras (Vosk no produce texto, o el texto parcial no
     * cambia). Si aparecen {@code WORD} pero la imagen sigue sin cambiar, el
     * problema está en el consumo de la cola en la UI (buscar
     * {@code [UI] IMG ...}).
     */
    private volatile String lastRecognizedWord = "";

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
                String rawJson = recognizer.getResult();
                String text = extractText(rawJson);
                if (text != null && !text.isBlank()) {
                    // IMPORTANTE (fluidez): calcular la última palabra y detectar
                    // si YA se animó vía resultado PARCIAL ANTES de resetear los
                    // guards. Si la palabra final coincide con la última palabra
                    // parcial ya encolada, la boca ya la mostró; re-encolarla aquí
                    // provocaba un "tartamudeo" visible (la última sílaba se
                    // repite ~1 s después del FINAL). En ese caso se omite.
                    String finalWord = lastWord(text);
                    boolean alreadyAnimated = !finalWord.isEmpty()
                            && finalWord.equals(lastProcessedPartialWord);
                    if (!text.equals(lastLoggedText)) {
                        LOG.info("[Vosk {}] FINAL   text='{}'", System.currentTimeMillis(), text);
                    }
                    // Reinicia el guard para que la siguiente frase (aunque repita
                    // la misma palabra) se loguee de nuevo.
                    lastLoggedText = "";
                    // Reinicia el guard de palabras parciales para que la
                    // siguiente frase se procese desde cero.
                    lastProcessedPartialWord = "";
                    logNewWord(finalWord, "FINAL");
                    if (alreadyAnimated) {
                        LOG.debug("[Vosk {}] FINAL '{}' ya animado por parcial; omito re-encolado",
                                System.currentTimeMillis(), finalWord);
                    } else {
                        // Mapea la última palabra en sílabas y encola un viseme por sílaba.
                        updateVisemesFromSyllables(text);
                    }
                }
            } else {
                // Resultado parcial: procesa en tiempo real cada nueva palabra
                // detectada para que la imagen se actualice sin esperar al FINAL.
                String rawJson = recognizer.getPartialResult();
                String partial = extractText(rawJson);
                if (partial != null && !partial.isBlank()) {
                    if (!partial.equals(lastLoggedText)) {
                        lastLoggedText = partial;
                        LOG.info("[Vosk {}] PARTIAL text='{}'", System.currentTimeMillis(), partial);
                    }
                    // Detecta si aparece una NUEVA palabra al final del texto
                    // parcial y encola sus sílabas inmediatamente.
                    String currentWord = lastWord(partial);
                    if (!currentWord.isEmpty() && !currentWord.equals(lastProcessedPartialWord)) {
                        lastProcessedPartialWord = currentWord;
                        logNewWord(currentWord, "PARTIAL");
                        updateVisemesFromSyllables(partial);
                    }
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

    /**
     * Toma la última palabra del texto, la divide en sílabas con
     * {@link #dividirEnSilabas(String)} y encola un viseme por cada sílaba
     * en orden de pronunciación.
     *
     * <p>Regla de viseme por sílaba:
     * <ul>
     *   <li>3 o más letras → la <b>letra central</b> (índice {@code len/2})</li>
     *   <li>2 letras        → la <b>última letra</b></li>
     *   <li>1 letra         → esa misma letra</li>
     * </ul>
     *
     * @param text texto completo reconocido (p. ej. "el programa")
     */
    private void updateVisemesFromSyllables(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String lastWord = lastWord(text);
        java.util.List<String> syllables = dividirEnSilabas(lastWord);
        if (syllables.isEmpty()) {
            return;
        }

        // Construye el log y encola cada viseme en orden.
        //
        // <p><b>Sin colapso de repetidos:</b> a diferencia de la versión
        // anterior (que usaba {@code lastQueued} para descartar visemes
        // consecutivos iguales), aquí se encola <b>CADA</b> sílaba, incluidas
        // las repetidas. La UI (ver {@code PngVoiceApp.tick}) muestra cada
        // viseme con una duración mínima, de modo que las sílabas repetidas
        // (p. ej. "ba-ja-ban" → A A A) se suman en tiempo y producen la
        // <b>ilusión de un sonido sostenido</b>.
        StringBuilder sb = new StringBuilder();
        Viseme lastViseme = Viseme.NEUTRAL;
        for (String syl : syllables) {
            Viseme v = visemeFromSyllable(syl);
            if (sb.length() > 0) sb.append(" ");
            sb.append("'").append(syl).append("'→").append(v);
            lastViseme = v;
            visemeQueue.offer(v);
        }

        this.current = lastViseme;
        LOG.info("[Vosk {}] SYLLABLES '{}' → [{}]", System.currentTimeMillis(), lastWord, sb);
    }

    /**
     * Registra en el log que se ha detectado una <b>nueva palabra</b> reconocida.
     *
     * <p>Sirve para diagnosticar por qué la imagen de pronunciación <b>no</b> se
     * actualiza: si no ves líneas {@code [VOSK] WORD} en el log, el reconocedor
     * no está produciendo texto nuevo (Vosk no reconoce nada o el texto parcial
     * no avanza); si las ves pero la imagen sigue quieta, el fallo está aguas
     * abajo (cola / UI).
     *
     * <p>Evita loguear dos veces la misma palabra consecutiva (p. ej. cuando
     * una palabra llega por PARTIAL y luego se confirma por FINAL).
     *
     * @param word   palabra reconocida (puede ser {@code null} o vacía)
     * @param source origen: {@code "PARTIAL"} (texto en curso) o {@code "FINAL"} (frase terminada)
     */
    private void logNewWord(String word, String source) {
        if (word == null || word.isBlank()) {
            return;
        }
        String w = word.trim();
        if (w.equals(lastRecognizedWord)) {
            return; // misma palabra que la anterior: no repetir
        }
        lastRecognizedWord = w;
        LOG.info("[VOSK] WORD {} → '{}' ({} ms)",
                source, w, System.currentTimeMillis());
    }

    /**
     * Devuelve el viseme correspondiente a una sílaba según la regla:
     * <ul>
     *   <li>1 letra        → esa letra</li>
     *   <li>2 letras       → la <b>última</b></li>
     *   <li>3+ letras      → la <b>central</b> (índice {@code len/2})</li>
     * </ul>
     *
     * @param syllable sílaba (p. ej. "pro", "ma", "ho")
     * @return viseme, o {@link Viseme#NEUTRAL} si no se puede determinar
     */
    static Viseme visemeFromSyllable(String syllable) {
        if (syllable == null || syllable.isBlank()) {
            return Viseme.NEUTRAL;
        }
        String s = syllable.toLowerCase();
        int len = s.length();
        if (len == 1) {
            return Viseme.fromLetter(s.charAt(0));
        }
        if (len == 2) {
            return Viseme.fromLetter(s.charAt(1));   // última letra
        }
        // 3+ letras: letra central
        return Viseme.fromLetter(s.charAt(len / 2));
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

    // USER CODE//////////////////////////////////////////////////////////////////////////////////////////////////////////

    public static ArrayList<String> dividirEnSilabas(String palabra) {
        ArrayList<String> silabas = new ArrayList<>();
        if (palabra == null || palabra.isEmpty()) return silabas;

        String p = palabra.toLowerCase();
        int i = 0;
        int n = p.length();
        int inicioSilaba = 0;

        while (i < n) {
            // Avanzar hasta encontrar una vocal (núcleo de la sílaba)
            if (esVocal(p.charAt(i))) {

                // Evaluar si la siguiente letra forma diptongo o hiato
                if (i + 1 < n && esVocal(p.charAt(i + 1))) {
                    if (esHiato(p.charAt(i), p.charAt(i + 1))) {
                        // Si es hiato, la sílaba se corta justo después de la primera vocal
                        silabas.add(palabra.substring(inicioSilaba, i + 1));
                        inicioSilaba = i + 1;
                        i++;
                        continue;
                    } else {
                        // Si es diptongo, permanecen juntas en la misma sílaba
                        i++;
                    }
                }

                // Determinar cuántas consonantes siguen después de la(s) vocal(es)
                int J = i + 1;
                while (J < n && !esVocal(p.charAt(J))) {
                    J++;
                }
                int numConsonantes = J - (i + 1);

                // Decidir dónde cortar según el número de consonantes intermedias
                int corte = J; // Por defecto corta justo antes de la siguiente vocal

                if (numConsonantes == 1) {
                    // VCV -> Corta antes de la consonante (V-CV)
                    corte = i + 1;
                } else if (numConsonantes == 2) {
                    // VCCV -> Evalúa si las consonantes forman un grupo inseparable (bl, cr, tr...)
                    if (esGrupoConsonanticoInseparable(p.substring(i + 1, i + 3))) {
                        corte = i + 1; // V-CCV
                    } else {
                        corte = i + 2; // VC-CV
                    }
                } else if (numConsonantes == 3) {
                    // VCCCV -> Generalmente las dos primeras se quedan y la tercera se va (VCC-CV)
                    // Excepto si las dos últimas son inseparables (VC-CCV)
                    if (esGrupoConsonanticoInseparable(p.substring(i + 2, i + 4))) {
                        corte = i + 2;
                    } else {
                        corte = i + 3;
                    }
                } else if (numConsonantes == 4) {
                    // VCCCCV -> Siempre se divide a la mitad (VCC-CCV) como en "abstracto"
                    corte = i + 3;
                }

                // Evitar añadir sílabas vacías o desbordamientos al final de la palabra
                if (J == n) {
                    silabas.add(palabra.substring(inicioSilaba, n));
                    break;
                } else {
                    silabas.add(palabra.substring(inicioSilaba, corte));
                    inicioSilaba = corte;
                    i = corte - 1;
                }
            }
            i++;
        }
        return silabas;
    }

    private static boolean esVocal(char c) {
        return "aeiouáéíóúü".indexOf(c) != -1;
    }

    private static boolean esVocalFuerte(char c) {
        return "aeoáéó".indexOf(c) != -1;
    }

    private static boolean esHiato(char v1, char v2) {
        // Dos fuertes siempre se separan (ca-os, po-e-ma)
        if (esVocalFuerte(v1) && esVocalFuerte(v2)) return true;
        // Una vocal débil con tilde unida a una fuerte se separa (tí-o, grú-a)
        if ("áéó".indexOf(v1) != -1 && "íú".indexOf(v2) != -1) return false; // Diptongo decreciente
        if ("íú".indexOf(v1) != -1 && esVocalFuerte(v2)) return true; // Hiato acentual (dí-a)
        if (esVocalFuerte(v1) && "íú".indexOf(v2) != -1) return true; // Hiato acentual (re-ú-ne)
        return false;
    }

    private static boolean esGrupoConsonanticoInseparable(String par) {
        if (par.length() < 2) return false;
        char c1 = par.charAt(0);
        char c2 = par.charAt(1);
        // Combinaciones de consonante + L o R que no se pueden separar (ej: bra, cla, pro)
        return (c2 == 'r' && "bcdfgpt".indexOf(c1) != -1) ||
                (c2 == 'l' && "bcfgpl".indexOf(c1) != -1) ||
                (c1 == 'c' && c2 == 'h') ||
                (c1 == 'l' && c2 == 'l') ||
                (c1 == 'r' && c2 == 'r');
    }

}
