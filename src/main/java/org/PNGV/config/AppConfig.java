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

    /** Alfa del suavizado exponencial (0..1). 0.5 = muy reactivo, 0.9 = muy suave. */
    private double smoothing = 0.5;

    /** Muestra por buffer de captura (16-bit). 1024 es un buen valor. */
    private int bufferFrames = 1024;
    /** Frecuencia de muestreo. */
    private int sampleRate = 44100;

    /** Ruta al modelo de Vosk (p. ej. assets/models/vosk-model-small-es-0.42). */
    private String modelPath = "assets/models/vosk-model-small-es-0.42";

    /** Ruta al avatar activo (hoja de sprites PNG). */
    private String activeAvatar = "assets/avatars/avatar1.png";

    /** Color de fondo de la ventana (hex #RRGGBB). Por defecto verde croma-key. */
    private String backgroundColor = "#00FF00";

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

    public double getSmoothing() { return smoothing; }
    public void setSmoothing(double smoothing) { this.smoothing = smoothing; }

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

    public Map<String, String> getVisemeImages() {
        return visemeImages;
    }

    public void setVisemeImages(Map<String, String> visemeImages) {
        this.visemeImages = visemeImages == null ? new LinkedHashMap<>() : visemeImages;
    }
}
