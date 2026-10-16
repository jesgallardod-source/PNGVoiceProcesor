package org.PNGV.phoneme;

import java.util.HashMap;
import java.util.Map;

/**
 * Visemas (formas de boca) que el avatar puede mostrar.
 *
 * <p>Esta versión usa la estructura <b>ideal para 2D: 10 imágenes</b>. Cada
 * viseme es una forma de boca distinta y tiene su propia imagen de referencia.
 *
 * <p>La idea es separar las <b>5 vocales</b> (el movimiento de boca más
 * importante) y añadir las <b>4 grupos de consonantes</b> que más cambian la
 * forma de la boca:
 *
 * <ul>
 *   <li>{@link #NEUTRAL} → boca cerrada, reposo (silencio)</li>
 *   <li>{@link #A}       → boca abierta amplia            (a, á)</li>
 *   <li>{@link #E}       → boca media, labios extendidos  (e, é)</li>
 *   <li>{@link #I}       → boca cerrada, sonrisa          (i, í)</li>
 *   <li>{@link #O}       → boca redondeada media          (o, ó)</li>
 *   <li>{@link #U}       → boca muy redondeada            (u, ú)</li>
 *   <li>{@link #BMP}     → labios cerrados                (b, m, p)</li>
 *   <li>{@link #FV}      → diente sobre labio inferior    (f, v)</li>
 *   <li>{@link #L}       → punta de lengua                (l, r)</li>
 *   <li>{@link #S}       → boca estrecha / intermedia     (s, c, d, g, k, n, t, x, y, z, h, q, w)</li>
 * </ul>
 *
 * <p><b>Nota sobre consonantes agrupadas:</b> en 2D las consonantes del grupo
 * {@code S} comparten una posición de boca "intermedia" que funciona para todas;
 * separarlas no aporta movimiento visible. Lo que sí se separa son las vocales
 * (máximo movimiento) y los 4 grupos labiales/linguales que se distinguen a
 * simple vista (BMP, FV, L, S).
 */
public enum Viseme {
    /** Boca cerrada / neutra. Siempre disponible (silencio). */
    NEUTRAL,
    /** Boca abierta amplia. Vocal A. */
    A,
    /** Boca media, labios extendidos. Vocal E. */
    E,
    /** Boca cerrada, sonrisa. Vocal I. */
    I,
    /** Boca redondeada media. Vocal O. */
    O,
    /** Boca muy redondeada. Vocal U. */
    U,
    /** Labios cerrados. B, M, P. */
    BMP,
    /** Diente superior sobre labio inferior. F, V. */
    FV,
    /** Punta de lengua visible. L, R. */
    L,
    /** Boca estrecha / intermedia. S, C, D, G, K, N, T, X, Y, Z, H, Q, W. */
    S,
    /**
     * Grito (variante 1): expresión de boca a gritar. <b>No</b> corresponde a
     * ninguna letra del alfabeto; se muestra únicamente cuando el micrófono
     * detecta un nivel de decibeles muy alto (grito).
     */
    GRITO,
    /** Grito (variante 2): segunda imagen disponible para el grito. */
    GRITO2,
    /** Grito (variante 3): tercera imagen disponible para el grito. */
    GRITO3;

    /** Mapa letra (minúscula) → viseme al que pertenece. */
    private static final Map<Character, Viseme> LETTER_TO_VISEME = buildLetterMap();

    /**
     * Construye el mapa letra → viseme. Cada letra pertenece a un único grupo,
     * así que no hay sobrescrituras ni prioridades: la asignación es directa.
     */
    private static Map<Character, Viseme> buildLetterMap() {
        Map<Character, Viseme> map = new HashMap<>();
        // Vocales (cada una con su propia forma de boca)
        assign(map, A, "aá");
        assign(map, E, "eé");
        assign(map, I, "ií");
        assign(map, O, "oó");
        assign(map, U, "uú");
        // Grupos de consonantes
        assign(map, BMP, "bmp");
        assign(map, FV, "fv");
        assign(map, L, "lr");
        assign(map, S, "scdgkntxyzhqw");
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
     * Devuelve el viseme correspondiente a una letra.
     *
     * <p>Regla de default: si la letra <b>sí</b> es una letra del alfabeto pero
     * no cae en ningún grupo de viseme asignado, se usa {@link #S} como
     * viseme por defecto (una posición de boca "intermedia" que funciona para
     * cualquier consonante no clasificada). Solo los caracteres que <b>no</b>
     * son letras (puntuación, espacios, dígitos, etc.) devuelven
     * {@link #NEUTRAL}, porque no representan una forma de boca real.
     *
     * @param letter letra arbitraria (mayúscula o minúscula)
     * @return viseme del grupo, {@link #S} si es una letra sin grupo asignado,
     *         o {@link #NEUTRAL} si no es una letra
     */
    public static Viseme fromLetter(char letter) {
        if (!Character.isLetter(letter)) {
            return NEUTRAL;
        }
        Viseme v = LETTER_TO_VISEME.get(Character.toLowerCase(letter));
        return v != null ? v : S;
    }

    /**
     * Devuelve el viseme de una palabra usando su <b>primera letra</b>.
     *
     * <p>La primera letra es el "ataque" de la sílaba: la forma de boca más
     * reconocible. Se recorre la palabra hasta encontrar la primera letra
     * (ignorando puntuación, espacios, etc.) y se mapea con
     * {@link #fromLetter(char)}.
     *
     * @param word palabra arbitraria (p. ej. "hola", "programación")
     * @return viseme de la primera letra, o {@link #NEUTRAL} si no hay letras
     */
    public static Viseme fromWord(String word) {
        if (word == null) {
            return NEUTRAL;
        }
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetter(c)) {
                return fromLetter(c);
            }
        }
        return NEUTRAL;
    }
}
