package org.PNGV.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica que el flag {@code soundSyncLetterEnabled} (activar/desactivar la
 * "letra A al detectar sonido") se serializa/deserializa de forma estable y
 * que el default es {@code true}.
 */
class SoundSyncLetterFlagTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void defaultIsEnabled() {
        assertTrue(new AppConfig().isSoundSyncLetterEnabled());
    }

    @Test
    void serializedKeyIsCamelCase() throws Exception {
        AppConfig cfg = new AppConfig();
        cfg.setSoundSyncLetterEnabled(false);
        String json = mapper.writeValueAsString(cfg);
        assertTrue(json.contains("\"soundSyncLetterEnabled\""),
                "La clave serializada debe ser 'soundSyncLetterEnabled' pero el JSON fue:\n" + json);
    }

    @Test
    void roundTripPreservesFalseValue() throws Exception {
        AppConfig cfg = new AppConfig();
        cfg.setSoundSyncLetterEnabled(false);
        String json = mapper.writeValueAsString(cfg);
        AppConfig loaded = mapper.readValue(json, AppConfig.class);
        assertFalse(loaded.isSoundSyncLetterEnabled());
    }

    @Test
    void roundTripPreservesTrueValue() throws Exception {
        AppConfig cfg = new AppConfig();
        cfg.setSoundSyncLetterEnabled(true);
        String json = mapper.writeValueAsString(cfg);
        AppConfig loaded = mapper.readValue(json, AppConfig.class);
        assertTrue(loaded.isSoundSyncLetterEnabled());
    }

    @Test
    void missingKeyFallsBackToDefaultTrue() throws Exception {
        // Una app.json antigua sin la clave debe deserializarse como true (default).
        AppConfig loaded = mapper.readValue("{}", AppConfig.class);
        assertEquals(true, loaded.isSoundSyncLetterEnabled());
    }
}
