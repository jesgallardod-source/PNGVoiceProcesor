package org.PNGV.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Carga y guarda la {@link AppConfig} desde/hacia un archivo JSON usando Jackson.
 *
 * <p>Si el archivo no existe, se crea con los valores por defecto.
 */
public class ConfigLoader {

    // FAIL_ON_UNKNOWN_PROPERTIES = false: si la app.json del usuario contiene
    // claves que ya no existen en AppConfig (p. ej. la clave legacy
    // "agraceDelayEnabled" de una versión anterior de la "letra A al sonar"),
    // se IGNORAN en vez de lanzar UnrecognizedPropertyException y tumbar la app
    // al arrancar. Es la regla de compatibilidad hacia adelante: una config
    // "más vieja" o con campos huérfanos nunca debe impedir el arranque.
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * Carga la configuración desde {@code path}. Si no existe, crea el archivo
     * con valores por defecto y los devuelve.
     */
    public AppConfig load(Path path) {
        try {
            if (Files.exists(path)) {
                AppConfig cfg = mapper.readValue(path.toFile(), AppConfig.class);
                if (cfg != null) {
                    return cfg;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la configuración: " + path, e);
        }
        AppConfig defaults = new AppConfig();
        save(path, defaults);
        return defaults;
    }

    /**
     * Guarda la configuración en {@code path}, creando las carpetas padre si hacen falta.
     */
    public void save(Path path, AppConfig config) {
        Objects.requireNonNull(config, "config");
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            mapper.writeValue(path.toFile(), config);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar la configuración: " + path, e);
        }
    }
}
