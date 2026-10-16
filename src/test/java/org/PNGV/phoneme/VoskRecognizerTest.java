package org.PNGV.phoneme;

import org.PNGV.phoneme.VoskRecognizer.WordTiming;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pruebas de {@link VoskRecognizer#extractText(String)} y
 * {@link VoskRecognizer#extractTextBest(String)}: las funciones que
 * extraen el texto reconocible del JSON que devuelve Vosk
 * ({@code getResult()} / {@code getPartialResult()}).
 *
 * <p>{@code extractText} es el método legado (nbest=1): extrae
 * {@code text} o {@code partial} del JSON raíz.
 *
 * <p>{@code extractTextBest} (Fase 2.1 Opción B) es el método mejorado:
 * además de los formatos anteriores, maneja el campo
 * {@code "alternatives": [...]} y elige la alternativa de mayor
 * confianza.
 */
class VoskRecognizerTest {

    // ==================== extractText (legado, nbest=1) ====================

    @Test
    @DisplayName("extractText: JSON final con campo 'text' → extrae el texto")
    void finalResult_extractsText() {
        assertEquals("hola mundo",
                VoskRecognizer.extractText("{\"text\" : \"hola mundo\"}"));
    }

    @Test
    @DisplayName("extractText: JSON parcial con campo 'partial' → extrae el texto")
    void partialResult_extractsPartial() {
        assertEquals("hola mun",
                VoskRecognizer.extractText("{\"partial\" : \"hola mun\"}"));
    }

    @Test
    @DisplayName("extractText: JSON final con texto vacío → null")
    void finalResult_emptyText_returnsNull() {
        assertNull(VoskRecognizer.extractText("{\"text\" : \"\"}"));
    }

    @Test
    @DisplayName("extractText: JSON parcial vacío → null")
    void partialResult_emptyPartial_returnsNull() {
        assertNull(VoskRecognizer.extractText("{\"partial\" : \"\"}"));
    }

    @Test
    @DisplayName("extractText: JSON con alternativas → null (no lo maneja, ver extractTextBest)")
    void alternatives_notHandledByExtractText() {
        String json = "{\"alternatives\":[{\"text\":\"hola\",\"confidence\":0.9}]}";
        // extractText (legado) no conoce el formato "alternatives" → null.
        assertNull(VoskRecognizer.extractText(json));
    }

    @Test
    @DisplayName("extractText: Texto plano (no JSON) → se devuelve tal cual")
    void plainText_returnedAsIs() {
        assertEquals("hola", VoskRecognizer.extractText("hola"));
    }

    @Test
    @DisplayName("extractText: JSON malformado → null (no lanza)")
    void malformedJson_returnsNull() {
        assertNull(VoskRecognizer.extractText("{no es json}"));
    }

    @Test
    @DisplayName("extractText: null / blank → null")
    void nullAndBlank_returnNull() {
        assertNull(VoskRecognizer.extractText(null));
        assertNull(VoskRecognizer.extractText("   "));
    }

    @Test
    @DisplayName("extractText: Viseme.fromWord sobre texto extraído → viseme correcto")
    void extractText_thenFromWord() {
        String json = "{\"partial\" : \"laptop\"}";
        String text = VoskRecognizer.extractText(json);
        assertEquals(Viseme.L, Viseme.fromWord(text));
    }

    // ==================== extractTextBest (Fase 2.1, nbest>1) ====================

    @Nested
    @DisplayName("extractTextBest")
    class ExtractTextBestTests {

        @Test
        @DisplayName("JSON final con campo 'text' (nbest=1) → extrae el texto")
        void finalResult_extractsText() {
            assertEquals("hola mundo",
                    VoskRecognizer.extractTextBest("{\"text\" : \"hola mundo\"}"));
        }

        @Test
        @DisplayName("JSON parcial con campo 'partial' (nbest=1) → extrae el texto")
        void partialResult_extractsPartial() {
            assertEquals("hola mun",
                    VoskRecognizer.extractTextBest("{\"partial\" : \"hola mun\"}"));
        }

        @Test
        @DisplayName("Alternativas: elige la de MAYOR confianza")
        void alternatives_picksHighestConfidence() {
            String json = """
                    {
                      "alternatives": [
                        {"text": "ola mundo",  "confidence": 0.45},
                        {"text": "hola mundo", "confidence": 0.92},
                        {"text": "a lo mundo", "confidence": 0.30}
                      ]
                    }
                    """;
            assertEquals("hola mundo", VoskRecognizer.extractTextBest(json));
        }

        @Test
        @DisplayName("Alternativas parciales: elige la de MAYOR confianza")
        void alternativesPartial_picksHighestConfidence() {
            String json = """
                    {
                      "alternatives": [
                        {"partial": "mun",    "confidence": 0.60},
                        {"partial": "mundo",  "confidence": 0.85}
                      ]
                    }
                    """;
            assertEquals("mundo", VoskRecognizer.extractTextBest(json));
        }

        @Test
        @DisplayName("Alternativas sin confianza: elige la primera")
        void alternativesNoConfidence_picksFirst() {
            String json = """
                    {
                      "alternatives": [
                        {"text": "hola mundo"},
                        {"text": "ola mundo"}
                      ]
                    }
                    """;
            assertEquals("hola mundo", VoskRecognizer.extractTextBest(json));
        }

        @Test
        @DisplayName("Alternativas vacías → null")
        void alternativesEmpty_returnsNull() {
            String json = "{\"alternatives\":[]}";
            assertNull(VoskRecognizer.extractTextBest(json));
        }

        @Test
        @DisplayName("Alternativas con texto vacío en todas → null")
        void alternativesAllEmpty_returnsNull() {
            String json = """
                    {
                      "alternatives": [
                        {"text": "", "confidence": 0.9},
                        {"text": "  ", "confidence": 0.8}
                      ]
                    }
                    """;
            assertNull(VoskRecognizer.extractTextBest(json));
        }

        @Test
        @DisplayName("Alternativas: mezcla de 'text' y 'partial' → elige la de mayor conf")
        void alternativesMixedTextPartial() {
            String json = """
                    {
                      "alternatives": [
                        {"text": "hola",    "confidence": 0.50},
                        {"partial": "hola mundo", "confidence": 0.90}
                      ]
                    }
                    """;
            assertEquals("hola mundo", VoskRecognizer.extractTextBest(json));
        }

        @Test
        @DisplayName("Alternativas: una sin confidence → usa la primera con texto")
        void alternativesPartialConfidence_mixed() {
            // Cuando alguna alternativa tiene confidence, se usa confidence
            // para todas las que lo tengan; las que no lo tengan se ignoran
            // si hay alguna con conf.
            String json = """
                    {
                      "alternatives": [
                        {"text": "hola mundo"},
                        {"text": "ola mundo", "confidence": 0.80}
                      ]
                    }
                    """;
            // La primera no tiene confidence, la segunda sí →
            // bestConfidence empieza en -1, la segunda gana con 0.80
            assertEquals("ola mundo", VoskRecognizer.extractTextBest(json));
        }

        @Test
        @DisplayName("Texto plano (no JSON) → se devuelve tal cual")
        void plainText_returnedAsIs() {
            assertEquals("hola", VoskRecognizer.extractTextBest("hola"));
        }

        @Test
        @DisplayName("JSON malformado → null (no lanza)")
        void malformedJson_returnsNull() {
            assertNull(VoskRecognizer.extractTextBest("{no es json}"));
        }

        @Test
        @DisplayName("null / blank → null")
        void nullAndBlank_returnNull() {
            assertNull(VoskRecognizer.extractTextBest(null));
            assertNull(VoskRecognizer.extractTextBest("   "));
        }

        @Test
        @DisplayName("Viseme.fromWord sobre mejor alternativa → viseme correcto")
        void extractTextBest_thenFromWord() {
            // Simula el flujo real con nbest: Vosk devuelve alternativas →
            // extractTextBest elige la mejor → fromWord mapea al viseme.
            String json = """
                    {
                      "alternatives": [
                        {"partial": "laptop",    "confidence": 0.95},
                        {"partial": "ra pt",     "confidence": 0.40}
                      ]
                    }
                    """;
            String text = VoskRecognizer.extractTextBest(json);
            assertEquals("laptop", text);
            assertEquals(Viseme.L, Viseme.fromWord(text));
        }
    }

    // ==================== parseWords (Fase 2.2, timestamps) ====================

    @Nested
    @DisplayName("parseWords (Fase 2.2)")
    class ParseWordsTests {

        @Test
        @DisplayName("JSON parcial con 'result' array → extrae palabras con timestamps")
        void partialResult_withWordsArray() {
            String json = """
                    {
                      "partial": "hola mun",
                      "result": [
                        {"word": "hola", "start": 0.12, "end": 0.45, "conf": 0.92},
                        {"word": "mun",  "start": 0.61, "end": 0.78, "conf": 0.71}
                      ]
                    }
                    """;
            List<WordTiming> words = VoskRecognizer.parseWords(json);
            assertEquals(2, words.size());

            WordTiming w0 = words.get(0);
            assertEquals("hola", w0.word());
            assertEquals(0.12, w0.start(), 0.001);
            assertEquals(0.45, w0.end(), 0.001);
            assertEquals(0.92, w0.conf(), 0.001);

            WordTiming w1 = words.get(1);
            assertEquals("mun", w1.word());
            assertEquals(0.61, w1.start(), 0.001);
            assertEquals(0.78, w1.end(), 0.001);
            assertEquals(0.71, w1.conf(), 0.001);
        }

        @Test
        @DisplayName("JSON final con 'result' array → extrae palabras con timestamps")
        void finalResult_withWordsArray() {
            String json = """
                    {
                      "text": "hola mundo",
                      "result": [
                        {"word": "hola",  "start": 0.12, "end": 0.45, "conf": 0.92},
                        {"word": "mundo", "start": 0.61, "end": 0.95, "conf": 0.88}
                      ]
                    }
                    """;
            List<WordTiming> words = VoskRecognizer.parseWords(json);
            assertEquals(2, words.size());
            assertEquals("hola", words.get(0).word());
            assertEquals("mundo", words.get(1).word());
        }

        @Test
        @DisplayName("JSON con campo 'tokens' (formato alternativo) → extrae palabras")
        void tokensField_worksAsFallback() {
            String json = """
                    {
                      "partial": "hola",
                      "tokens": [
                        {"word": "hola", "start": 0.1, "end": 0.5, "conf": 0.9}
                      ]
                    }
                    """;
            List<WordTiming> words = VoskRecognizer.parseWords(json);
            assertEquals(1, words.size());
            assertEquals("hola", words.get(0).word());
        }

        @Test
        @DisplayName("JSON con alternativas (nbest>1) → elige la mejor y extrae sus tokens")
        void alternatives_extractsTokensFromBest() {
            String json = """
                    {
                      "alternatives": [
                        {"text": "ola mundo", "confidence": 0.45,
                         "tokens": [{"word":"ola","start":0.1,"end":0.4,"conf":0.8}]},
                        {"text": "hola mundo", "confidence": 0.92,
                         "tokens": [
                           {"word":"hola","start":0.12,"end":0.45,"conf":0.92},
                           {"word":"mundo","start":0.61,"end":0.95,"conf":0.88}
                         ]}
                      ]
                    }
                    """;
            List<WordTiming> words = VoskRecognizer.parseWords(json);
            assertEquals(2, words.size());
            assertEquals("hola", words.get(0).word());
            assertEquals("mundo", words.get(1).word());
        }

        @Test
        @DisplayName("JSON sin array de palabras → lista vacía")
        void noWordsArray_returnsEmpty() {
            String json = "{\"partial\": \"hola\"}";
            List<WordTiming> words = VoskRecognizer.parseWords(json);
            assertNotNull(words);
            assertTrue(words.isEmpty());
        }

        @Test
        @DisplayName("JSON con 'result' vacío → lista vacía")
        void emptyResultArray_returnsEmpty() {
            String json = "{\"partial\": \"hola\", \"result\": []}";
            List<WordTiming> words = VoskRecognizer.parseWords(json);
            assertNotNull(words);
            assertTrue(words.isEmpty());
        }

        @Test
        @DisplayName("null / blank → lista vacía")
        void nullAndBlank_returnsEmpty() {
            assertNotNull(VoskRecognizer.parseWords(null));
            assertTrue(VoskRecognizer.parseWords(null).isEmpty());

            assertNotNull(VoskRecognizer.parseWords("   "));
            assertTrue(VoskRecognizer.parseWords("   ").isEmpty());
        }

        @Test
        @DisplayName("Texto plano (no JSON) → lista vacía")
        void plainText_returnsEmpty() {
            List<WordTiming> words = VoskRecognizer.parseWords("hola mundo");
            assertNotNull(words);
            assertTrue(words.isEmpty());
        }

        @Test
        @DisplayName("JSON malformado → lista vacía (no lanza)")
        void malformedJson_returnsEmpty() {
            List<WordTiming> words = VoskRecognizer.parseWords("{no es json}");
            assertNotNull(words);
            assertTrue(words.isEmpty());
        }

        @Test
        @DisplayName("Palabra con word vacío → se omite")
        void emptyWord_skipped() {
            String json = """
                    {
                      "partial": "hola",
                      "result": [
                        {"word": "", "start": 0.1, "end": 0.2, "conf": 0.9},
                        {"word": "hola", "start": 0.1, "end": 0.5, "conf": 0.9}
                      ]
                    }
                    """;
            List<WordTiming> words = VoskRecognizer.parseWords(json);
            assertEquals(1, words.size());
            assertEquals("hola", words.get(0).word());
        }

        @Test
        @DisplayName("Palabra sin campos start/end/conf → valores por defecto")
        void missingFields_defaultsApplied() {
            String json = """
                    {
                      "partial": "hola",
                      "result": [
                        {"word": "hola"}
                      ]
                    }
                    """;
            List<WordTiming> words = VoskRecognizer.parseWords(json);
            assertEquals(1, words.size());
            WordTiming w = words.get(0);
            assertEquals("hola", w.word());
            assertEquals(0.0, w.start(), 0.001);
            assertEquals(0.0, w.end(), 0.001);
            assertEquals(-1.0, w.conf(), 0.001);
        }
    }

    // ==================== WordTiming record (Fase 2.2) ====================

    @Nested
    @DisplayName("WordTiming")
    class WordTimingTests {

        @Test
        @DisplayName("duration() = end - start")
        void duration_correct() {
            WordTiming w = new WordTiming("hola", 0.12, 0.45, 0.92);
            assertEquals(0.33, w.duration(), 0.001);
        }

        @Test
        @DisplayName("duration() nunca es negativo (start > end)")
        void duration_neverNegative() {
            WordTiming w = new WordTiming("hola", 0.5, 0.2, 0.9);
            assertEquals(0.0, w.duration(), 0.001);
        }

        @Test
        @DisplayName("durationMs() = duration * 1000")
        void durationMs_correct() {
            WordTiming w = new WordTiming("hola", 0.12, 0.45, 0.92);
            assertEquals(330L, w.durationMs());
        }

        @Test
        @DisplayName("durationMs() con valores enteros")
        void durationMs_integerValues() {
            WordTiming w = new WordTiming("mundo", 1.0, 2.5, 0.8);
            assertEquals(1500L, w.durationMs());
        }
    }

    // ==================== obtenerVisemeDeSilabaDominante (Fase: sílaba tónica) ====================

    @Nested
    @DisplayName("obtenerVisemeDeSilabaDominante (Fase: sílaba tónica)")
    class SilabaDominanteVisemeTests {

        @Test
        @DisplayName("Sílaba 'laptop' → primera letra 'l' → viseme L")
        void laptop_firstLetter_l() {
            // dividirEnSilabas("laptop") = ["laptop"] → única sílaba.
            assertEquals(Viseme.L,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante("laptop"));
        }

        @Test
        @DisplayName("Sílaba 'mundo' → primera letra 'm' → viseme BMP")
        void mundo_firstLetter_m() {
            assertEquals(Viseme.BMP,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante("mundo"));
        }

        @Test
        @DisplayName("Sílaba 'la' → primera letra 'l' → viseme L")
        void la_firstLetter_l() {
            assertEquals(Viseme.L,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante("la"));
        }

        @Test
        @DisplayName("Sílaba 'ca' → primera letra 'c' → viseme S (grupo S incluye c)")
        void ca_firstLetter_c() {
            assertEquals(Viseme.S,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante("ca"));
        }

        @Test
        @DisplayName("Sílaba 'pa' → primera letra 'p' → viseme BMP")
        void pa_firstLetter_p() {
            assertEquals(Viseme.BMP,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante("pa"));
        }

        @Test
        @DisplayName("Sílaba 'fa' → primera letra 'f' → viseme FV")
        void fa_firstLetter_f() {
            assertEquals(Viseme.FV,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante("fa"));
        }

        @Test
        @DisplayName("Sílaba 'sa' → primera letra 's' → viseme S")
        void sa_firstLetter_s() {
            assertEquals(Viseme.S,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante("sa"));
        }

        @Test
        @DisplayName("Sílaba 'a' (solo vocal) → viseme A")
        void solaVocal_a() {
            assertEquals(Viseme.A,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante("a"));
        }

        @Test
        @DisplayName("Sílaba 'e' (solo vocal) → viseme E")
        void solaVocal_e() {
            assertEquals(Viseme.E,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante("e"));
        }

        @Test
        @DisplayName("Sílaba 'o' (solo vocal) → viseme O")
        void solaVocal_o() {
            assertEquals(Viseme.O,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante("o"));
        }

        @Test
        @DisplayName("Sílaba 'u' (solo vocal) → viseme U")
        void solaVocal_u() {
            assertEquals(Viseme.U,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante("u"));
        }

        @Test
        @DisplayName("Sílaba 'í' (vocal con tilde) → viseme I")
        void solaVocalConTilde_i() {
            assertEquals(Viseme.I,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante("í"));
        }

        @Test
        @DisplayName("Sílaba 'ñ' (solo ñ) → viseme NEUTRAL (ñ no está en el mapa)")
        void solaEnye_neutral() {
            assertEquals(Viseme.NEUTRAL,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante("ñ"));
        }

        @Test
        @DisplayName("Sílaba con signos no alfabéticos al inicio → salta a la primera letra")
        void signosAlInicio_saltaAprimeraLetra() {
            // La primera letra mapeable es 'm'.
            assertEquals(Viseme.BMP,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante("123mundo"));
        }

        @Test
        @DisplayName("Sílaba vacía / null → NEUTRAL")
        void emptyOrNull_neutral() {
            assertEquals(Viseme.NEUTRAL,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante(""));
            assertEquals(Viseme.NEUTRAL,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante(null));
        }

        @Test
        @DisplayName("Solo caracteres no alfabéticos → NEUTRAL")
        void soloSignos_neutral() {
            assertEquals(Viseme.NEUTRAL,
                    VoskRecognizer.obtenerVisemeDeSilabaDominante("123 !@#"));
        }
    }

    // ==================== obtenerVisemeLetraAleatoria (Fase: variación) ====================

    @Nested
    @DisplayName("obtenerVisemeLetraAleatoria (Fase: variación)")
    class LetraAleatoriaVisemeTests {

        @Test
        @DisplayName("Texto 'la' con Random fijo(0) → letra determinística → viseme válido")
        void la_randomFijo_viseVálido() {
            // "la" → silabaDominante "la" → letras [l, a] → una de las dos.
            java.util.Random r = new java.util.Random(0);
            Viseme v = VoskRecognizer.obtenerVisemeLetraAleatoria("la", r);
            assertTrue(v == Viseme.L || v == Viseme.A,
                    "Se esperaba L o A, se obtuvo " + v);
        }

        @Test
        @DisplayName("Texto 'pa' con Random fijo → una de [p, a] → BMP o A")
        void pa_randomFijo_viseVálido() {
            java.util.Random r = new java.util.Random(42);
            Viseme v = VoskRecognizer.obtenerVisemeLetraAleatoria("pa", r);
            assertTrue(v == Viseme.BMP || v == Viseme.A,
                    "Se esperaba BMP o A, se obtuvo " + v);
        }

        @Test
        @DisplayName("Texto 'mundo' con Random fijo → una de [m,u,n,d,o] → viseme del grupo")
        void mundo_randomFijo_viseDelGrupo() {
            java.util.Random r = new java.util.Random(7);
            Viseme v = VoskRecognizer.obtenerVisemeLetraAleatoria("mundo", r);
            // m→BMP, u→U, n→S, d→S, o→O. Cualquiera de estos es válido.
            assertTrue(v == Viseme.BMP || v == Viseme.U || v == Viseme.S || v == Viseme.O,
                    "Se esperaba BMP/U/S/O, se obtuvo " + v);
        }

        @Test
        @DisplayName("Texto vacío / null / blank → NEUTRAL")
        void emptyOrNull_neutral() {
            assertEquals(Viseme.NEUTRAL, VoskRecognizer.obtenerVisemeLetraAleatoria(""));
            assertEquals(Viseme.NEUTRAL, VoskRecognizer.obtenerVisemeLetraAleatoria(null));
            assertEquals(Viseme.NEUTRAL, VoskRecognizer.obtenerVisemeLetraAleatoria("   "));
        }

        @Test
        @DisplayName("Texto sin letras (solo números) → NEUTRAL")
        void soloNumeros_neutral() {
            assertEquals(Viseme.NEUTRAL,
                    VoskRecognizer.obtenerVisemeLetraAleatoria("12345", new java.util.Random()));
        }

        @Test
        @DisplayName("Random null → NEUTRAL (defensivo)")
        void randomNull_neutral() {
            assertEquals(Viseme.NEUTRAL,
                    VoskRecognizer.obtenerVisemeLetraAleatoria("hola", null));
        }

        @Test
        @DisplayName("Sobrecarga sin Random (new Random interno) → viseme no null")
        void sobrecargaSinRandom_viseNoNull() {
            Viseme v = VoskRecognizer.obtenerVisemeLetraAleatoria("hola");
            assertNotNull(v);
        }

        @Test
        @DisplayName("Múltiples elecciones sobre 'hola' → todos los visemes válidos")
        void hola_multiplesElecciones_viseVálido() {
            // "hola" → silabaDominante (depende de dividirEnSilabas) →
            // las letras posibles son h, o, l, a → visemes S, O, L, A.
            java.util.Random r = new java.util.Random(12345);
            for (int i = 0; i < 50; i++) {
                Viseme v = VoskRecognizer.obtenerVisemeLetraAleatoria("hola", r);
                assertTrue(v == Viseme.S || v == Viseme.O || v == Viseme.L || v == Viseme.A,
                        "Viseme inesperado: " + v);
            }
        }
    }

    // ==================== formatWords (Fase 2.2, logging) ====================

    @Nested
    @DisplayName("formatWords")
    class FormatWordsTests {

        @Test
        @DisplayName("Lista de 2 palabras → formato legible")
        void twoWords_formatted() {
            List<WordTiming> words = List.of(
                    new WordTiming("hola", 0.12, 0.45, 0.92),
                    new WordTiming("mun", 0.61, 0.78, 0.71)
            );
            String formatted = VoskRecognizer.formatWords(words);
            assertEquals("[hola(0.12→0.45, 0.92), mun(0.61→0.78, 0.71)]", formatted);
        }

        @Test
        @DisplayName("Lista vacía → '[]'")
        void emptyList_emptyBrackets() {
            assertEquals("[]", VoskRecognizer.formatWords(List.of()));
        }

        @Test
        @DisplayName("Palabra sin confianza (conf=-1) → muestra '?'")
        void noConfidence_showsQuestionMark() {
            List<WordTiming> words = List.of(
                    new WordTiming("hola", 0.1, 0.5, -1.0)
            );
            String formatted = VoskRecognizer.formatWords(words);
            assertTrue(formatted.contains("?"));
            assertFalse(formatted.contains("-1"));
        }

        @Test
        @DisplayName("Lista de 1 palabra → sin coma extra")
        void singleWord_noExtraComma() {
            List<WordTiming> words = List.of(
                    new WordTiming("hola", 0.1, 0.5, 0.9)
            );
            String formatted = VoskRecognizer.formatWords(words);
            assertEquals("[hola(0.10→0.50, 0.90)]", formatted);
        }
    }
}
