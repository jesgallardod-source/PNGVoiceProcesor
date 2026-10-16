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

    /**
     * Alfa del suavizado exponencial (0..1) del volumen.
     *
     * <p>0 = totalmente reactivo (el volumen/grito sigue el pico inmediato),
     * 1 = totalmente inerte. El default baja de 0.5 a <b>0.3</b> para que la
     * barra de volumen y el disparo del grito respondan más rápido sin
     * "parpadeo" excesivo. Afecta solo al eje de volumen, no a los visemes
     * (que salen de Vosk) ni a ningún parámetro del reconocedor.
     */
    private double smoothing = 0.3;

    /**
     * Duración mínima (ms) que se muestra <b>cada sílaba</b> (viseme) en la
     * fila de reproducción antes de avanzar a la siguiente.
     *
     * <p><b>Es la principal fuente de latencia artificial</b> del pipeline:
     * cada sílaba reconocida se "congela" este tiempo antes de mostrar la
     * siguiente, así una palabra de N sílabas tarda N × este valor en
     * animarse <i>después</i> de haber sido dicha. Cuanto más bajo, más
     * fluida y reactiva se ve la boca.
     *
     * <p>El default baja de 80 a <b>40 ms</b> (~25 sílabas/s), muy por debajo
     * del ritmo del habla natural (~150 ms/sílaba), para que la secuencia de
     * sílabas de una palabra se reproduzca casi al instante de reconocerse.
     * A 60 fps un frame son ~16,7 ms, así que 40 ms ≈ 2 frames por sílaba:
     * el cambio de imagen sigue siendo perceptible (no se "comprime" a 1 frame)
     * pero la boca avanza mucho más rápido que con 50 ms.
     * Las sílabas repetidas (mismo viseme) siguen sumando duración, de modo
     * que se conserva la sensación de sonido sostenido.
     *
     * <p><b>No es un parámetro de Vosk</b> (no afecta al reconocedor, que
     * emite sílabas en cuanto las detecta); es solo el ritmo de la animación
     * de la UI. Si prefieres una boca menos veloz, sube el valor en
     * {@code app.json} (clave {@code syllableMinMs}).
     */
    private long syllableMinMs = 40L;

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
     * continuidad, menos saltos a neutro). El default 120 ms cubre las pausas
     * típicas entre sílabas/palabras (~80–160 ms) sin "congelar" el avatar en
     * NEUTRAL, y deja que las pausas largas cierren la boca.
     *
     * <p>El default baja de 200 a <b>120 ms</b>: sigue cubriendo las pausas
     * típicas entre sílabas (~80–160 ms) para no ver "parpadeos" a boca
     * cerrada, pero reduce el tiempo que la última sílaba se mantiene antes
     * de volver a NEUTRAL, haciendo que la boca se cierre más rápido al
     * terminar de hablar.
     *
     * <p>Para afinarlo sin recompilar, cámbialo en {@code app.json}
     * (clave {@code silenceHoldMs}).
     */
    private long silenceHoldMs = 120L;

    /**
     * Muestra por buffer de captura (16-bit).
     *
     * <p>Menos frames = menos latencia de audio (el hilo de captura entrega
     * el buffer a Vosk antes de que se llene) = animación más reactiva.
     * A 16 kHz: 512 frames = 32 ms, 256 = 16 ms, 128 = 8 ms.
     * El default baja a <b>128</b> (~8 ms de latencia de captura) para que el
     * buffer llegue al reconocedor lo antes posible. <b>No afecta al
     * reconocimiento de Vosk</b>: el reconocedor acepta chunks de cualquier
     * tamaño (los va acumulando internamente hasta detectar fonemas); el
     * único parámetro fijo de Vosk es la frecuencia (16000 Hz), que se
     * mantiene. Chunks más pequeños también aligeran el hilo de audio
     * (menos CPU por buffer) dejando margen para que el pipeline responda.
     */
    private int bufferFrames = 128;
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
