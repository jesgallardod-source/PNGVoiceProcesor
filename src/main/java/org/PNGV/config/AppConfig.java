package org.PNGV.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuración de la aplicación. Se serializa a JSON con Jackson.
 *
 * <p>Campos:
 * <ul>
 *   <li>Umbrales de volumen (en dBFS) para clasificar el nivel 0-3.</li>
 *   <li>Sensibilidad / suavizado del volumen.</li>
 *   <li>Ruta del modelo de Vosk y del avatar activo.</li>
 *   <li>Parámetros de captura de audio.</li>
 * </ul>
 */
public class AppConfig {

    /** dBFS por debajo del cual se considera silencio (nivel 0). */
    private double thresholdLow = -50.0;
    /** dBFS por encima del cual se pasa a nivel 1 (habla suave). */
    private double thresholdMid = -40.0;
    /** dBFS por encima del cual se pasa a nivel 2 (medio). */
    private double thresholdHigh = -28.0;

    /**
     * Umbral de volumen <b>normalizado (0..1)</b> a partir del cual el avatar
     * muestra la imagen de <b>grito</b>. Se elige un valor alto (0.96) para
     * que el grito solo aparezca cuando el micrófono llega casi al <b>tope</b>
     * de decibeles, y <b>NO</b> al hablar normal (que suele rondar 0.3..0.6).
     * Ver {@link org.PNGV.audio.VolumeAnalyzer#getNormalizedLevel()}.
     */
    private double screamThreshold = 0.96;

    /** Alfa del suavizado exponencial (0..1). 0.5 = muy reactivo, 0.9 = muy suave. */
    private double smoothing = 0.5;

    /**
     * Muestra por buffer de captura (16-bit).
     * <p>256 = ~16 ms de latencia a 16 kHz (reducción de latencia, Fase 2.4 del
     * Plan de Mejora Vosk). Valores menores = partial results más frecuentes =
     * el reconocedor reacciona antes y mejor granularidad temporal.
     *
     * <p>Es un compromiso: buffer más pequeño = más buffers/segundo (un poco más
     * de CPU en el hilo de audio) a cambio de latencia menor. 256 es el
     * mínimo razonable sin saturar la CPU.
     */
    private int bufferFrames = 256;
    /**
     * Frecuencia de muestreo. <b>Debe coincidir con la frecuencia con la que
     * está entrenado el modelo de Vosk</b> (ver {@code conf/mfcc.conf} del
     * modelo). Para el modelo "vosk-model-small-es-0.42" la frecuencia es
     * <b>16000 Hz</b>. Usar otra frecuencia (p. ej. 44100 Hz) hace que Vosk
     * reciba la señal acelerada y produzca texto aleatorio / fin de frase
     * inmediato.
     */
    private int sampleRate = 16000;

    /** Ruta al modelo de Vosk (p. ej. assets/models/vosk-model-small-es-0.42). */
    private String modelPath = "assets/models/vosk-model-small-es-0.42";

    /**
     * Número de alternativas (n-best) que pide a Vosk por cada resultado.
     *
     * <p>Con nbest &gt; 1, Vosk devuelve un JSON con campo
     * {@code "alternatives": [...]} que contiene varias hipótesis ordenadas
     * por confianza. El reconocedor elige la de mayor confianza para mejorar
     * la coherencia del texto reconocido.
     *
     * <p><b>Latencia (Fase "reducción de latencia"):</b> el coste de
     * reconocimiento de Vosk crece <b>linealmente</b> con nbest: con nbest=5
     * se calculan 5 hipótesis por buffer en vez de 1, que es el mayor gasto
     * de CPU del pipeline y la principal causa de que los resultados
     * "se tarden" en aparecer en los logs. Como el uso actual (viseme por la
     * última palabra de la hipótesis ganadora) no aprovecha las alternativas
     * extra, el default se baja a <b>1</b> para minimizar la latencia.
     *
     * <p>Si se necesita mayor coherencia de texto (p. ej. para transcripción),
     * se puede subir a 3-5 aceptando más latencia por buffer.
     *
     * <p>Valor por defecto: <b>1</b>. Mínimo permitido: 1.
     */
    private int nbest = 1;

    /** Ruta al avatar activo (hoja de sprites PNG). */
    private String activeAvatar = "assets/avatars/avatar1.png";

    /** Color de fondo de la ventana (hex #RRGGBB). Por defecto verde croma-key. */
    private String backgroundColor = "#00FF00";

    /**
     * Indica si el <b>agitamiento/vibración</b> del avatar mientras habla está
     * activado. {@code true} (por defecto) = el avatar tiembla suavemente al
     * hablar; {@code false} = el avatar permanece quieto.
     */
    private boolean vibrationEnabled = true;

    /**
     * Nombre del micrófono seleccionado (el nombre que muestra
     * {@link javax.sound.sampled.Mixer.Info#getName()}).
     * <p>{@code null} o cadena vacía = usar el micrófono por defecto del sistema.
     */
    private String selectedMic;

    /**
     * Imágenes propias del usuario, una por <b>viseme/letra</b>.
     *
     * <p>Clave = nombre del viseme (p. ej. "A", "E", "NEUTRAL") y valor =
     * ruta del archivo PNG que se muestra cuando el avatar debe representar
     * esa letra. Si una letra no tiene imagen asignada, se usa el placeholder
     * dibujado con formas.
     */
    private Map<String, String> visemeImages = new LinkedHashMap<>();

    public double getThresholdLow() { return thresholdLow; }
    public void setThresholdLow(double thresholdLow) { this.thresholdLow = thresholdLow; }

    public double getThresholdMid() { return thresholdMid; }
    public void setThresholdMid(double thresholdMid) { this.thresholdMid = thresholdMid; }

    public double getThresholdHigh() { return thresholdHigh; }
    public void setThresholdHigh(double thresholdHigh) { this.thresholdHigh = thresholdHigh; }

    public double getScreamThreshold() { return screamThreshold; }
    public void setScreamThreshold(double screamThreshold) { this.screamThreshold = screamThreshold; }

    public double getSmoothing() { return smoothing; }
    public void setSmoothing(double smoothing) { this.smoothing = smoothing; }

    public int getBufferFrames() { return bufferFrames; }
    public void setBufferFrames(int bufferFrames) { this.bufferFrames = bufferFrames; }

    public int getSampleRate() { return sampleRate; }
    public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }

    public String getModelPath() { return modelPath; }
    public void setModelPath(String modelPath) { this.modelPath = modelPath; }

    public int getNbest() { return nbest; }
    public void setNbest(int nbest) { this.nbest = Math.max(1, nbest); }

    public String getActiveAvatar() { return activeAvatar; }
    public void setActiveAvatar(String activeAvatar) { this.activeAvatar = activeAvatar; }

    public String getBackgroundColor() { return backgroundColor; }
    public void setBackgroundColor(String backgroundColor) { this.backgroundColor = backgroundColor; }

    public boolean isVibrationEnabled() { return vibrationEnabled; }
    public void setVibrationEnabled(boolean vibrationEnabled) { this.vibrationEnabled = vibrationEnabled; }

    public String getSelectedMic() { return selectedMic; }
    public void setSelectedMic(String selectedMic) { this.selectedMic = selectedMic; }

    public Map<String, String> getVisemeImages() {
        return visemeImages;
    }

    public void setVisemeImages(Map<String, String> visemeImages) {
        this.visemeImages = visemeImages == null ? new LinkedHashMap<>() : visemeImages;
    }
}
