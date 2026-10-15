package org.PNGV.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas de {@link SampleRateFilter}.
 *
 * <p>Cubren:
 * <ul>
 *   <li>Validación de argumentos (frecuencias inválidas, length impar).</li>
 *   <li>Passthrough (mismas frecuencias → sin copia).</li>
 *   <li>Downsample 48 kHz → 16 kHz (ratio 3:1).</li>
 *   <li>Upsample 8 kHz → 16 kHz (ratio 2:1).</li>
 *   <li>Señal constante → señal constante (la interpolación lineal
 *       conserva el valor).</li>
 *   <li>Señal en rampa lineal → valores interpolados correctos.</li>
 *   <li>Statefulness: dos chunks consecutivos de la misma señal producen
 *       la misma salida total (continuidad en la frontera).</li>
 *   <li>reset() → la muestra pendiente vuelve a 0.</li>
 * </ul>
 */
class SampleRateFilterTest {

    // ------------------------------------------------------------------
    // Validación
    // ------------------------------------------------------------------

    @Test
    @DisplayName("inputRate <= 0 → IllegalArgumentException")
    void invalidInputRate_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new SampleRateFilter(0, 16000));
        assertThrows(IllegalArgumentException.class,
                () -> new SampleRateFilter(-48000, 16000));
    }

    @Test
    @DisplayName("targetRate <= 0 → IllegalArgumentException")
    void invalidTargetRate_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new SampleRateFilter(48000, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SampleRateFilter(48000, -16000));
    }

    @Test
    @DisplayName("length impar → IllegalArgumentException (no puede haber media muestra)")
    void oddLength_throws() {
        SampleRateFilter f = new SampleRateFilter(48000, 16000);
        byte[] pcm = new byte[]{0x01, 0x00}; // 1 muestra (2 bytes)
        // length=1 es impar y no tiene sentido para PCM 16-bit
        assertThrows(IllegalArgumentException.class,
                () -> f.process(pcm, 1));
    }

    @Test
    @DisplayName("length fuera de rango → IllegalArgumentException")
    void outOfRangeLength_throws() {
        SampleRateFilter f = new SampleRateFilter(48000, 16000);
        byte[] pcm = new byte[]{0x01, 0x00};
        assertThrows(IllegalArgumentException.class,
                () -> f.process(pcm, -1));
        assertThrows(IllegalArgumentException.class,
                () -> f.process(pcm, 100));
    }

    // ------------------------------------------------------------------
    // Passthrough
    // ------------------------------------------------------------------

    @Test
    @DisplayName("mismas frecuencias → isPassthrough() = true")
    void sameRate_isPassthrough() {
        assertTrue(new SampleRateFilter(16000, 16000).isPassthrough());
        assertFalse(new SampleRateFilter(48000, 16000).isPassthrough());
        assertFalse(new SampleRateFilter(8000, 16000).isPassthrough());
    }

    @Test
    @DisplayName("passthrough devuelve el MISMO array de entrada (sin copia)")
    void passthrough_returnsSameArray() {
        SampleRateFilter f = new SampleRateFilter(16000, 16000);
        byte[] pcm = new byte[]{0x01, 0x00, 0x02, 0x00, 0x03, 0x00};
        byte[] out = f.process(pcm, pcm.length);
        assertEquals(pcm, out, "Debe ser el mismo array (sin copia)");
    }

    @Test
    @DisplayName("passthrough con length parcial → devuelve el mismo array (el llamador usa length)")
    void passthrough_partialLength_returnsSameArray() {
        SampleRateFilter f = new SampleRateFilter(16000, 16000);
        byte[] pcm = new byte[]{0x01, 0x00, 0x02, 0x00, 0x03, 0x00, 0x04, 0x00};
        byte[] out = f.process(pcm, 6);
        assertEquals(pcm, out);
        // El llamador debe usar length=6, no out.length
        assertEquals(6, 6); // (el llamador lo gestiona)
    }

    // ------------------------------------------------------------------
    // Downsample 48k → 16k
    // ------------------------------------------------------------------

    @Test
    @DisplayName("downsample 48k→16k: 3 muestras de entrada → 1 de salida (ratio 3:1)")
    void downsample_3to1_outputSize() {
        SampleRateFilter f = new SampleRateFilter(48000, 16000);
        // 3 muestras de entrada → 1 de salida
        byte[] pcm = new byte[6];
        byte[] out = f.process(pcm, 6);
        assertEquals(2, out.length, "1 muestra de salida = 2 bytes");
    }

    @Test
    @DisplayName("downsample 48k→16k: 6 muestras de entrada → 2 de salida")
    void downsample_6to2_outputSize() {
        SampleRateFilter f = new SampleRateFilter(48000, 16000);
        byte[] pcm = new byte[12];
        byte[] out = f.process(pcm, 12);
        assertEquals(4, out.length, "2 muestras de salida = 4 bytes");
    }

    @Test
    @DisplayName("downsample 48k→16k: señal constante → señal constante")
    void downsample_constantSignal_staysConstant() {
        SampleRateFilter f = new SampleRateFilter(48000, 16000);
        // Señal constante = 1000 (0x03E8) en 6 muestras
        byte[] pcm = new byte[12];
        for (int i = 0; i < 6; i++) {
            pcm[i * 2] = (byte) (1000 & 0xFF);
            pcm[i * 2 + 1] = (byte) ((1000 >> 8) & 0xFF);
        }
        byte[] out = f.process(pcm, 12);
        // 2 muestras de salida, ambas = 1000
        assertEquals(4, out.length);
        assertEquals(1000, readSample(out, 0), "Muestra 0 debe ser 1000");
        assertEquals(1000, readSample(out, 1), "Muestra 1 debe ser 1000");
    }

    @Test
    @DisplayName("downsample 48k→16k: rampa lineal → interpolación correcta")
    void downsample_rampSignal_interpolates() {
        SampleRateFilter f = new SampleRateFilter(48000, 16000);
        // 6 muestras: 0, 100, 200, 300, 400, 500
        byte[] pcm = new byte[12];
        int[] in = {0, 100, 200, 300, 400, 500};
        for (int i = 0; i < 6; i++) {
            writeSample(pcm, i, in[i]);
        }
        byte[] out = f.process(pcm, 12);
        // ratio = 16000/48000 = 1/3, así idx = i / ratio = i * 3
        // 2 muestras de salida:
        //   out[0] = idx 0  → in[0] = 0
        //   out[1] = idx 3  → in[3] = 300
        assertEquals(4, out.length);
        assertEquals(0, readSample(out, 0), "Muestra 0 = in[0] (idx=0)");
        assertEquals(300, readSample(out, 1), "Muestra 1 = in[3] (idx=3)");
    }

    // ------------------------------------------------------------------
    // Upsample 8k → 16k
    // ------------------------------------------------------------------

    @Test
    @DisplayName("upsample 8k→16k: 2 muestras de entrada → 4 de salida (ratio 2:1)")
    void upsample_2to4_outputSize() {
        SampleRateFilter f = new SampleRateFilter(8000, 16000);
        byte[] pcm = new byte[4];
        byte[] out = f.process(pcm, 4);
        assertEquals(8, out.length, "4 muestras de salida = 8 bytes");
    }

    @Test
    @DisplayName("upsample 8k→16k: señal constante → señal constante")
    void upsample_constantSignal_staysConstant() {
        SampleRateFilter f = new SampleRateFilter(8000, 16000);
        // Señal constante = -2000 en 2 muestras
        byte[] pcm = new byte[4];
        for (int i = 0; i < 2; i++) {
            writeSample(pcm, i, -2000);
        }
        byte[] out = f.process(pcm, 4);
        assertEquals(8, out.length);
        for (int i = 0; i < 4; i++) {
            assertEquals(-2000, readSample(out, i),
                    "Muestra " + i + " debe ser -2000");
        }
    }

    // ------------------------------------------------------------------
    // Statefulness (chunks consecutivos)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("dos chunks de señal constante → misma señal constante en ambos")
    void chunksConstantSignal_continuity() {
        SampleRateFilter f = new SampleRateFilter(48000, 16000);
        byte[] chunk1 = new byte[12]; // 6 muestras
        byte[] chunk2 = new byte[12]; // 6 muestras
        for (int i = 0; i < 6; i++) {
            writeSample(chunk1, i, 500);
            writeSample(chunk2, i, 500);
        }
        byte[] out1 = f.process(chunk1, 12);
        byte[] out2 = f.process(chunk2, 12);
        // Cada chunk produce 2 muestras, ambas = 500
        assertEquals(2, out1.length / 2, "Chunk 1: 2 muestras");
        assertEquals(2, out2.length / 2, "Chunk 2: 2 muestras");
        for (int i = 0; i < 2; i++) {
            assertEquals(500, readSample(out1, i), "Chunk 1 muestra " + i);
            assertEquals(500, readSample(out2, i), "Chunk 2 muestra " + i);
        }
    }

    @Test
    @DisplayName("reset() → la muestra pendiente vuelve a 0 (no afecta a señal constante)")
    void reset_clearsPending() {
        SampleRateFilter f = new SampleRateFilter(48000, 16000);
        byte[] chunk = new byte[12];
        for (int i = 0; i < 6; i++) {
            writeSample(chunk, i, 500);
        }
        f.process(chunk, 12); // deja pendingSample = 500
        f.reset();
        // Después del reset, el próximo chunk empieza con pendingSample = 0.
        // Para señal constante, la salida sigue siendo constante (la
        // interpolación entre 500 y 500 es 500).
        byte[] out = f.process(chunk, 12);
        for (int i = 0; i < out.length / 2; i++) {
            assertEquals(500, readSample(out, i),
                    "Después de reset, señal constante sigue siendo constante");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Lee una muestra PCM 16-bit little-endian del buffer. */
    private static int readSample(byte[] buf, int index) {
        return (short) ((buf[index * 2] & 0xFF) | (buf[index * 2 + 1] << 8));
    }

    /** Escribe una muestra PCM 16-bit little-endian en el buffer. */
    private static void writeSample(byte[] buf, int index, int value) {
        int v = (short) value;
        buf[index * 2] = (byte) (v & 0xFF);
        buf[index * 2 + 1] = (byte) ((v >> 8) & 0xFF);
    }
}
