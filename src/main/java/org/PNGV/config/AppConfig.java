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
     * <p>El default es <b>60 ms</b> (~16.7 sílabas/s), por debajo del ritmo del
     * habla natural (~150 ms/sílaba) pero lo bastante lento como para que cada
     * forma de boca se perciba de forma natural (no "a pulsos"). A 60 fps un
     * frame son ~16,7 ms, así que 60 ms ≈ 3.6 frames por sílaba: el cambio de
     * imagen es suave y cada viseme se ve claramente.
     * Las sílabas repetidas (mismo viseme) siguen sumando duración, de modo
     * que se conserva la sensación de sonido sostenido.
     *
     * <p><b>No es un parámetro de Vosk</b> (no afecta al reconocedor, que
     * emite sílabas en cuanto las detecta); es solo el ritmo de la animación
     * de la UI. Si prefieres una boca menos veloz, sube el valor en
     * {@code app.json} (clave {@code syllableMinMs}).
     */
    private long syllableMinMs = 60L;

    /**
     * Umbral de volumen <b>normalizado (0..1)</b> a partir del cual el avatar
     * se considera "abriendo la boca" (estado OPEN en la máquina de estados de
     * la boca, ver {@code PngVoiceApp.tick}).
     *
     * <p>El volumen ya es reactivo (buffer de ~8 ms + EMA con alfa 0.3), así que
     * usarlo como disparador de la boca hace que el avatar se vea "hablando"
     * <b>al instante</b> de empezar a hablar, sin esperar a que Vosk confirme
     * la primera palabra (que tarda ~200–400 ms).
     *
     * <p>El default 0.05 (~-44 dBFS con el EMA actual) es bastante por encima
     * del ruido de fondo pero muy por debajo del habla normal (~0.3..0.6), así
     * que se dispara con cualquier sonido de voz sin saltar por ruido ambiente.
     *
     * <p>Debe ser <b>mayor</b> que {@link #mouthCloseThreshold} (histéresis):
     * si no, la boca abriría/cerraría en cada microvariación del volumen.
     */
    private double mouthOpenThreshold = 0.05;

    /**
     * Umbral de volumen <b>normalizado (0..1)</b> por debajo del cual el avatar
     * se considera "cerrando la boca" (estado CLOSED).
     *
     * <p>Debe ser <b>menor</b> que {@link #mouthOpenThreshold}: la diferencia
     * entre ambos umbrales es la <b>histéresis</b> que evita que la boca
     * "parpadee" abriendo/cerrando en cada pico de volumen dentro de una sílaba.
     * Mientras el volumen esté entre {@code close} y {@code open}, la boca
     * mantiene su estado actual.
     *
     * <p>El default 0.02 (~-50 dBFS) es justo por encima del ruido típico de
     * fondo, así que el silencio real (sin voz) sí cierra la boca, pero las
     * micropausas dentro de una palabra no.
     */
    private double mouthCloseThreshold = 0.02;

    /**
     * Tiempo (ms) que la boca se mantiene <b>abierta</b> tras la última
     * actividad (volumen por encima del umbral de apertura o llegada de una
     * sílaba nueva a la cola) antes de volver a NEUTRAL.
     *
     * <p>Sustituye (y complementa) al antiguo {@code silenceHoldMs}: ahora el
     * "hold de silencio" se cuenta a partir de la última <b>actividad</b>
     * (no solo desde que la fila de sílabas se vació). Así si el usuario
     * mantiene la voz por encima del umbral la boca no se cierra, aunque no
     * haya sílabas nuevas en la cola (Vosk no siempre emite sílabas).
     *
     * <p>El default 160 ms cubre las pausas típicas entre sílabas/palabras
     * (~80–140 ms) sin "parpadeo" a boca cerrada, y deja que las pausas
     * largas (>160 ms) cierren la boca.
     */
    private long mouthReleaseMs = 160L;

    /**
     * <b>LEGACY</b> — Duración (ms) que se mantenía la última sílaba hablada
     * antes de volver a NEUTRAL.
     *
     * <p>Este campo quedó <b>sin usar</b> por la implementación de la boca por
     * volumen (ver {@link #mouthReleaseMs}). Se mantiene solo para compatibilidad
     * de serialización: si una {@code app.json} antigua tiene la clave
     * {@code silenceHoldMs}, Jackson la deserializa sin error y la ignora al
     * calcular el estado de la boca. La app ya no la lee.
     *
     * <p>Para afinar el cierre de la boca, usa {@link #mouthReleaseMs}.
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

    public double getMouthOpenThreshold() { return mouthOpenThreshold; }
    public void setMouthOpenThreshold(double mouthOpenThreshold) { this.mouthOpenThreshold = mouthOpenThreshold; }

    public double getMouthCloseThreshold() { return mouthCloseThreshold; }
    public void setMouthCloseThreshold(double mouthCloseThreshold) { this.mouthCloseThreshold = mouthCloseThreshold; }

    public long getMouthReleaseMs() { return mouthReleaseMs; }
    public void setMouthReleaseMs(long mouthReleaseMs) { this.mouthReleaseMs = mouthReleaseMs; }

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
