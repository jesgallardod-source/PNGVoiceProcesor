package org.PNGV.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Garantiza que una {@code app.json} con claves desconocidas / huérfanas
 * (p. ej. la clave legacy {@code agraceDelayEnabled} de una versión anterior)
 * se carga <b>sin error</b> en vez de tumbar la app al arrancar
 * (UnrecognizedPropertyException). Ver {@link ConfigLoader}.
 */
class UnknownPropertyToleranceTest {

    @Test
    void unknownLegacyKeyDoesNotThrowAndKeepsDefaults() throws Exception {
        Path tmp = Files.createTempFile("app", ".json");
        try {
            String json = """
                    {
                      "screamThreshold" : 0.9,
                      "agraceDelayEnabled" : false,
                      "someFutureKey" : "whatever"
                    }
                    """;
            Files.writeString(tmp, json);

            // Antes del fix esto lanzaba UncheckedIOException
            // (UnrecognizedPropertyException sobre "agraceDelayEnabled").
            AppConfig cfg = new ConfigLoader().load(tmp);

            // La clave conocida se respeta...
            assertEquals(0.9, cfg.getScreamThreshold());
            // ...y el default del flag nuevo se conserva (true).
            assertTrue(cfg.isSoundSyncLetterEnabled());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
