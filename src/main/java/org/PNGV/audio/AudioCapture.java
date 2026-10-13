package org.PNGV.audio;

import org.PNGV.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captura audio del micrófono (PCM 16-bit mono) en un hilo dedicado y
 * publica el nivel de volumen (0-3) y el valor normalizado (0-1) de forma
 * thread-safe para que la UI (Application Thread de JavaFX) pueda leerlos.
 *
 * <p>El flujo:
 * <pre>
 *   TargetDataLine.read() → int[] → VolumeAnalyzer → AtomicReference&lt;VolumeState&gt;
 * </pre>
 */
public class AudioCapture implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AudioCapture.class);

    private final AppConfig config;
    private final VolumeAnalyzer analyzer;

    private TargetDataLine line;
    private Thread thread;
    private volatile boolean running = false;

    /** Estado más reciente publicado por el hilo de audio. */
    private final AtomicReference<VolumeState> stateRef =
            new AtomicReference<>(VolumeState.SILENCE);

    public AudioCapture(AppConfig config, VolumeAnalyzer analyzer) {
        this.config = config;
        this.analyzer = analyzer;
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
        LOG.info("AudioCapture iniciado ({} Hz, {} frames/buffer)",
                config.getSampleRate(), config.getBufferFrames());
    }

    private void openLine() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(
                config.getSampleRate(),
                16,          // bits por muestra
                1,           // mono
                true,        // signed
                false);      // little-endian
        line = AudioSystem.getTargetDataLine(format);
        line.open(format, config.getBufferFrames());
        line.start();
    }

    private void loop() {
        int frames = config.getBufferFrames();
        byte[] raw = new byte[frames * 2]; // 16-bit = 2 bytes por muestra
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int[] samples = new int[frames];

        while (running) {
            int n = line.read(raw, 0, raw.length);
            if (n <= 0) {
                continue;
            }
            int count = n / 2; // muestras int16
            buf.clear();
            for (int i = 0; i < count; i++) {
                samples[i] = buf.getShort();
            }

            int level = analyzer.analyzeLevel(samples);
            double normalized = analyzer.getNormalizedLevel();
            stateRef.set(new VolumeState(level, normalized));
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
