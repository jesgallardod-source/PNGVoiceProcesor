package org.PNGV.phoneme;
import java.util.ArrayList;

public class DebugYI {
    public static void main(String[] args) {
        String[] words = {"i", "y", "ye", "yo", "ya", "si", "ti", "mi", "rey", "ley", "hoy", "soy", "yema", "ii", "ay", "iy"};
        for (String w : words) {
            ArrayList<String> syl = VoskRecognizer.dividirEnSilabas(w);
            StringBuilder sb = new StringBuilder();
            for (String s : syl) sb.append("['").append(s).append("'->").append(VoskRecognizer.visemeFromSyllable(s)).append("] ");
            System.out.printf("%-6s -> %s  (fromWord=%s)%n", w, sb, Viseme.fromWord(w));
        }
    }
}
