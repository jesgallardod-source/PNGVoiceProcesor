package org.PNGV.phoneme;

import java.util.ArrayList;
import java.util.List;

/**
 * Decide el viseme dominante de una palabra.
 *
 * <p>En la nueva estructura de <b>10 visemes</b> cada vocal (A, E, I, O, U)
 * tiene su propia forma de boca. Para elegir cuál se muestra se usa la heurística
 * de <b>apertura</b>: la <b>vocal más abierta</b> presente en la palabra domina,
 * porque es la que más mueve la boca y la que más se distingue a simple vista.
 *
 * <p>Orden de apertura (de más abierta a menos):
 * <pre>
 *   A  &gt;  O / E  &gt;  U / I
 * </pre>
 *
 * <p>Si la palabra no contiene ninguna vocal, se devuelve {@link Viseme#NEUTRAL}.
 *
 * <p>El método {@link #split(String)} conserva el antiguo segmentador silábico
 * (por compatibilidad), aunque {@link #dominantViseme(String)} ya no lo necesita.
 */
public final class SyllableSplitter {

    private SyllableSplitter() {
    }

    /**
     * Extrae el viseme dominante de una palabra.
     *
     * <p>Heurística: recorre la palabra y elige el viseme de la <b>vocal más
     * abierta</b> encontrada (A &gt; O/E &gt; U/I). Las consonantes no cuentan,
     * porque el movimiento visible de la boca al hablar lo marcan las vocales.
     *
     * @param word palabra arbitraria (p. ej. "hola", "programación")
     * @return viseme a mostrar, o {@link Viseme#NEUTRAL} si no hay vocales
     */
    public static Viseme dominantViseme(String word) {
        if (word == null || word.isEmpty()) {
            return Viseme.NEUTRAL;
        }
        Viseme best = Viseme.NEUTRAL;
        int bestOpenness = -1;

        for (int i = 0; i < word.length(); i++) {
            char c = Character.toLowerCase(word.charAt(i));
            if (!isVowel(c)) {
                continue;
            }
            Viseme v = Viseme.fromLetter(c);
            int openness = opennessOf(c);
            if (openness > bestOpenness) {
                bestOpenness = openness;
                best = v;
            }
        }
        return best;
    }

    /**
     * Grado de apertura de una vocal (mayor = más abierta).
     * <p>A es la más abierta; O y E son medias; U e I son cerradas.
     */
    private static int opennessOf(char c) {
        return switch (c) {
            case 'a', 'á' -> 5;
            case 'o', 'ó', 'e', 'é' -> 4;
            case 'u', 'ú', 'i', 'í' -> 3;
            default -> 0;
        };
    }

    private static boolean isVowel(char c) {
        return "aeiouáéíóú".indexOf(c) >= 0;
    }

    // ==================================================================
    // Segmentador silábico (compatibilidad)
    //
    // Se conserva por si algún código externo lo usa. La heurística de
    // apertura de dominantViseme() ya no depende de él.
    // ==================================================================

    /**
     * Divide una palabra en sílabas usando reglas simplificadas de acentuación
     * del español. No es un segmentador lingüístico perfecto.
     *
     * @param word palabra (se normaliza a minúsculas)
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
                char next = (i + 1 < n) ? w.charAt(i + 1) : 0;
                boolean isDiphthong = isVowel(c) && isVowel(next)
                        && (isWeak(c) || isWeak(next));

                if (!current.isEmpty() && isSyllableBreak(current, c)) {
                    result.add(current.toString());
                    current.setLength(0);
                }

                current.append(c);

                if (isDiphthong) {
                    current.append(next);
                    i++;
                }
            } else {
                if (current.isEmpty()) {
                    current.append(c);
                    while (i + 1 < n && isConsonant(w.charAt(i + 1))
                            && canCluster(c, w.charAt(i + 1))) {
                        i++;
                        current.append(w.charAt(i));
                    }
                } else if (isVowel(current.charAt(current.length() - 1))) {
                    result.add(current.toString());
                    current.setLength(0);
                    current.append(c);
                } else {
                    current.append(c);
                }
            }
        }

        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Helpers del segmentador
    // ------------------------------------------------------------------

    private static boolean isWeak(char c) {
        return "iú".indexOf(c) >= 0;
    }

    private static boolean isStrongVowel(char c) {
        return "aeoáéó".indexOf(c) >= 0;
    }

    private static boolean isConsonant(char c) {
        return Character.isLetter(c) && !isVowel(c);
    }

    private static boolean isSyllableBreak(StringBuilder current, char c) {
        char last = current.charAt(current.length() - 1);
        if (isStrongVowel(last) && isStrongVowel(c)) {
            return true;
        }
        return false;
    }

    private static boolean canCluster(char a, char b) {
        if (a == 's') {
            return true;
        }
        return "brbl".indexOf(a) >= 0 || "prpl".indexOf(a) >= 0
                || "dr".indexOf(a) >= 0 || "trgrcrfr".indexOf(a) >= 0;
    }
}
