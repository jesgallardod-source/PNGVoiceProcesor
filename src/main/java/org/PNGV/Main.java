package org.PNGV;

import org.PNGV.app.PngVoiceApp;

/**
 * Punto de entrada de PNGVoice. Lanza la interfaz gráfica JavaFX
 * (estilo veadotube).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        PngVoiceApp.launch(PngVoiceApp.class, args);
    }
}
