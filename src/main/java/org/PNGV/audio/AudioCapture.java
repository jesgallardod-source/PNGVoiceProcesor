package org.PNGV.audio;

import org.PNGV.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captura audio del micrófono (PCM 16-bit mono) en un hilo dedicado y
 * publica el nivel de volumen (0-3) y el valor normalizado (0-1) de forma
 * thread-safe para que la UI (Application Thread de JavaFX) pueda leerlos.
 *
 * <p>El flujo:
 * <pre>
 *   TargetDataLine.read() → [SampleRateFilter] → int[] → VolumeAnalyzer → AtomicReference&lt;VolumeState&gt;
 *                                            └──────► [SampleRateFilter] → AudioFrameProcessor (Vosk)
 * </pre>
 *
 * <p><b>Garantía de frecuencia:</b> aunque el SO/driver entregue el audio a
 * otra frecuencia (44100/48000 Hz), el {@link SampleRateFilter} re-muestrea
 * cada buffer a la frecuencia objetivo ({@code config.sampleRate}, 16000 Hz
 * por defecto) <b>antes</b> de pasarlo al analizador de volumen y al
 * reconocedor Vosk. Así el pipeline completo siempre ve audio a 16 kHz, que
 * es la frecuencia con la que está entrenado el modelo.
 */
