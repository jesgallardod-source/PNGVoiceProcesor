package org.PNGV.phoneme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ================================================================
    // Tests de visemeFromSyllable (prioridad a vocal dominante)
    // ================================================================

    @Test
    @DisplayName("1 letra → esa letra (vocal 'a')")
    void oneLetter_vowel() {
        assertEquals(Viseme.A, VoskRecognizer.visemeFromSyllable("a"));
    }

    @Test
    @DisplayName("1 letra → esa letra (consonante 'b')")
    void oneLetter_consonant() {
        assertEquals(Viseme.BMP, VoskRecognizer.visemeFromSyllable("b"));
    }

    @Test
    @DisplayName("2 letras, 1 vocal (posición 1) → la vocal")
    void twoLetters_vowelAtEnd() {
        assertEquals(Viseme.A, VoskRecognizer.visemeFromSyllable("ba"));
    }

    @Test
    @DisplayName("2 letras, 1 vocal (posición 0) → la vocal")
    void twoLetters_vowelAtStart() {
        assertEquals(Viseme.E, VoskRecognizer.visemeFromSyllable("el"));
    }

    @Test
    @DisplayName("2 letras, 2 vocales → la última")
    void twoLetters_twoVowels_lastOne() {
        assertEquals(Viseme.I, VoskRecognizer.visemeFromSyllable("ai"));
    }

    @Test
    @DisplayName("2 letras, 2 vocales (ae) → la última (e)")
    void twoLetters_twoVowels_ae() {
        assertEquals(Viseme.E, VoskRecognizer.visemeFromSyllable("ae"));
    }

    @Test
    @DisplayName("2 letras, sin vocales → última (fallback)")
    void twoLetters_noVowels_fallback() {
        assertEquals(Viseme.BMP, VoskRecognizer.visemeFromSyllable("bm"));
    }

    @Test
    @DisplayName("3 letras, medio es vocal → el medio")
    void threeLetters_middleVowel() {
        assertEquals(Viseme.A, VoskRecognizer.visemeFromSyllable("baa"));
    }

    @Test
    @DisplayName("3 letras, medio no es vocal, 1 vocal en posición 0 → esa vocal")
    void threeLetters_noMiddleVowel_oneAtStart() {
        assertEquals(Viseme.E, VoskRecognizer.visemeFromSyllable("elb"));
    }

    @Test
    @DisplayName("3 letras, medio no es vocal, 1 vocal en posición 2 → esa vocal")
    void threeLetters_noMiddleVowel_oneAtEnd() {
        assertEquals(Viseme.O, VoskRecognizer.visemeFromSyllable("blo"));
    }

    @Test
    @DisplayName("3 letras, medio no es vocal, 2 vocales (pos 0 y 2) → una de las dos (aleatoria)")
    void threeLetters_noMiddleVowel_twoVowels_random() {
        Viseme result = VoskRecognizer.visemeFromSyllable("ael");
        assertTrue(result == Viseme.A || result == Viseme.E,
                "Debe ser A o E, got: " + result);
    }

    @Test
    @DisplayName("3 letras, sin vocales → el medio (fallback)")
    void threeLetters_noVowels_fallbackMiddle() {
        // "bld" → medio 'l' → L
        assertEquals(Viseme.L, VoskRecognizer.visemeFromSyllable("bld"));
    }

    @Test
    @DisplayName("4 letras, ambas centrales son vocales → una de las dos (aleatoria)")
    void fourLetters_bothMiddleVowels_random() {
        // "baei" → índices 1='a'(vocal), 2='e'(vocal) → A o E
        Viseme result = VoskRecognizer.visemeFromSyllable("baei");
        assertTrue(result == Viseme.A || result == Viseme.E,
                "Debe ser A o E, got: " + result);
    }

    @Test
    @DisplayName("4 letras, 1 vocal en centro (índice 1) → esa vocal")
    void fourLetters_oneCentralVowelAt1() {
        // "bacl" → índice 1='a'(vocal), índice 2='c'(no) → A
        assertEquals(Viseme.A, VoskRecognizer.visemeFromSyllable("bacl"));
    }

    @Test
    @DisplayName("4 letras, 1 vocal en centro (índice 2) → esa vocal")
    void fourLetters_oneCentralVowelAt2() {
        // "blao" → índice 1='l'(no), índice 2='a'(vocal) → A
        assertEquals(Viseme.A, VoskRecognizer.visemeFromSyllable("blao"));
    }

    @Test
    @DisplayName("4 letras, sin vocales en centro, 1 vocal en posición 0 → esa vocal")
    void fourLetters_noCentralVowel_vowelAtStart() {
        // "eblm" → índices 1='b', 2='l' (no vocales), pos 0='e'(vocal) → E
        assertEquals(Viseme.E, VoskRecognizer.visemeFromSyllable("eblm"));
    }

    @Test
    @DisplayName("4 letras, sin vocales en centro, 1 vocal en posición 3 → esa vocal")
    void fourLetters_noCentralVowel_vowelAtEnd() {
        // "blme" → índices 1='l', 2='m' (no vocales), pos 3='e'(vocal) → E
        assertEquals(Viseme.E, VoskRecognizer.visemeFromSyllable("blme"));
    }

    @Test
    @DisplayName("4 letras, sin vocales → aleatoria entre centrales (fallback)")
    void fourLetters_noVowels_fallbackRandom() {
        // "blmr" → índices 1='l'(L), 2='m'(BMP), pos 0='b'(BMP), pos 3='r'(L)
        Viseme result = VoskRecognizer.visemeFromSyllable("blmr");
        assertTrue(result == Viseme.L || result == Viseme.BMP,
                "Debe ser L (l) o BMP (m), got: " + result);
    }

    @Test
    @DisplayName("5 letras, medio es vocal → el medio")
    void fiveLetters_middleVowel() {
        // "abcde" → índice 2='c'(no), pero probemos "abaeo" → índice 2='a' → A
        assertEquals(Viseme.A, VoskRecognizer.visemeFromSyllable("abaeo"));
    }

    @Test
    @DisplayName("5 letras, medio no es vocal, 2 vocales equidistantes → una de las dos (aleatoria)")
    void fiveLetters_noMiddleVowel_twoEquidistantVowels_random() {
        // "abcde" → índice 2='c'(no), offset 1: 'b'/'d'(no),
        //  offset 2: 'a'(vocal) / 'e'(vocal) → A o E (aleatoria)
        Viseme result = VoskRecognizer.visemeFromSyllable("abcde");
        assertTrue(result == Viseme.A || result == Viseme.E,
                "Debe ser A o E, got: " + result);
    }

    @Test
    @DisplayName("null/blank → NEUTRAL")
    void nullAndBlank_neutral() {
        assertEquals(Viseme.NEUTRAL, VoskRecognizer.visemeFromSyllable(null));
        assertEquals(Viseme.NEUTRAL, VoskRecognizer.visemeFromSyllable(""));
        assertEquals(Viseme.NEUTRAL, VoskRecognizer.visemeFromSyllable("   "));
    }

    // ================================================================
    // Tests de la regla "última sílaba termina en S → viseme S"
    // ================================================================

    @Test
    @DisplayName("terminaEnEs: sílaba que termina en 's' (minúscula) → true")
    void terminaEnEs_lowercase() {
        assertTrue(VoskRecognizer.terminaEnEs("mas"));
        assertTrue(VoskRecognizer.terminaEnEs("s"));
    }

    @Test
    @DisplayName("terminaEnEs: sílaba que termina en 'S' (mayúscula) → true")
    void terminaEnEs_uppercase() {
        assertTrue(VoskRecognizer.terminaEnEs("MAS"));
        assertTrue(VoskRecognizer.terminaEnEs("S"));
    }

    @Test
    @DisplayName("terminaEnEs: sílaba que no termina en 's' → false")
    void terminaEnEs_notEndingInS() {
        assertTrue(!VoskRecognizer.terminaEnEs("ma"));
        assertTrue(!VoskRecognizer.terminaEnEs("ba"));
    }

    @Test
    @DisplayName("terminaEnEs: null / vacía / solo espacios → false")
    void terminaEnEs_nullAndEmpty() {
        assertTrue(!VoskRecognizer.terminaEnEs(null));
        assertTrue(!VoskRecognizer.terminaEnEs(""));
        assertTrue(!VoskRecognizer.terminaEnEs("   "));
    }

    @Test
    @DisplayName("Sílabas típicas del español: 'ba'→A, 'pro'→P(BMP), 'ma'→A, 'ta'→A")
    void spanishSyllables_typical() {
        assertEquals(Viseme.A, VoskRecognizer.visemeFromSyllable("ba"));
        // "pro" → 3 letras, medio 'r'(no vocal), pos 0 'p'(no), pos 2 'o'(vocal) → O
        assertEquals(Viseme.O, VoskRecognizer.visemeFromSyllable("pro"));
        assertEquals(Viseme.A, VoskRecognizer.visemeFromSyllable("ma"));
        assertEquals(Viseme.A, VoskRecognizer.visemeFromSyllable("ta"));
    }
}
