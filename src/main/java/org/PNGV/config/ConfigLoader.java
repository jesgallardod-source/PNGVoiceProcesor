package org.PNGV.config;

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

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

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
