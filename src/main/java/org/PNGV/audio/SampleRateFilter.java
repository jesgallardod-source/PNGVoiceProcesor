package org.PNGV.audio;

/**
 * Re-muestreador de audio PCM 16-bit mono de una frecuencia a otra
 * (p. ej. 48000 Hz → 16000 Hz) usando <b>interpolación lineal</b>.
 *
 * <p><b>Por qué existe:</b> la API {@code javax.sound.sampled} abre la línea
 * de captura con el formato pedido, pero si el driver del micrófono no soporta
 * esa frecuencia, el SO entrega el audio a otra frecuencia (típicamente 44100
 * o 48000 Hz). Vosk <b>no re-muestrea internamente</b>: asume que la señal
 * llega a la frecuencia con la que se creó el {@code Recognizer}. Si la app
 * le pasa audio a 48 kHz cuando el modelo espera 16 kHz, el reconocimiento
 * sale "acelerado" (voz de ardilla) y produce texto aleatorio.
 *
 * <p>Este filtro se aplica <b>después</b> de {@code TargetDataLine.read()}
 * y <b>antes</b> de {@code VolumeAnalyzer} y {@code AudioFrameProcessor},
 * garantizando que todo el pipeline (volumen + Vosk) reciba audio a la
 * frecuencia objetivo (16 kHz por defecto).
 *
 * <p><b>Stateful:</b> conserva la última muestra del chunk anterior
 * ({@code pendingSample}) para que la interpolación en la frontera entre
 * chunks sea continua (no hay "corte" ni click en cada buffer).
 *
 * <p><b>Uso típico:</b>
 * <pre>
 *   SampleRateFilter r = new SampleRateFilter(48000, 16000);
 *   byte[] out = r.process(raw, n);
 *   // out está a 16 kHz, listo para Vosk
 * </pre>
 *
 * <p><b>Nota de calidad:</b> la interpolación lineal es suficiente para
 * downsample (48k→16k, ratio 3:1) en el rango de voz humana (300 Hz–4 kHz).
 * No es un filtro anti-aliasing ideal, pero para un avatar local tipo
 * Veadotube es más que aceptable y sin dependencias externas.
 */
public final class SampleRateFilter {

    /** Frecuencia de muestreo de entrada (Hz) — la que entrega el hardware/OS. */
    private final int inputRate;
    /** Frecuencia de muestreo de salida (Hz) — la que espera el pipeline (p. ej. 16000). */
    private final int targetRate;
    /**
     * Ratio de muestreo: targetRate / inputRate.
     * &lt; 1 = downsample (48k→16k), &gt; 1 = upsample (8k→16k), 1 = passthrough.
     */
    private final double ratio;
    /**
     * Última muestra de entrada (en float, rango -32768..32767) del chunk
     * anterior. Sirve para que la interpolación en la frontera entre chunks
     * sea continua. Inicialmente 0.0.
     */
    private float pendingSample = 0f;

    /**
     * Crea un re-muestreador.
     *
     * @param inputRate  frecuencia de muestreo de entrada (Hz), p. ej. 48000
     * @param targetRate frecuencia de muestreo de salida (Hz), p. ej. 16000
     * @throws IllegalArgumentException si alguna frecuencia es &lt;= 0
     */
    public SampleRateFilter(int inputRate, int targetRate) {
        if (inputRate <= 0) {
            throw new IllegalArgumentException("inputRate debe ser > 0, recibido: " + inputRate);
        }
        if (targetRate <= 0) {
            throw new IllegalArgumentException("targetRate debe ser > 0, recibido: " + targetRate);
        }
        this.inputRate = inputRate;
        this.targetRate = targetRate;
        this.ratio = (double) targetRate / inputRate;
    }

    /** Devuelve la frecuencia de entrada (Hz). */
    public int getInputRate() {
        return inputRate;
    }

    /** Devuelve la frecuencia de salida (Hz). */
    public int getTargetRate() {
        return targetRate;
    }

    /**
     * Devuelve {@code true} si no hace falta re-muestrear
     * (frecuencias iguales). En ese caso {@link #process(byte[], int)}
     * devuelve el buffer de entrada sin copiar (passthrough).
     */
    public boolean isPassthrough() {
        return inputRate == targetRate;
    }

