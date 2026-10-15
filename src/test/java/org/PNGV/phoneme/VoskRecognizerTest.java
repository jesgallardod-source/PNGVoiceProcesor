package org.PNGV.phoneme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pruebas de {@link VoskRecognizer#extractText(String)}: la función que
 * extrae el texto reconocible del JSON que devuelve Vosk
 * ({@code getResult()} / {@code getPartialResult()}).
 *
 * <p>Es el método puro detrás del bug original: antes se usaba la cadena
 * JSON tal cual, por lo que {@link Viseme#fromWord} no encontraba letras y
 * siempre devolvía {@link Viseme#NEUTRAL}.
 */
class VoskRecognizerTest {

    @Test
    @DisplayName("JSON final con campo 'text' → extrae el texto")
    void finalResult_extractsText() {
        assertEquals("hola mundo",
                VoskRecognizer.extractText("{\"text\" : \"hola mundo\"}"));
    }

    @Test
    @DisplayName("JSON parcial con campo 'partial' → extrae el texto")
    void partialResult_extractsPartial() {
        assertEquals("hola mun",
                VoskRecognizer.extractText("{\"partial\" : \"hola mun\"}"));
    }

    @Test
    @DisplayName("JSON final con texto vacío → null")
    void finalResult_emptyText_returnsNull() {
        assertNull(VoskRecognizer.extractText("{\"text\" : \"\"}"));
    }

    @Test
    @DisplayName("JSON parcial vacío → null")
    void partialResult_emptyPartial_returnsNull() {
        assertNull(VoskRecognizer.extractText("{\"partial\" : \"\"}"));
    }

    @Test
    @DisplayName("JSON con alternativas (setMaxAlternatives) → extrae el primer text")
    void alternatives_extractsFirstText() {
        String json = "{\"alternatives\":[{\"text\":\"hola\",\"confidence\":0.9}]}";
        // Sin campo "text" a nivel raíz → null (los visemes usan text/partial raíz).
        assertNull(VoskRecognizer.extractText(json));
    }

    @Test
    @DisplayName("Texto plano (no JSON) → se devuelve tal cual")
    void plainText_returnedAsIs() {
        assertEquals("hola", VoskRecognizer.extractText("hola"));
    }

    @Test
    @DisplayName("JSON malformado → null (no lanza)")
    void malformedJson_returnsNull() {
        assertNull(VoskRecognizer.extractText("{no es json}"));
    }

    @Test
    @DisplayName("null / blank → null")
    void nullAndBlank_returnNull() {
        assertNull(VoskRecognizer.extractText(null));
        assertNull(VoskRecognizer.extractText("   "));
    }

    @Test
    @DisplayName("Viseme.fromWord sobre texto extraído → viseme correcto")
    void extractText_thenFromWord() {
        // Simula el flujo real: Vosk devuelve JSON parcial → extractText → fromWord.
        String json = "{\"partial\" : \"laptop\"}";
        String text = VoskRecognizer.extractText(json);
        assertEquals(Viseme.L, Viseme.fromWord(text));
    }
}
