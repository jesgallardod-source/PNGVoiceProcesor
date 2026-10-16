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
     * Duración mínima (ms) que se muestra <b>cada sílaba</b> (viseme) en la
     * fila de reproducción antes de avanzar a la siguiente.
     *
     * <p>Cuanto más bajo, más rápida y fluida se ve la boca (más sílabas/s).
     * El default 80 ms (~12 sílabas/s) es más rápido que el habla natural
     * (~150 ms) para que el cambio de imagen se sienta continuo y cercano a
     * una persona pronunciando, sin "pulsos" entre sílaba y sílaba.
     *
     * <p>Para afinarlo sin recompilar, cámbialo en {@code app.json}
     * (clave {@code syllableMinMs}).
     */
    private long syllableMinMs = 80L;

    /**
     * Duración (ms) que se mantiene la <b>última</b> sílaba hablada antes de
     * volver a NEUTRAL al llegar el silencio entre palabras.
     *
     * <p>Siempre que la siguiente sílaba llegue <b>dentro</b> de este tiempo,
     * el avatar <b>NO</b> vuelve a NEUTRAL: conserva la última boca hasta que
     * llegue la siguiente. Así las sílabas cercanas (y las palabras
     * consecutivas pronunciadas sin pausa) se ven <b>continuas</b>, sin el
     * "parpadeo" a boca cerrada. Solo cuando pasa más de este tiempo sin nueva
     * sílaba (pausa real) el avatar vuelve a NEUTRAL.
     *
     * <p>Cuanto más alto, más tiempo se mantiene la última sílaba (más
     * continuidad, menos saltos a neutro). El default 200 ms cubre las pausas
     * típicas entre sílabas/palabras (~80–160 ms) sin "congelar" el avatar en
     * NEUTRAL, y deja que las pausas largas (>200 ms) cierren la boca.
     *
     * <p>Para afinarlo sin recompilar, cámbialo en {@code app.json}
     * (clave {@code silenceHoldMs}).
     */
    private long silenceHoldMs = 200L;

    /** Muestra por buffer de captura (16-bit). 1024 es un buen valor. */
    private int bufferFrames = 1024;
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

    public long getSyllableMinMs() { return syllableMinMs; }
    public void setSyllableMinMs(long syllableMinMs) { this.syllableMinMs = syllableMinMs; }

    public long getSilenceHoldMs() { return silenceHoldMs; }
    public void setSilenceHoldMs(long silenceHoldMs) { this.silenceHoldMs = silenceHoldMs; }

    public int getBufferFrames() { return bufferFrames; }
    public void setBufferFrames(int bufferFrames) { this.bufferFrames = bufferFrames; }

    public int getSampleRate() { return sampleRate; }
    public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }

    public String getModelPath() { return modelPath; }
    public void setModelPath(String modelPath) { this.modelPath = modelPath; }

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
