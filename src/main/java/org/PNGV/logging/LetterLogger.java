package org.PNGV.logging;

import org.PNGV.phoneme.Viseme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Logger dedicado para registrar cada <b>letra/viseme</b> que el avatar
 * muestra y cuánto tiempo tardó en mostrarse.
 *
 * <p>Funciones:
 * <ul>
 *   <li>Registra cada cambio de viseme con su duración (ms).</li>
 *   <li>Mantiene un acumulado de tiempo por viseme.</li>
 *   <li>Imprime un resumen periódico (cada N cambios) con las letras que
 *       más tiempo han tardado en mostrarse.</li>
 *   <li>{@link #stop()} imprime el resumen final.</li>
 * </ul>
 *
 * <p>Thread-safe: se usa desde el hilo de JavaFX (AnimationTimer).
 */
public class LetterLogger {

    private static final Logger LOG = LoggerFactory.getLogger(LetterLogger.class);

    /** Cada cuántos cambios se imprime un resumen intermedio. */
    private static final int SUMMARY_EVERY_N = 20;

    /**
     * Registro de un cambio de viseme.
     */
    public record VisemeEntry(Viseme viseme, long durationMs, long timestampNanos) {}

    /** El viseme actualmente "activo" (mostrándose). */
    private final AtomicReference<Viseme> current = new AtomicReference<>(Viseme.NEUTRAL);
    /** Momento (ns) en que el viseme actual empezó a mostrarse. */
    private volatile long currentStartNanos = 0L;
    /** Total de cambios registrados. */
    private volatile int changeCount = 0;
    /** Acumulado de tiempo (ms) por viseme. */
    private final Map<Viseme, Long> totalMsByViseme = new LinkedHashMap<>();
    /** true cuando se ha llamado stop(). */
    private volatile boolean stopped = false;

    // ===================================================================
    // API pública
    // ===================================================================

    /**
     * Registra que el viseme {@code viseme} empieza a mostrarse en el momento
     * {@code now} (nanos del AnimationTimer).
     *
     * <p>Si había un viseme anterior activo, calcula cuánto duró y lo loguea.
     */
    public void record(Viseme viseme, long now) {
        if (stopped) {
            return;
        }
        Viseme prev = current.get();
        if (prev == viseme) {
            return; // no cambió
        }

        // Calcular duración del viseme anterior
        long prevDuration = 0L;
        if (prev != null && currentStartNanos > 0) {
            prevDuration = (now - currentStartNanos) / 1_000_000L;
        }

        // Loguear el cambio
        LOG.info("[LETRA] {} → {} | duración anterior: {} ms",
                prev, viseme, prevDuration);

        // Actualizar acumulados
        synchronized (totalMsByViseme) {
            totalMsByViseme.merge(prev, prevDuration, Long::sum);
        }

        // Avanzar al nuevo viseme
        current.set(viseme);
        currentStartNanos = now;
        changeCount++;

        // Resumen periódico
        if (changeCount % SUMMARY_EVERY_N == 0) {
            logInterimSummary();
        }
    }

    /**
     * Detiene el logger y imprime el resumen final con las letras que más
     * tiempo tardaron en mostrarse.
     */
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;

        // Cerrar el viseme actualmente activo con el tiempo transcurrido
        Viseme last = current.get();
        if (last != null && currentStartNanos > 0) {
            long lastDuration = System.nanoTime() - currentStartNanos;
            lastDuration = lastDuration / 1_000_000L;
            LOG.info("[LETRA] {} (última) | duración: {} ms", last, lastDuration);
            synchronized (totalMsByViseme) {
                totalMsByViseme.merge(last, lastDuration, Long::sum);
            }
        }

        logFinalSummary();
    }

    /**
     * Reinicia el logger (para un nuevo ciclo de micrófono ON).
     */
    public void reset() {
        stopped = false;
        current.set(Viseme.NEUTRAL);
        currentStartNanos = 0L;
        changeCount = 0;
        synchronized (totalMsByViseme) {
            totalMsByViseme.clear();
        }
        LOG.info("[LETRA] Logger reiniciado");
    }

    /**
     * Devuelve el resumen actual como string (útil para tests o UI).
     */
    public String summary() {
        StringBuilder sb = new StringBuilder("Resumen de letras por tiempo mostrado:\n");
        synchronized (totalMsByViseme) {
            totalMsByViseme.entrySet().stream()
                    .sorted(Map.Entry.<Viseme, Long>comparingByValue().reversed())
                    .forEach(e -> sb.append(String.format("  %-12s → %8d ms%n", e.getKey(), e.getValue())));
        }
        return sb.toString();
    }

    // ===================================================================
    // Internos
    // ===================================================================

    private void logInterimSummary() {
        LOG.info("[LETRA] --- Resumen intermedio ({} cambios) ---", changeCount);
        synchronized (totalMsByViseme) {
            totalMsByViseme.entrySet().stream()
                    .sorted(Map.Entry.<Viseme, Long>comparingByValue().reversed())
                    .limit(5)
                    .forEach(e -> LOG.info("[LETRA]   {:<12} → {} ms", e.getKey(), e.getValue()));
        }
        LOG.info("[LETRA] --- Fin resumen ---");
    }

    private void logFinalSummary() {
        LOG.info("[LETRA] ═══════════════════════════════════════════");
        LOG.info("[LETRA] RESUMEN FINAL ({} cambios totales)", changeCount);
        LOG.info("[LETRA] ═══════════════════════════════════════════");
        synchronized (totalMsByViseme) {
            totalMsByViseme.entrySet().stream()
                    .sorted(Map.Entry.<Viseme, Long>comparingByValue().reversed())
                    .forEach(e -> LOG.info("[LETRA]   {:<12} → {:>8} ms", e.getKey(), e.getValue()));
        }
        LOG.info("[LETRA] ═══════════════════════════════════════════");
    }
}
