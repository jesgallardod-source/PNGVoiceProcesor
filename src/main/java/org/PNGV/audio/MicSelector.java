package org.PNGV.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utilidad para enumerar y resolver micrófonos (dispositivos de entrada de
 * audio) del sistema usando la API estándar {@link AudioSystem}.
 *
 * <p>No depende de JavaFX ni de ningún otro framework: solo
 * {@code javax.sound.sampled} (incluido en el JDK). Funciona igual en
 * Windows, macOS y Linux.
 *
 * <p>Uso típico:
 * <pre>
 *   List&lt;String&gt; mics = MicSelector.listMicNames();
 *   // → ["(Predeterminado del sistema)", "Microphone (Realtek Audio)", ...]
 *
 *   Mixer.Info info = MicSelector.resolve("Microphone (Realtek Audio)");
 *   // info == null → usar AudioSystem.getTargetDataLine(format) (default)
 *   // info != null → AudioSystem.getTargetDataLine(format, info)
 * </pre>
 *
 * <p>La lista de micrófonos se obtiene filtrando
 * {@link AudioSystem#getMixerInfo()} por mixers que <b>sí</b> pueden
 * proporcionar una {@code TargetDataLine} (dispositivos de captura).
 */
public final class MicSelector {

    /** Nombre sintético para el micrófono por defecto del sistema. */
    public static final String DEFAULT_MIC = "(Predeterminado del sistema)";

    /**
     * Formato de prueba para verificar si un mixer soporta captura.
     * 16 kHz, 16-bit, mono, PCM signed, little-endian.
     */
    private static final AudioFormat PROBE_FORMAT =
            new AudioFormat(16000, 16, 1, true, false);

    private MicSelector() {
        // Utilidad sin instanciar.
    }

    /**
     * Enumera los nombres de todos los micrófonos (dispositivos de captura)
     * disponibles en el sistema.
     *
     * <p>El resultado <b>siempre</b> incluye {@link #DEFAULT_MIC} como
     * primera entrada.
     *
     * @return lista no vacía, inmutable, con al menos {@link #DEFAULT_MIC}
     */
    public static List<String> listMicNames() {
        Map<String, Boolean> names = new LinkedHashMap<>();
        names.put(DEFAULT_MIC, Boolean.TRUE);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (info == null) {
                continue;
            }
            if (isCaptureDevice(info)) {
                String name = info.getName();
                if (name != null && !name.isEmpty() && !DEFAULT_MIC.equals(name)) {
                    names.putIfAbsent(name, Boolean.TRUE);
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(names.keySet()));
    }

    /**
     * Resuelve el {@link Mixer.Info} correspondiente a un nombre de
     * micrófono devuelto por {@link #listMicNames()}.
     *
     * @param micName nombre del micrófono; {@code null}, cadena vacía o
     *                {@link #DEFAULT_MIC} → {@code null} (indicando "usar
     *                el micrófono por defecto del sistema")
     * @return info del mixer, o {@code null} si es el default / no encontrado
     */
    public static Mixer.Info resolve(String micName) {
        if (micName == null || micName.isEmpty() || DEFAULT_MIC.equals(micName)) {
            return null;
        }
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (micName.equals(info.getName()) && isCaptureDevice(info)) {
                return info;
            }
        }
        // Si el micrófono ya no existe (p. ej. se desconectó), cae al default.
        return null;
    }

    /**
     * Verifica si un {@link Mixer.Info} corresponde a un dispositivo de
     * captura (puede proporcionar una {@code TargetDataLine}).
     */
    private static boolean isCaptureDevice(Mixer.Info info) {
        try {
            // Intentar obtener una TargetDataLine; si lanza excepción,
            // el mixer no es de captura.
            AudioSystem.getTargetDataLine(PROBE_FORMAT, info);
            return true;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            return false;
        }
    }
}
