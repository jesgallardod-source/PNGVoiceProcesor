package org.PNGV.audio;

import org.PNGV.config.AppConfig;

/**
 * Analiza el volumen de un buffer de audio PCM 16-bit mono y lo clasifica
 * en un nivel 0-3 (silencio / bajo / medio / alto).
 *
 * <p>El cálculo usa el RMS (Root Mean Square) de las muestras, lo convierte
 * a dBFS y lo compara contra los umbrales configurados.
 *
 * <p>La clase aplica un <b>suavizado exponencial</b> (EMA) para evitar
 * que el avatar "parpadee" con los picos del audio.
 *
 * <p><b>Optimización de latencia:</b> {@link #analyze(byte[], int)} trabaja
 * directamente sobre el buffer PCM 16-bit (sin decodificarlo a {@code int[]})
 * y reutiliza el buffer de trabajo, de modo que el hilo de audio no hace
 * ninguna copia ni asignación por buffer. Esto reduce la CPU y el GC del
 * pipeline, dejando más margen para que el reconocedor y la animación
 * respondan rápido.
 */
public class VolumeAnalyzer {

    private final AppConfig config;
    private double smoothedDbfs = -100.0; // valor inicial muy bajo (silencio)

    public VolumeAnalyzer(AppConfig config) {
        this.config = config;
    }

    /**
     * Analiza un buffer de PCM 16-bit mono (little-endian) y devuelve el nivel
     * suavizado (0-3). Trabaja directamente sobre los bytes, sin copiar a
     * {@code int[]}.
     *
     * @param pcm    buffer PCM 16-bit mono little-endian
     * @param length longitud en <b>bytes</b> del fragmento a analizar (debe ser par)
     * @return nivel de volumen 0..3
     */
    public int analyze(byte[] pcm, int length) {
        double rms = computeRms(pcm, length);
        double dbfs = toDbfs(rms);
        double alpha = clamp(config.getSmoothing(), 0.0, 1.0);
        // EMA: new = alpha * old + (1 - alpha) * current
        smoothedDbfs = alpha * smoothedDbfs + (1.0 - alpha) * dbfs;
        return levelFromDbfs(smoothedDbfs);
    }

    /**
     * Analiza un buffer de PCM 16-bit mono (ya decodificado a {@code int[]})
     * y devuelve el nivel suavizado (0-3).
     *
     * <p><b>Nota:</b> se conserva por compatibilidad y pruebas. El pipeline
     * real usa {@link #analyze(byte[], int)}, que evita esta decodificación.
     *
     * @param pcmSamples buffer de int16 (rango -32768..32767)
     * @return nivel de volumen 0..3
     */
    public int analyzeLevel(int[] pcmSamples) {
        if (pcmSamples == null || pcmSamples.length == 0) {
            return levelFromDbfs(smoothedDbfs);
        }
        double rms = computeRms(pcmSamples);
        double dbfs = toDbfs(rms);
        double alpha = clamp(config.getSmoothing(), 0.0, 1.0);
        smoothedDbfs = alpha * smoothedDbfs + (1.0 - alpha) * dbfs;
        return levelFromDbfs(smoothedDbfs);
    }

    /**
     * Normaliza el dBFS suavizado a 0..1 (para una barra de progreso).
     * -100 dBFS = 0.0, -20 dBFS = 1.0.
     */
    public double getNormalizedLevel() {
        double min = -100.0;
        double max = -20.0;
        double v = (smoothedDbfs - min) / (max - min);
        return clamp(v, 0.0, 1.0);
    }

    /** Reinicia el suavizado (p. ej. al cambiar de avatar). */
    public void reset() {
        smoothedDbfs = -100.0;
    }

    // ------------------------------------------------------------------
    // Internos
    // ------------------------------------------------------------------

    /**
     * RMS de un buffer PCM 16-bit (little-endian) usando solo los primeros
     * {@code length} bytes. Evita decodificar a {@code int[]}.
     */
    static double computeRms(byte[] pcm, int length) {
        if (pcm == null || length <= 0) {
            return 0.0;
        }
        int n = length / 2; // número de muestras
        if (n <= 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
            double v = s / 32768.0; // normaliza a [-1, 1)
            sum += v * v;
        }
        double mean = sum / n;
        return Math.sqrt(mean) * 32768.0; // de vuelta a rango int16
    }

    /**
     * RMS de un buffer de int16. Devuelve un valor en el rango [0, 32767].
     */
    static double computeRms(int[] samples) {
        if (samples == null || samples.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int s : samples) {
            double v = s / 32768.0; // normaliza a [-1, 1)
            sum += v * v;
        }
        double mean = sum / samples.length;
        return Math.sqrt(mean) * 32768.0; // de vuelta a rango int16
    }

    /**
     * Convierte un RMS (en rango int16: 0..32767) a dBFS.
     * 0 dBFS = señal a escala completa (32767).
     */
    static double toDbfs(double rms) {
        if (rms <= 0.0) {
            return -100.0; // silencioso
        }
        double fullScale = 32767.0;
        double ratio = rms / fullScale;
        return 20.0 * Math.log10(ratio);
    }

    /**
     * Mapea un dBFS a un nivel discreto 0-3 según los umbrales de config.
     */
    int levelFromDbfs(double dbfs) {
        if (dbfs < config.getThresholdLow()) {
            return 0; // silencio
        }
        if (dbfs < config.getThresholdMid()) {
            return 1; // bajo
        }
        if (dbfs < config.getThresholdHigh()) {
            return 2; // medio
        }
        return 3; // alto / grito
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
