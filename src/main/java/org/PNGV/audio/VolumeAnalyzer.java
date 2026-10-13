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
 */
public class VolumeAnalyzer {

    private final AppConfig config;
    private double smoothedDbfs = -100.0; // valor inicial muy bajo (silencio)

    public VolumeAnalyzer(AppConfig config) {
        this.config = config;
    }

    /**
     * Analiza un buffer de PCM 16-bit mono y devuelve el nivel suavizado (0-3).
     *
     * @param pcmSamples buffer de int16 en little-endian (ya convertido a int[])
     * @return nivel de volumen 0..3
     */
    public int analyzeLevel(int[] pcmSamples) {
        double rms = computeRms(pcmSamples);
        double dbfs = toDbfs(rms);
        double alpha = clamp(config.getSmoothing(), 0.0, 1.0);
        // EMA: new = alpha * old + (1 - alpha) * current
        smoothedDbfs = alpha * smoothedDbfs + (1.0 - alpha) * dbfs;
        return levelFromDbfs(smoothedDbfs);
    }

    /**
     * Devuelve el dBFS suavizado actual (útil para la barra de volumen en la UI).
     */
    public double getSmoothedDbfs() {
        return smoothedDbfs;
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