public class AudioCapture implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AudioCapture.class);

    private final AppConfig config;
    private final VolumeAnalyzer analyzer;
    /**
     * Procesador opcional de cada buffer PCM (p. ej. un reconocedor de
     * fonemas). Si es {@code null}, solo se publica el volumen.
     */
    private final AudioFrameProcessor frameProcessor;

    private TargetDataLine line;
    private Thread thread;
    private volatile boolean running = false;
    /**
     * Re-muestreador de cada buffer PCM a la frecuencia objetivo
     * ({@code config.sampleRate}). Se crea en {@link #start()} y es
     * {@code null} mientras la captura no ha iniciado.
     */
    private SampleRateFilter resampler;
    /**
     * Frecuencia real (Hz) con la que la línea de captura está abierta.
     * Puede diferir de {@code config.sampleRate} si el hardware no soporta
     * la frecuencia objetivo. Se usa para construir el re-muestreador.
     */
    private int actualCaptureRate;

    /** Estado más reciente publicado por el hilo de audio. */
    private final AtomicReference<VolumeState> stateRef =
            new AtomicReference<>(VolumeState.SILENCE);

    public AudioCapture(AppConfig config, VolumeAnalyzer analyzer) {
        this(config, analyzer, null);
    }

    /**
     * Crea un captor con un procesador de buffers PCM adicional (reconocedor
     * de fonemas).
     *
     * @param config         configuración de la app
     * @param analyzer       analizador de volumen
     * @param frameProcessor callback opcional por buffer PCM (puede ser null)
     */
    public AudioCapture(AppConfig config, VolumeAnalyzer analyzer, AudioFrameProcessor frameProcessor) {
        this.config = config;
        this.analyzer = analyzer;
        this.frameProcessor = frameProcessor;
    }

    /** Inicia la captura de audio en un hilo separado. */
    public void start() {
        if (running) {
            return;
        }
        try {
            openLine();
        } catch (LineUnavailableException e) {
            throw new IllegalStateException("Micrófono no disponible", e);
        }
        running = true;
        thread = new Thread(this::loop, "audio-capture");
        thread.setDaemon(true);
        thread.start();
        if (actualCaptureRate != config.getSampleRate()) {
            LOG.info("AudioCapture iniciado (captura a {} Hz → re-muestreado a {} Hz, {} frames/buffer)",
                    actualCaptureRate, config.getSampleRate(), config.getBufferFrames());
        } else {
            LOG.info("AudioCapture iniciado ({} Hz, {} frames/buffer)",
                    config.getSampleRate(), config.getBufferFrames());
        }
    }

    /**
     * Abre la línea de captura. Intenta primero la frecuencia objetivo
     * ({@code config.sampleRate}). Si el hardware no la soporta, busca la
     * frecuencia de captura más cercana (de las que soporta el driver) y
     * deja que el {@link SampleRateFilter} la convierta a la frecuencia
     * objetivo.
     *
     * @throws LineUnavailableException si no se puede abrir ninguna línea
     */
    private void openLine() throws LineUnavailableException {
        int targetRate = config.getSampleRate();
        AudioFormat desired = new AudioFormat(
                targetRate,
                16,          // bits por muestra
                1,           // mono
                true,        // signed
                false);      // little-endian

        // 1) Intentar la frecuencia objetivo directamente.
        try {
            line = AudioSystem.getTargetDataLine(desired);
            line.open(desired, config.getBufferFrames());
            // El driver puede abrir la línea "con éxito" entregando otra
            // frecuencia (SRC interna del SO). Verificamos la real para que
            // el SampleRateFilter use la frecuencia correcta.
            actualCaptureRate = (int) line.getFormat().getSampleRate();
            if (actualCaptureRate != targetRate) {
                LOG.warn("Se pidió {} Hz pero la línea entregó {} Hz; se re-muestreará a {} Hz",
                        targetRate, actualCaptureRate, targetRate);
            }
        } catch (LineUnavailableException e) {
            // 2) El driver no soporta la frecuencia objetivo. Intentar
            //    frecuencias de captura comunes, de la más cercana a la
            //    más lejana. (16 kHz → 32 kHz → 48 kHz → 44.1 kHz → 8 kHz)
            int[] candidates = {32000, 48000, 44100, 8000};
            for (int candidate : candidates) {
                if (candidate == targetRate) {
                    continue;
                }
                AudioFormat fallback = new AudioFormat(candidate, 16, 1, true, false);
                try {
                    line = AudioSystem.getTargetDataLine(fallback);
                    line.open(fallback, config.getBufferFrames());
                    int actual = (int) line.getFormat().getSampleRate();
                    if (actual != candidate) {
                        LOG.warn("Fallback: se pidió {} Hz pero la línea entregó {} Hz",
                                candidate, actual);
                    }
                    actualCaptureRate = actual;
                    LOG.info("Frecuencia objetivo {} Hz no disponible; capturando a {} Hz (re-muestreado a {} Hz)",
                            targetRate, actualCaptureRate, targetRate);
                    break;
                } catch (LineUnavailableException ignored) {
                    // Probar la siguiente frecuencia candidata.
                }
            }
            if (line == null) {
                throw new LineUnavailableException(
                        "Ninguna frecuencia de captura disponible (se intentó " + targetRate + " Hz)");
            }
        }

        // 3) Construir el re-muestreador (passthrough si ya está a la
        //    frecuencia objetivo, o real si hay que convertir).
        resampler = new SampleRateFilter(actualCaptureRate, targetRate);
        if (resampler.isPassthrough()) {
            LOG.info("AudioCapture: pipeline a {} Hz (passthrough, sin re-muestreo)", targetRate);
        } else {
            LOG.info("AudioCapture: pipeline {} Hz → {} Hz (interpolación lineal)",
                    actualCaptureRate, targetRate);
        }

        line.start();
    }

    private void loop() {
        int frames = config.getBufferFrames();
        byte[] raw = new byte[frames * 2]; // 16-bit = 2 bytes por muestra

        while (running) {
            int n = line.read(raw, 0, raw.length);
            if (n <= 0) {
                continue;
            }

            // Re-muestrear a la frecuencia objetivo (16 kHz) antes de pasar
            // al analizador de volumen y al reconocedor. Si la captura ya
            // está a la frecuencia objetivo, process() devuelve el mismo
            // buffer (passthrough, sin copia).
            byte[] processed;
            int processedLen;
            if (resampler != null) {
                processed = resampler.process(raw, n);
                processedLen = processed.length;
            } else {
                processed = raw;
                processedLen = n;
            }

            // Decodificar PCM 16-bit little-endian a int[] (para el analizador
            // de volumen, que trabaja en muestras enteras).
            int count = processedLen / 2;
            int[] samples = new int[count];
            for (int i = 0; i < count; i++) {
                samples[i] = (short) ((processed[i * 2] & 0xFF)
                        | (processed[i * 2 + 1] << 8));
            }

            int level = analyzer.analyzeLevel(samples);
            double normalized = analyzer.getNormalizedLevel();
            stateRef.set(new VolumeState(level, normalized));

            // Procesador opcional (p. ej. reconocedor de fonemas). Las
            // excepciones se capturan para no tumbar el hilo de audio.
            if (frameProcessor != null) {
                try {
                    frameProcessor.process(processed, processedLen);
                } catch (Exception e) {
                    LOG.warn("AudioFrameProcessor falló: {}", e.getMessage(), e);
                }
            }
        }
    }

    /** Devuelve el estado de volumen más reciente. */
    public VolumeState getState() {
        return stateRef.get();
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (line != null) {
            line.close();
            line = null;
        }
        LOG.info("AudioCapture detenido");
    }

    /**
     * Estado inmutable de volumen publicado por el hilo de audio.
     */
    public record VolumeState(int level, double normalized) {
        public static final VolumeState SILENCE = new VolumeState(0, 0.0);
    }
}
