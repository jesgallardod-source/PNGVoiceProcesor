package org.PNGV.phoneme;

import java.util.ArrayList;
import java.util.List;

/**
 * Segmenta palabras del español en sílabas usando reglas simples de
 * acentuación. No es un segmentador lingüístico perfecto, pero es suficiente
 * para extraer la vocable (vocal) dominante de cada sílaba y mapearla a un
 * viseme.
 *
 * <p>Reglas simplificadas aplicadas:
 * <ul>
 *   <li>Diptongos (V+C+V) se mantienen juntos.</li>
 *   <li>Hiatos (vocales fuertes + débiles) se separan.</li>
 *   <li>Consonantes al inicio de sílaba se agrupan según las reglas
 *       (sonoras → sílaba anterior; sordas → sílaba posterior).</li>
 * </ul>
 */
public final class SyllableSplitter {

    private static final String STRONG_VOWELS = "aeiouáéíóú";
    private static final String WEAK_VOWELS = "iú";

    private SyllableSplitter() {
    }

    /**
     * Divide una palabra en sílabas.
     *
     * @param word palabra en minúsculas (sin tildes para el análisis, aunque se conservan)
     * @return lista de sílabas (nunca null, puede estar vacía)
     */
    public static List<String> split(String word) {
        List<String> result = new ArrayList<>();
        if (word == null || word.isEmpty()) {
            return result;
        }

        String w = word.toLowerCase();
        StringBuilder current = new StringBuilder();
        int n = w.length();

        for (int i = 0; i < n; i++) {
            char c = w.charAt(i);

            if (isVowel(c)) {
                // ¿Diéresis o vocal débil?
                char next = (i + 1 < n) ? w.charAt(i + 1) : 0;
                boolean isDiphthong = isVowel(c) && isVowel(next)
                        && (isWeak(c) || isWeak(next));

                if (!current.isEmpty() && isSyllableBreak(current, c, next)) {
                    result.add(current.toString());
                    current.setLength(0);
                }

                current.append(c);

                // Si es diptongo, agrega también la siguiente vocal
                if (isDiphthong) {
                    current.append(next);
                    i++;
                }
            } else {
                // Consonante: decide si se une a la sílaba anterior o inicia una nueva.
                if (current.isEmpty()) {
                    // Empieza la palabra: agrupa consonantes iniciales.
                    current.append(c);
                    // Consonantes que pueden agruparse (p. ej. "br", "pl", "str").
                    while (i + 1 < n && isConsonant(w.charAt(i + 1))
                            && canCluster(c, w.charAt(i + 1))) {
                        i++;
                        current.append(w.charAt(i));
                    }
                } else if (isVowel(current.charAt(current.length() - 1))) {
                    // La sílaba actual termina en vocal → la consonante inicia nueva sílaba.
                    result.add(current.toString());
                    current.setLength(0);
                    current.append(c);
                } else {
                    // La sílaba actual termina en consonante → agrega la consonante
                    // (clúster final, p. e. "nd", "nt").
                    current.append(c);
                }
            }
        }

        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * Extrae el viseme dominante de una palabra.
     *
     * <p>Heurística simple: devuelve el viseme de la <b>última vocal fuerte</b>
     * (a, e, o) encontrada en la palabra. Si no hay ninguna, usa la primera
     * vocal débil (i, u). Si tampoco, {@link Viseme#NEUTRAL}.
     *
     * @param word palabra arbitraria
     * @return viseme a mostrar, o {@link Viseme#NEUTRAL} si no aplica
     */
    public static Viseme dominantViseme(String word) {
        if (word == null || word.isEmpty()) {
            return Viseme.NEUTRAL;
        }
        String w = word.toLowerCase();
        Viseme lastStrong = Viseme.NEUTRAL;
        Viseme firstWeak = Viseme.NEUTRAL;

        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            Viseme v = Viseme.fromPhoneme(String.valueOf(c));
            if (v == Viseme.NEUTRAL) {
                continue;
            }
            if (isStrongVowel(c)) {
                lastStrong = v; // la última vocal fuerte gana
            } else if (firstWeak == Viseme.NEUTRAL) {
                firstWeak = v;  // recuerda la primera débil como respaldo
            }
        }

        return lastStrong != Viseme.NEUTRAL ? lastStrong : firstWeak;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean isVowel(char c) {
        return "aeiouáéíóú".indexOf(c) >= 0;
    }

    private static boolean isWeak(char c) {
        return "iú".indexOf(c) >= 0;
    }

    private static boolean isStrongVowel(char c) {
        return "aeoáéó".indexOf(c) >= 0;
    }

    private static boolean isConsonant(char c) {
        return Character.isLetter(c) && !isVowel(c);
    }

    /**
     * Decide si hay que cortar la sílaba antes de {@code c}.
     */
    private static boolean isSyllableBreak(StringBuilder current, char c, char next) {
        // Si la sílaba actual ya termina en vocal fuerte y la siguiente es
        // vocal fuerte → hiato → corte.
        char last = current.charAt(current.length() - 1);
        if (isStrongVowel(last) && isStrongVowel(c)) {
            return true;
        }
        return false;
    }

    /**
     * ¿Pueden agruparse las consonantes {@code a} + {@code b} al inicio de sílaba?
     * Regla muy simplificada.
     */
    private static boolean canCluster(char a, char b) {
        if (a == 's') {
            return true; // "str", "spr", "scr"
        }
        // Grupo sonoro + oclusiva (br, pr, bl, pl, dr, tr, gr, cr, fr).
        return "brbl".indexOf(a) >= 0 || "prpl".indexOf(a) >= 0
                || "dr".indexOf(a) >= 0 || "trgrcrfr".indexOf(a) >= 0;
    }
}
