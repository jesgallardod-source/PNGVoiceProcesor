package org.PNGV.phoneme;

import java.util.HashMap;
import java.util.Map;

/**
 * Visemas (grupos de referencia) que el avatar puede mostrar.
 *
 * <p>Cada viseme agrupa un <b>conjunto de letras</b> que comparten la <b>misma
 * imagen de referencia</b>. Es decir, todas las letras de un grupo se renderizan
 * con la misma imagen:
 *
 * <ul>
 *   <li>{@link #AE}        → A, E</li>
 *   <li>{@link #CDGKNSXYZ} → C, D, G, K, N, S, X, Y, Z</li>
 *   <li>{@link #R}         → R</li>
 *   <li>{@link #BMP}       → B, M, P</li>
 *   <li>{@link #TH}        → T, H</li>
 *   <li>{@link #O}         → O</li>
 *   <li>{@link #QW}        → Q, W</li>
 *   <li>{@link #L}         → L</li>
 *   <li>{@link #FV}        → F, V</li>
 * </ul>
 *
 * <p><b>Nota sobre la letra T:</b> la letra T aparece en dos grupos
 * ({@code C,D,G,K,N,S,T,X,Y,Z} y {@code T,H}). Se resuelve dando prioridad al
 * grupo {@link #TH} (el último definido). Si se desea lo contrario, basta con
 * reordenar las llamadas a {@link #assign} en {@link #buildLetterMap()}.
 */
public enum Viseme {
    /** Boca cerrada / neutra. Siempre disponible (silencio). */
    NEUTRAL,
    /** A, E */
    AE,
    /** C, D, G, K, N, S, X, Y, Z */
    CDGKNSXYZ,
    /** R */
    R,
    /** B, M, P */
    BMP,
    /** T, H */
    TH,
    /** O */
    O,
    /** Q, W */
    QW,
    /** L */
    L,
    /** F, V */
    FV;

    /** Mapa letra (minúscula) → viseme/grupo al que pertenece. */
    private static final Map<Character, Viseme> LETTER_TO_VISEME = buildLetterMap();

    private Viseme() {
    }

    /**
     * Construye el mapa letra → viseme a partir de los grupos definidos por el
     * usuario. Los grupos se asignan en orden; si una letra pertenece a varios
     * grupos, <b>gana el último</b> (por eso T → TH).
     */
    private static Map<Character, Viseme> buildLetterMap() {
        Map<Character, Viseme> map = new HashMap<>();
        assign(map, AE, "AE");
        assign(map, CDGKNSXYZ, "CDGKNSXYZT"); // incluye T (será sobrescrito por TH)
        assign(map, R, "R");
        assign(map, BMP, "BMP");
        assign(map, TH, "TH");                 // T → TH (prioridad)
        assign(map, O, "O");
        assign(map, QW, "QW");
        assign(map, L, "L");
        assign(map, FV, "FV");
        return map;
    }

    private static void assign(Map<Character, Viseme> map, Viseme viseme, String letters) {
        for (char c : letters.toCharArray()) {
            if (Character.isLetter(c)) {
                map.put(Character.toLowerCase(c), viseme);
            }
        }
    }

    /**
     * Devuelve el viseme (grupo de referencia) correspondiente a una letra.
     *
     * @param letter letra arbitraria (mayúscula o minúscula)
     * @return viseme del grupo, o {@link #NEUTRAL} si la letra no está asignada
     */
    public static Viseme fromLetter(char letter) {
        if (!Character.isLetter(letter)) {
            return NEUTRAL;
        }
        Viseme v = LETTER_TO_VISEME.get(Character.toLowerCase(letter));
        return v != null ? v : NEUTRAL;
    }

    /**
     * Devuelve el viseme correspondiente a un fonema/letra dado como cadena.
     *
     * <p>Se usa la primera letra de la cadena para el mapeo (compatible con las
     * llamadas de {@code SyllableSplitter}, que pasa una letra a la vez).
     *
     * @param phoneme fonema en minúsculas (p. ej. "a", "m", "f")
     * @return viseme del grupo, o {@link #NEUTRAL} si no se reconoce
     */
    public static Viseme fromPhoneme(String phoneme) {
        if (phoneme == null || phoneme.isBlank()) {
            return NEUTRAL;
        }
        return fromLetter(phoneme.trim().charAt(0));
    }
}
