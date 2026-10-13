package org.PNGV.avatar;

import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Hoja de sprites: una imagen PNG dividida en una cuadrícula de frames
 * iguales (rows × cols), cada uno de tamaño {@code frameWidth × frameHeight}.
 *
 * <p>Los frames se pre-cortan (sub-image) y se guardan en un array 2D para
 * que el render sea O(1).
 */
public class SpriteSheet {

    private final Image source;
    private final int rows;
    private final int cols;
    private final int frameWidth;
    private final int frameHeight;

    public SpriteSheet(Image source, int rows, int cols, int frameWidth, int frameHeight) {
        this.source = source;
        this.rows = rows;
        this.cols = cols;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
    }

    /**
     * Carga una hoja de sprites desde un archivo PNG.
     *
     * @param path         ruta al PNG
     * @param rows         número de filas
     * @param cols         número de columnas
     * @param frameWidth   ancho de cada frame en píxeles
     * @param frameHeight  alto de cada frame en píxeles
     * @return hoja de sprites lista para render
     * @throws IOException si el archivo no existe o no es un PNG válido
     */
    public static SpriteSheet load(Path path, int rows, int cols,
                                   int frameWidth, int frameHeight) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("Sprite sheet no encontrado: " + path.toAbsolutePath());
        }
        try (InputStream in = new FileInputStream(path.toFile())) {
            Image img = new Image(in);
            return new SpriteSheet(img, rows, cols, frameWidth, frameHeight);
        }
    }

    /**
     * Devuelve el frame en la posición (row, col) como una sub-imagen.
     *
     * @param row fila (0..rows-1)
     * @param col columna (0..cols-1)
     * @return sub-imagen del tamaño del frame
     */
    public WritableImage frame(int row, int col) {
        int x = col * frameWidth;
        int y = row * frameHeight;
        // JavaFX no expone un sub-image directo: copiamos los píxeles del
        // recuadro (x, y, frameWidth, frameHeight) a un WritableImage nuevo.
        javafx.scene.image.PixelReader reader = source.getPixelReader();
        int[] pixels = new int[frameWidth * frameHeight];
        for (int py = 0; py < frameHeight; py++) {
            for (int px = 0; px < frameWidth; px++) {
                pixels[py * frameWidth + px] = reader.getArgb(x + px, y + py);
            }
        }
        WritableImage out = new WritableImage(frameWidth, frameHeight);
        javafx.scene.image.PixelWriter writer = out.getPixelWriter();
        for (int py = 0; py < frameHeight; py++) {
            for (int px = 0; px < frameWidth; px++) {
                writer.setArgb(px, py, pixels[py * frameWidth + px]);
            }
        }
        return out;
    }

    public int getRows() { return rows; }
    public int getCols() { return cols; }
    public int getFrameWidth() { return frameWidth; }
    public int getFrameHeight() { return frameHeight; }
}
