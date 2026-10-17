package org.PNGV.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas de {@link NativeModelErrorBuilder#build(Throwable)}.
 *
 * <p>La lógica de construcción del mensaje está en una clase independiente
 * de {@link PngVoiceApp} para poder probarse sin cargar JavaFX (la clase
 * principal extiende {@code javafx.application.Application}, lo que impide
 * instanciarla en un entorno de test sin display).
 *
 * <p>Asegura que el mensaje de error que se muestra al usuario distingue el
 * caso de {@link UnsatisfiedLinkError} del resto de errores y que incluye
 * el detalle de la causa cuando existe.
 */
class NativeModelErrorTest {

    @Test
    @DisplayName("UnsatisfiedLinkError → mensaje específico de librería nativa")
    void unsatisfiedLinkError_produceSpecificMessage() {
        UnsatisfiedLinkError cause =
                new UnsatisfiedLinkError("no vosk in java.library.path");

        String msg = NativeModelErrorBuilder.build(cause);

        assertTrue(msg.contains("librería nativa"),
                "Debe mencionar que falta la librería nativa");
        assertTrue(msg.contains("Vosk"),
                "Debe mencionar a Vosk");
        assertTrue(msg.contains("no vosk in java.library.path"),
                "Debe incluir el detalle del error de causa");
    }

    @Test
    @DisplayName("Excepción genérica → mensaje genérico de carga de modelo")
    void genericException_produceGenericMessage() {
        IllegalStateException cause =
                new IllegalStateException("modelo corrupto");

        String msg = NativeModelErrorBuilder.build(cause);

        assertTrue(msg.contains("modelo de reconocimiento"),
                "Debe indicar que falló la carga del modelo");
        assertTrue(msg.contains("modelo corrupto"),
                "Debe incluir el detalle del error de causa");
        assertFalse(msg.contains("librería nativa"),
                "No debe usar el mensaje específico de JNI para una excepción genérica");
    }

    @Test
    @DisplayName("Causa con mensaje null → no rompe y no añade detalle")
    void nullMessage_doesNotThrow() {
        RuntimeException cause = new RuntimeException();

        String msg = NativeModelErrorBuilder.build(cause);

        assertTrue(msg.contains("modelo de reconocimiento"),
                "Debe usar el mensaje genérico");
        assertFalse(msg.contains("Detalle: null"),
                "No debe añadir el literal 'null' como detalle");
    }

    @Test
    @DisplayName("Causa null → mensaje vacío, sin excepciones")
    void nullCause_returnsEmptyMessage() {
        String msg = NativeModelErrorBuilder.build(null);
        assertTrue(msg.isEmpty(), "Sin causa debe devolver un mensaje vacío");
    }
}