    /**
     * Re-muestrea un chunk PCM 16-bit mono little-endian de {@code inputRate}
     * a {@code targetRate} Hz.
     *
     * <p>Si las frecuencias son iguales (passthrough), devuelve el mismo
     * array de entrada (sin copia).
     *
     * @param pcm    buffer PCM 16-bit mono little-endian (rango -32768..32767)
     * @param length longitud en <b>bytes</b> del fragmento a procesar
     *               (debe ser par; cada muestra ocupa 2 bytes)
     * @return nuevo buffer PCM 16-bit mono little-endian a la frecuencia objetivo
     * @throws IllegalArgumentException si {@code length} es impar
     *         (no puede haber media muestra PCM 16-bit)
     */
    public byte[] process(byte[] pcm, int length) {
        if (length < 0 || length > pcm.length) {
            throw new IllegalArgumentException(
                    "length fuera de rango: " + length + " (pcm.length=" + pcm.length + ")");
        }
        if (isPassthrough()) {
            // Sin re-muestreo: reutilizamos el buffer del llamador para no
            // copiar. El llamador (AudioCapture) no modifica el buffer después
            // de llamar a process(), por lo que es seguro.
            return pcm;
        }
        if ((length & 1) != 0) {
            throw new IllegalArgumentException(
                    "length debe ser par (PCM 16-bit): " + length);
        }
        int inSamples = length / 2;
        if (inSamples == 0) {
            return new byte[0];
        }
        int outSamples = (int) Math.round(inSamples * ratio);
        if (outSamples == 0) {
            return new byte[0];
        }
        byte[] out = new byte[outSamples * 2];
        for (int i = 0; i < outSamples; i++) {
            // Posición (fraccionaria) en el dominio de la señal de entrada
            // que corresponde a la muestra de salida i.
            // ratio = targetRate / inputRate, así:
            //   idx = i / ratio = i * inputRate / targetRate
            // Ejemplo downsample 48k→16k (ratio=1/3): out[0]=in[0], out[1]=in[3], out[2]=in[6]…
            // Ejemplo upsample 8k→16k (ratio=2):      out[0]=in[0], out[1]=interp(in[0],in[1]), out[2]=in[1]…
            double idx = i / ratio;
            int idxInt = (int) idx;
            double frac = idx - idxInt;
            float s0, s1;
            if (idxInt < inSamples) {
                s0 = (short) ((pcm[idxInt * 2] & 0xFF) | (pcm[idxInt * 2 + 1] << 8));
                if (idxInt + 1 < inSamples) {
                    s1 = (short) ((pcm[(idxInt + 1) * 2] & 0xFF) | (pcm[(idxInt + 1) * 2 + 1] << 8));
                } else {
                    // Última muestra del chunk actual: la siguiente "virtual"
                    // es la última muestra (franja plana hasta el próximo chunk).
                    s1 = s0;
                }
            } else if (idxInt == inSamples) {
                // El índice cae justo en la frontera con el próximo chunk:
                // s0 es la última muestra del chunk actual, s1 la primera
                // del siguiente (que aún no llegó).
                s0 = (short) ((pcm[(inSamples - 1) * 2] & 0xFF)
                        | (pcm[(inSamples - 1) * 2 + 1] << 8));
                s1 = pendingSample;
            } else {
                // idxInt > inSamples: la muestra de salida corresponde a
                // una posición ya cubierta por el chunk anterior (no debería
                // ocurrir con ratio constante y chunks consecutivos, pero
                // lo manejamos de forma defensiva).
                s0 = pendingSample;
                s1 = pendingSample;
            }
            float interpolated = s0 + (float) frac * (s1 - s0);
            int clamped = (int) Math.max(-32768, Math.min(32767, interpolated));
            out[i * 2] = (byte) (clamped & 0xFF);
            out[i * 2 + 1] = (byte) ((clamped >> 8) & 0xFF);
        }
        // Conservamos la última muestra de entrada para el próximo chunk.
        pendingSample = (short) ((pcm[(inSamples - 1) * 2] & 0xFF)
                | (pcm[(inSamples - 1) * 2 + 1] << 8));
        return out;
    }

    /** Reinicia el estado interno (muestra pendiente) a 0. Útil entre sesiones. */
    public void reset() {
        pendingSample = 0f;
    }
}
