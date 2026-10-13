package org.PNGV.avatar;

import java.util.ArrayList;
import java.util.List;

/**
 * Modelo de un avatar: una hoja de sprites (PNG) organizada en
 * <b>filas = niveles de volumen</b> y <b>columnas = visemes</b>.
 *
 * <p>Ejemplo: un avatar 4×12 tiene 4 niveles de volumen (silencio, bajo,
 * medio, alto) y 12 formas de boca.
 *
 * <p>Los frames se indexan como:
 * <pre>
 *   row    = nivel de volumen  (0..rows-1)
 *   column = viseme ordinal    (0..cols-1)
 * </pre>
 */
public class Avatar {

    private final String id;
    private final String imagePath;
    private final int rows;
    private final int cols;
    private final int frameWidth;
    private final int frameHeight;
    private final List<String> visemeLabels;

    public Avatar(String id, String imagePath, int rows, int cols,
                  int frameWidth, int frameHeight, List<String> visemeLabels) {
        this.id = id;
        this.imagePath = imagePath;
        this.rows = rows;
        this.cols = cols;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.visemeLabels = visemeLabels == null ? new ArrayList<>() : List.copyOf(visemeLabels);
    }

    public String getId() { return id; }
    public String getImagePath() { return imagePath; }
    public int getRows() { return rows; }
    public int getCols() { return cols; }
    public int getFrameWidth() { return frameWidth; }
    public int getFrameHeight() { return frameHeight; }
    public List<String> getVisemeLabels() { return visemeLabels; }

    /**
     * Devuelve la etiqueta de viseme para una columna, o "NEUTRAL" si la
     * columna no tiene etiqueta explícita.
     */
    public String visemeLabel(int column) {
        if (column >= 0 && column < visemeLabels.size()) {
            return visemeLabels.get(column);
        }
        return "NEUTRAL";
    }

    @Override
    public String toString() {
        return "Avatar{id='" + id + "', " + rows + "x" + cols + ", "
                + frameWidth + "x" + frameHeight + "}";
    }
}
