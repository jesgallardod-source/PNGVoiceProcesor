package org.PNGV.avatar;

import org.PNGV.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Gestiona los avatares disponibles y el avatar activo.
 *
 * <p>Un avatar se define por:
 * <ul>
 *   <li>Hoja de sprites PNG (atlas).</li>
 *   <li>Dimensiones de la cuadrícula (rows × cols).</li>
 *   <li>Tamaño de cada frame.</li>
 *   <li>Etiquetas de viseme por columna (opcional).</li>
 * </ul>
 *
 * <p>Para v1 se carga un único avatar de prueba. En el futuro, se puede
 * explorar la carpeta {@code assets/avatars/} y cargar varios.
 */
public class AvatarManager {

    private static final Logger LOG = LoggerFactory.getLogger(AvatarManager.class);

    private final ConcurrentMap<String, Avatar> avatars = new ConcurrentHashMap<>();
    private volatile Avatar active;

    public AvatarManager() {
    }

    /** Registra un avatar. */
    public void register(Avatar avatar) {
        avatars.put(avatar.getId(), avatar);
        if (active == null) {
            active = avatar;
        }
    }

    /** Cambia el avatar activo por id. */
    public boolean setActive(String id) {
        Avatar a = avatars.get(id);
        if (a != null) {
            active = a;
            return true;
        }
        return false;
    }

    /** Devuelve el avatar activo (o null si no hay ninguno). */
    public Avatar getActive() {
        return active;
    }

    /** Lista de ids de avatares registrados. */
    public List<String> getAvatarIds() {
        return new ArrayList<>(avatars.keySet());
    }

    /**
     * Carga el avatar activo desde la configuración.
     *
     * <p>Para v1, se usa un avatar de prueba con dimensiones fijas:
     * 4 filas × 12 columnas, frames de 128×128 px.
     *
     * @param config configuración de la app
     * @throws IOException si el archivo no existe o no se puede cargar
     */
    public void loadDefault(AppConfig config) throws IOException {
        String path = config.getActiveAvatar();
        Path p = Paths.get(path);
        if (!p.toFile().exists()) {
            LOG.warn("Avatar {} no existe, usando avatar de prueba generado", path);
            register(generatedTestAvatar());
            return;
        }
        SpriteSheet sheet = SpriteSheet.load(p, 4, 10, 128, 128);
        Avatar avatar = new Avatar(
                "default",
                path,
                4, 10,
                128, 128,
                List.of("NEUTRAL", "A", "E", "I", "O",
                        "U", "BMP", "FV", "L", "S"));
        register(avatar);
        LOG.info("Avatar cargado: {}", avatar);
    }

    /**
     * Crea un avatar de prueba generado en memoria (sin archivo PNG).
     * Útil para la demo inicial y tests.
     */
    private Avatar generatedTestAvatar() {
        // Para la demo, se usa un placeholder. En la UI se dibuja un rectángulo
        // de prueba si no hay sprite sheet real.
        Avatar a = new Avatar(
                "test",
                "(generated)",
                4, 10,
                128, 128,
                List.of("NEUTRAL", "A", "E", "I", "O",
                        "U", "BMP", "FV", "L", "S"));
        register(a);
        return a;
    }
}
