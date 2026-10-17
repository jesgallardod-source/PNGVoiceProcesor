package org.PNGV.app;

/**
 * Construye mensajes de error legibles para el usuario cuando el modelo
 * nativo (Vosk) no puede cargarse.
 *
 * <p>Está en una clase independiente de {@link PngVoiceApp} para que pueda
 * probarse sin cargar JavaFX (la clase principal extiende
 * {@code javafx.application.Application}, lo que impide instanciarla en
 * un entorno de test sin display).
 */
public final class NativeModelErrorBuilder {

    private NativeModelErrorBuilder() {
        // utilitario: no instanciar
    }

    /**
     * Construye el mensaje legible que se muestra al usuario cuando el modelo
     * nativo no carga. Distingue el caso concreto de {@link UnsatisfiedLinkError}
     * (binario JNI incompatible/ausente) del resto de errores.
     *
     * @param cause error original (puede ser {@code null})
     * @return mensaje de error en español, listo para mostrarse
     */
    public static String build(Throwable cause) {
        StringBuilder sb = new StringBuilder();
        if (cause instanceof UnsatisfiedLinkError) {
            sb.append("No se encontró una librería nativa compatible para el sistema operativo "
                    + "y arquitectura actuales.\n\n")
              .append("Verifica que:\n")
              .append("  • La librería nativa de Vosk exista para tu plataforma (OS/CPU).\n")
              .append("  • Estés ejecutando la versión correcta del JAR (fat-jar con dependencias).\n");
        } else if (cause != null) {
            sb.append("Ocurrió un error al cargar el modelo de reconocimiento de voz.\n\n");
        }
        if (cause != null && cause.getMessage() != null) {
            sb.append("Detalle: ").append(cause.getMessage());
        }
        return sb.toString();
    }
}
