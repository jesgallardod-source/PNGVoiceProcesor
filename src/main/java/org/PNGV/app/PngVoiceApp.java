package org.PNGV.app;

import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.PNGV.audio.AudioCapture;
import org.PNGV.audio.VolumeAnalyzer;
import org.PNGV.config.AppConfig;
import org.PNGV.config.ConfigLoader;
import org.PNGV.logging.LetterLogger;
import org.PNGV.phoneme.Viseme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Ventana principal estilo <b>veadotube</b>:
 * <ul>
 *   <li>Pequeña, always-on-top, no redimensionable.</li>
 *   <li>Avatar centrado que reacciona al volumen (viseme + boca).</li>
 *   <li>Barra de volumen + estado del micrófono + botón iniciar/detener.</li>
 * </ul>
 *
 * <p>Para la v1 se dibuja un avatar de prueba con formas JavaFX. Cuando exista
 * un atlas PNG real, se usará {@code SpriteSheet} (ver {@link org.PNGV.avatar.SpriteSheet}).
 */
public class PngVoiceApp extends javafx.application.Application {

    private static final Logger LOG = LoggerFactory.getLogger(PngVoiceApp.class);

    /**
     * Tamaño (px) del área del avatar / imágenes propias. La ventana es 1024x768
     * y el avatar ocupa la mayor parte del centro para verse grande.
     */
    private static final double AVATAR_SIZE = 640;
    /**
     * Factor de escala del placeholder dibujado (base 240x240 → AVATAR_SIZE).
     */
    private static final double AVATAR_SCALE = AVATAR_SIZE / 240.0;

    /** Visemes de "hablando" que se rotan aleatoriamente cuando hay volumen. */
    private static final Viseme[] TALKING = {
            Viseme.A, Viseme.E, Viseme.I, Viseme.O, Viseme.U,
            Viseme.BMP, Viseme.FV, Viseme.L, Viseme.S};

    // ---- Ritmo de los cambios de imagen (para que se sienta una "plática") ----
    /**
     * Duración (ms) que se mantiene la <b>primera</b> imagen al empezar a hablar
     * y la <b>última</b> imagen antes de volver al silencio. Es el valor más
     * alto: estas dos se ven "más lentas" (permanecen más tiempo).
     */
    private static final long FIRST_LAST_MS = 700;
    /**
     * Duración (ms) de las imágenes <b>intermedias</b> (entre la primera y la
     * última). Es menor que {@link #FIRST_LAST_MS} pero mucho mayor que el
     * cambio por frame (~16 ms), de modo que se aprecian sin "parpadear".
     */
    private static final long MIDDLE_MS = 350;

    /**
     * Visemes (grupos de letras) que se muestran como imágenes propias.
     * Cada grupo comparte una única imagen de referencia.
     * El orden importa: es el orden en el que aparecen en el menú "Imágenes".
     */
    private static final List<Viseme> IMAGE_VISEMES = List.of(
            Viseme.NEUTRAL, Viseme.A, Viseme.E, Viseme.I, Viseme.O, Viseme.U,
            Viseme.BMP, Viseme.FV, Viseme.L, Viseme.S);

    /**
     * Paleta de fondos disponibles en el menú "Fondo".
     * Los verdes azules (croma-key) son los que se usan típicamente para
     * borrar el fondo digitalmente (chroma-key / green-screen).
     */
    private static final Map<String, String> BACKGROUND_PALETTE = new LinkedHashMap<>();
    static {
        BACKGROUND_PALETTE.put("Verde croma-key",   "#00FF00");
        BACKGROUND_PALETTE.put("Verde (alternativa)", "#39FF14");
        BACKGROUND_PALETTE.put("Azul croma-key",    "#0000FF");
        BACKGROUND_PALETTE.put("Cian (blue-screen)", "#00FFFF");
        BACKGROUND_PALETTE.put("Negro",             "#000000");
        BACKGROUND_PALETTE.put("Blanco",            "#FFFFFF");
        BACKGROUND_PALETTE.put("Gris oscuro",       "#1e1e2e");
        BACKGROUND_PALETTE.put("Gris claro",        "#cccccc");
        BACKGROUND_PALETTE.put("Amarillo",          "#FFFF00");
        BACKGROUND_PALETTE.put("Rosa",              "#FF69B4");
        BACKGROUND_PALETTE.put("Naranja",           "#FFA500");
    }

    private final Random random = new Random();

    private AudioCapture capture;
    private Viseme lastViseme = Viseme.NEUTRAL;
    private Path configPath;
    private final LetterLogger letterLogger = new LetterLogger();

    // ---- Estado del ritmo de cambio de viseme (ver tick) ----
    /** Viseme actualmente visible en pantalla. */
    private Viseme currentVisible = Viseme.NEUTRAL;
    /** true si el avatar está en modo "hablando" (viseme != NEUTRAL). */
    private boolean isTalking = false;
    /** true mientras la imagen visible es la primera de una "palabra" (se congela). */
    private boolean isHoldingFirst = false;
    /** Marca (ns, AnimationTimer) de cuándo se fijó la imagen visible actual. */
    private long visibleImageStartNanos = 0L;

    // Nodos UI
    private Circle face;
    private Ellipse mouth;
    private ProgressBar volumeBar;
    private Label statusLabel;
    private Label visemeLabel;
    private Button toggleButton;
    private Button placeholderButton;
    private Label rightLetterLabel;    // etiqueta del panel derecho con la letra actual
    private Pane placeholderLayer;   // capa con las formas del avatar de prueba
    private boolean placeholderVisible = false; // oculto por defecto al iniciar
    private Scene scene;
    private Stage stage;

    // Imágenes propias del usuario (una por viseme/letra)
    private Pane imageLayer;                       // contenedor de la ImageView
    private ImageView imageHolder;                 // imagen actual mostrada
    private final Map<Viseme, Image> visemeImages = new HashMap<>(); // viseme -> imagen cargada
    private Viseme lastShownViseme = Viseme.NEUTRAL;
    private boolean usingImages = false;           // true si hay al menos una imagen asignada

    // ---- Parpadeo periódico (independiente de silencio y letras) ----
    /** Imagen que se muestra brevemente cuando el avatar parpadea. */
    private Image blinkImage;
    /** Timeline que dispara el parpadeo cada cierto tiempo. */
    private Timeline blinkTimeline;
    /** true mientras se está mostrando el frame de parpadeo. */
    private boolean blinking = false;
    /** Imagen que estaba visible justo antes del parpadeo (para restaurarla). */
    private Image preBlinkImage;
    /** Intervalo (ms) entre parpadeos. */
    private static final long BLINK_INTERVAL_MS = 4000;
    /** Duración (ms) de cada parpadeo (ojos cerrados). */
    private static final long BLINK_DURATION_MS = 150;

    @Override
    public void start(Stage stage) {
        this.configPath = resolveConfigPath();
        AppConfig config = new ConfigLoader().load(configPath);
        LOG.info("App UI iniciada (config: {})", configPath.toAbsolutePath());
        loadVisemeImages(config);

        // ---- Avatar (placeholder dibujado con formas) ----
        face = new Circle(120, 120, 105, Color.web("#FFD9B3"));
        face.setStroke(Color.web("#3a2f2a"));
        face.setStrokeWidth(2);
        Circle eyeL = new Circle(92, 102, 9, Color.web("#222"));
        Circle eyeR = new Circle(148, 102, 9, Color.web("#222"));
        Circle cheekL = new Circle(78, 132, 10, Color.web("#f7b0a0"));
        Circle cheekR = new Circle(162, 132, 10, Color.web("#f7b0a0"));
        mouth = new Ellipse(120, 150, 18, 3);
        mouth.setFill(Color.web("#7a2020"));
        mouth.setStroke(Color.web("#3a1010"));
        mouth.setStrokeWidth(1);

        // Capa del placeholder: agrupa todas las formas para poder ocultarlas.
        // Las formas se dibujan en una base de 240x240 y se agrandan con una
        // escala para llenar AVATAR_SIZE (ver constante).
        Pane placeholderLayer = new Pane(face, cheekL, cheekR, eyeL, eyeR, mouth);
        placeholderLayer.setPrefSize(AVATAR_SIZE, AVATAR_SIZE);
        placeholderLayer.setMouseTransparent(true);
        placeholderLayer.getTransforms().add(new Scale(AVATAR_SCALE, AVATAR_SCALE, 0, 0));
        this.placeholderLayer = placeholderLayer;

        Pane avatar = new Pane(placeholderLayer);
        avatar.setPrefSize(AVATAR_SIZE, AVATAR_SIZE);
        avatar.setMinSize(AVATAR_SIZE, AVATAR_SIZE);
        avatar.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);

        // ---- Capa de imágenes propias (superpuesta al placeholder) ----
        // Se muestra sobre las formas cuando el usuario asigna una imagen a
        // un viseme. La imagen se centra y escala para cubrir el área AVATAR_SIZE.
        imageHolder = new ImageView();
        imageHolder.setPreserveRatio(true);
        imageHolder.setFitWidth(AVATAR_SIZE);
        imageHolder.setFitHeight(AVATAR_SIZE);
        imageHolder.setManaged(true);
        imageHolder.setVisible(false);
        imageHolder.setMouseTransparent(true);
        imageLayer = new StackPane(imageHolder);
        imageLayer.setPrefSize(AVATAR_SIZE, AVATAR_SIZE);
        imageLayer.setMouseTransparent(true);
        avatar.getChildren().add(imageLayer);
        imageLayer.toFront();

        // ---- Controles ----
        volumeBar = new ProgressBar(0.0);
        volumeBar.setMaxWidth(Double.MAX_VALUE);
        volumeBar.setPrefWidth(720);
        volumeBar.setStyle(
                "-fx-background-color: #2a2a3a;" +
                "-fx-progress-bar: #2a2a3a;" +
                "-fx-bar-color: linear-gradient(to right, #4CAF50, #FFC107, #F44336);");

        Label volLabel = new Label("Volumen");
        volLabel.setTextFill(Color.web("#cdd6f4"));
        volLabel.setStyle("-fx-font-size: 12px;");

        statusLabel = new Label("Micrófono: OFF");
        statusLabel.setTextFill(Color.web("#89b4fa"));
        statusLabel.setStyle("-fx-font-size: 13px;");

        visemeLabel = new Label("Viseme: NEUTRAL");
        visemeLabel.setTextFill(Color.web("#a6e3a1"));
        visemeLabel.setStyle("-fx-font-size: 12px;");

        toggleButton = new Button("Iniciar micrófono");
        toggleButton.setStyle(
                "-fx-background-color: #89b4fa;" +
                "-fx-text-fill: #1e1e2e;" +
                "-fx-font-weight: bold;" +
                "-fx-padding: 8 18 8 18;" +
                "-fx-cursor: hand;");
        toggleButton.setOnAction(e -> toggleMic());

        placeholderButton = new Button("Mostrar placeholder");
        placeholderButton.setStyle(
                "-fx-background-color: #a6e3a1;" +
                "-fx-text-fill: #1e1e2e;" +
                "-fx-font-weight: bold;" +
                "-fx-padding: 8 18 8 18;" +
                "-fx-cursor: hand;");
        placeholderButton.setOnAction(e -> togglePlaceholder());

        // ---- Botones de control de imágenes / letra ----
        Button removeLetterButton = new Button("Remover imagen");
        removeLetterButton.setStyle(panelButtonStyle("#f38ba8"));
        removeLetterButton.setOnAction(e -> removeCurrentLetterImage(configPath));

        Button replaceLetterButton = new Button("Reemplazar imagen…");
        replaceLetterButton.setStyle(panelButtonStyle("#fab387"));
        replaceLetterButton.setOnAction(e -> replaceCurrentLetterImage(configPath));

        // ---- Fila de botones: iniciar/stop + placeholder + acciones de letra ----
        HBox controls = new HBox(12,
                toggleButton,
                placeholderButton,
                removeLetterButton,
                replaceLetterButton);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(8));

        // ---- Etiqueta de la letra actual ----
        rightLetterLabel = new Label("Letra actual: " + displayName(lastViseme));
        rightLetterLabel.setTextFill(Color.web("#cdd6f4"));
        rightLetterLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        // ---- Área de controles: volumen + estado + botones, con fondo gris oscuro ----
        // Se superpone sobre el avatar (área de trabajo) anclada abajo, para
        // que los botones SIEMPRE sean visibles aunque el avatar ocupe todo.
        VBox bottom = new VBox(6, volLabel, volumeBar, statusLabel, visemeLabel, rightLetterLabel, controls);
        bottom.setAlignment(Pos.CENTER);
        bottom.setPadding(new Insets(10));
        bottom.setStyle("-fx-background-color: #2b2b2b; -fx-background-radius: 10; -fx-padding: 10;");

        // ---- Menú: fondo + salir ----
        MenuBar menuBar = buildMenuBar(config, configPath);

        // ---- Root: menú arriba + avatar al centro + controles abajo ----
        // La ventana es lo bastante alta (880 px) para que el avatar (640 px)
        // y la barra de controles quepan sin solaparse ni recortarse.
        BorderPane root = new BorderPane();
        root.setTop(menuBar);
        root.setCenter(avatar);
        root.setBottom(bottom);
        root.setStyle("-fx-background-color: transparent;");

        Color bgColor = safeColor(config.getBackgroundColor(), Color.web("#00FF00"));
        scene = new Scene(root, 1024, 880, bgColor);
        this.stage = stage;
        stage.setScene(scene);
        stage.setTitle("PNGVoice");
        stage.setResizable(false);
        stage.setAlwaysOnTop(true);
        stage.show();

        // Muestra el viseme inicial (NEUTRAL) para que, si el usuario asignó
        // una imagen a "silencio", se vea desde el arranque sin esperar a
        // iniciar el micrófono.
        applyViseme(lastViseme);

        // Inicia el parpadeo periódico (independiente de silencio y letras).
        startBlinkTimeline();

        // ---- Loop de animación (~60 fps, solo cuando hay captura) ----
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (capture == null) return;
                AudioCapture.VolumeState st = capture.getState();
                tick(st.level(), st.normalized(), now);
            }
        };
        timer.start();

        // ---- Smoke test: si se pasa --smoke-test, cierra solo tras 3 s ----
        if (getParameters().getRaw().contains("--smoke-test")) {
            javafx.animation.PauseTransition pause =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
            pause.setOnFinished(e -> Platform.exit());
            pause.play();
            LOG.info("Modo smoke-test: la ventana se cerrará en 3 s");
        }
    }

    /**
     * Actualiza UI con el estado de volumen más reciente.
     *
     * <p>Ritmo tipo "plática":
     * <ul>
     *   <li>Al <b>empezar</b> a hablar se elige una <b>primera</b> imagen que
     *       se mantiene durante {@link #FIRST_LAST_MS} (el valor más largo).</li>
     *   <li>Mientras continúa el habla se rotan imágenes <b>intermedias</b> cada
     *       {@link #MIDDLE_MS} (más lento que antes, pero menos que la primera/última).</li>
     *   <li>La <b>última</b> imagen antes de volver al silencio también se
     *       mantiene {@link #FIRST_LAST_MS}.</li>
     *   <li>Al llegar a silencio se pasa a {@code NEUTRAL} inmediatamente.</li>
     * </ul>
     *
     * @param now marca de tiempo del {@link AnimationTimer} (ns), para medir
     *            cuánto lleva la imagen visible actual.
     */
    private void tick(int level, double normalized, long now) {
        boolean talking = level != 0;
        boolean wasTalking = isTalking;
        boolean firstFrame = !wasTalking && talking;   // borde silencio → habla
        boolean lastFrame  = wasTalking && !talking;   // borde habla → silencio

        if (firstFrame) {
            // Empieza la "palabra": congelamos la primera imagen por FIRST_LAST_MS.
            currentVisible = pickTalkingViseme(level);
            isTalking = true;
            isHoldingFirst = true;
            visibleImageStartNanos = now;
        } else if (talking) {
            isTalking = true;
            if (isHoldingFirst) {
                // La primera imagen se mantiene; cuando termina su tiempo pasa
                // a ritmo normal (MIDDLE_MS) y se rota.
                if (elapsedMs(now, visibleImageStartNanos) >= FIRST_LAST_MS) {
                    isHoldingFirst = false;
                    currentVisible = pickDifferentTalkingViseme(level, currentVisible);
                    visibleImageStartNanos = now;
                }
            } else {
                // Ritmo normal: rotamos cada MIDDLE_MS, sin repetir la misma imagen.
                if (elapsedMs(now, visibleImageStartNanos) >= MIDDLE_MS) {
                    currentVisible = pickDifferentTalkingViseme(level, currentVisible);
                    visibleImageStartNanos = now;
                }
            }
        } else if (lastFrame) {
            // Acaba de llegar el silencio: la última imagen hablada se muestra
            // FIRST_LAST_MS más (ya está en pantalla) y luego NEUTRAL.
            isTalking = false;
            isHoldingFirst = false;
            // currentVisible se mantiene; se volverá a aplicar al terminar el
            // "hold" final (ver rama de silencio abajo).
        } else {
            // Silencio continuo.
            isTalking = false;
            isHoldingFirst = false;
        }

        // ---- Decisión de qué imagen mostrar en este frame ----
        Viseme toShow;
        if (isTalking) {
            toShow = currentVisible;
        } else {
            // Silencio: la "última" imagen hablada sigue visible durante un
            // hold final (FIRST_LAST_MS) antes de pasar a NEUTRAL.
            long held = elapsedMs(now, visibleImageStartNanos);
            if (lastFrame && held < FIRST_LAST_MS) {
                toShow = currentVisible;   // la última imagen hablada
            } else {
                toShow = Viseme.NEUTRAL;
                if (lastFrame) {
                    // Acaba de terminar el hold final: reiniciamos el reloj
                    // para que la próxima "palabra" empiece con su primera imagen.
                    visibleImageStartNanos = now;
                }
            }
        }

        // Solo redibujamos si cambió la imagen visible (evita trabajo inútil).
        if (toShow != currentVisible) {
            currentVisible = toShow;
        }
        applyViseme(currentVisible);
        volumeBar.setProgress(normalized);
        visemeLabel.setText("Viseme: " + currentVisible);
    }

    /** Elige un viseme de "hablando" aleatorio según el nivel de volumen. */
    private Viseme pickTalkingViseme(int level) {
        int idx = Math.min(TALKING.length - 1, random.nextInt(1 + level));
        return TALKING[idx];
    }

    /** Elige un viseme de "hablando" distinto al que ya está visible (para no repetir). */
    private Viseme pickDifferentTalkingViseme(int level, Viseme avoid) {
        int idx = Math.min(TALKING.length - 1, random.nextInt(1 + level));
        Viseme v = TALKING[idx];
        if (v == avoid) {
            // Si por azar repite, fuerza otro distinto (solo si hay más de uno).
            if (TALKING.length > 1) {
                do {
                    v = TALKING[random.nextInt(TALKING.length)];
                } while (v == avoid);
            }
        }
        return v;
    }

    /** Milisegundos transcurridos desde una marca de tiempo del AnimationTimer. */
    private static long elapsedMs(long now, long start) {
        if (start <= 0) return Long.MAX_VALUE;
        return (now - start) / 1_000_000L;
    }

    /** Cambia la forma de la boca según el viseme. */
    private void applyViseme(Viseme v) {
        lastShownViseme = v;
        // Registrar en el logger de letras (usa System.nanoTime como referencia)
        letterLogger.record(v, System.nanoTime());
        if (rightLetterLabel != null) {
            rightLetterLabel.setText("Letra actual: " + displayName(v));
        }
        // No sobrescribir la imagen durante un parpadeo activo.
        if (blinking) {
            return;
        }
        // Si el usuario asignó una imagen a este viseme, la muestra en lugar
        // del placeholder dibujado. Si no, se oculta la imagen.
        Image img = visemeImages.get(v);
        if (img != null) {
            if (imageHolder.getImage() != img) {
                imageHolder.setImage(img);
            }
            imageHolder.setVisible(true);
            imageHolder.toFront();
        } else {
            imageHolder.setVisible(false);
        }

        double rx, ry;
        switch (v) {
            case NEUTRAL -> { rx = 18; ry = 3; }   // reposo, casi cerrada
            case A       -> { rx = 22; ry = 18; }  // muy abierta
            case E       -> { rx = 22; ry = 10; }  // media, extendida
            case I       -> { rx = 18; ry = 5; }   // cerrada, sonrisa
            case O       -> { rx = 14; ry = 14; }  // redondeada media
            case U       -> { rx = 8;  ry = 12; }  // muy redondeada
            case BMP     -> { rx = 20; ry = 1; }   // labios cerrados
            case FV      -> { rx = 14; ry = 4; }   // diente sobre labio
            case L       -> { rx = 14; ry = 8; }   // punta de lengua
            case S       -> { rx = 6;  ry = 6; }   // boca estrecha
            default      -> { rx = 18; ry = 3; }
        }
        mouth.setCenterX(120);
        mouth.setCenterY(150);
        mouth.setRadiusX(rx);
        mouth.setRadiusY(ry);
    }

    /** Alterna la visibilidad del avatar de prueba (placeholder dibujado). */
    private void togglePlaceholder() {
        placeholderVisible = !placeholderVisible;
        if (placeholderLayer != null) {
            placeholderLayer.setVisible(placeholderVisible);
            placeholderLayer.setManaged(placeholderVisible);
        }
        placeholderButton.setText(placeholderVisible ? "Ocultar placeholder" : "Mostrar placeholder");
        LOG.info("Placeholder {}", placeholderVisible ? "mostrado" : "ocultado");
    }

    private void toggleMic() {
        if (capture == null) {
            startMic();
        } else {
            stopMic();
        }
    }

    private void startMic() {
        try {
            AppConfig config = new ConfigLoader().load(configPath);
            VolumeAnalyzer analyzer = new VolumeAnalyzer(config);
            AudioCapture cap = new AudioCapture(config, analyzer);
            cap.start();
            this.capture = cap;
            letterLogger.reset();
            statusLabel.setText("Micrófono: ON");
            toggleButton.setText("Detener micrófono");
            LOG.info("Micrófono iniciado");
        } catch (RuntimeException e) {
            LOG.error("No se pudo iniciar el micrófono", e);
            statusLabel.setText("Error: " + e.getMessage());
            this.capture = null;
        }
    }

    private void stopMic() {
        if (capture != null) {
            letterLogger.stop();
            capture.close();
            capture = null;
        }
        statusLabel.setText("Micrófono: OFF");
        toggleButton.setText("Iniciar micrófono");
        applyViseme(Viseme.NEUTRAL);
        volumeBar.setProgress(0.0);
        visemeLabel.setText("Viseme: NEUTRAL");
        LOG.info("Micrófono detenido");
    }

    /**
     * Construye la barra de menús con el submenú "Fondo" (paleta de colores)
     * y "Archivo" (Salir).
     */
    private MenuBar buildMenuBar(AppConfig config, Path configPath) {
        // ---- Fondo ----
        Menu fondo = new Menu("Fondo");
        ToggleGroup group = new ToggleGroup();
        String current = config.getBackgroundColor();

        BACKGROUND_PALETTE.forEach((name, hex) -> {
            RadioMenuItem item = new RadioMenuItem(name + "  " + hex);
            item.setToggleGroup(group);
            item.setSelected(hex.equalsIgnoreCase(current));
            item.setOnAction(e -> applyBackground(hex, configPath));
            fondo.getItems().add(item);
        });

        // ---- Imágenes propias (una por letra/viseme) ----
        Menu imagenes = new Menu("Imágenes");
        MenuItem addImages = new MenuItem("Agregar imágenes…");
        addImages.setOnAction(e -> openImageFilePicker(configPath));
        imagenes.getItems().add(addImages);
        imagenes.getItems().add(new javafx.scene.control.SeparatorMenuItem());

        // --- Parpadeo (independiente de silencio y letras) ---
        MenuItem assignBlink = new MenuItem("Asignar imagen de parpadeo…");
        assignBlink.setOnAction(e -> assignBlinkImage(configPath));
        imagenes.getItems().add(assignBlink);

        MenuItem replaceBlink = new MenuItem("Reemplazar imagen de parpadeo…");
        replaceBlink.setOnAction(e -> replaceBlinkImage(configPath));
        imagenes.getItems().add(replaceBlink);

        MenuItem removeBlink = new MenuItem("Quitar imagen de parpadeo");
        removeBlink.setOnAction(e -> removeBlinkImage(configPath));
        imagenes.getItems().add(removeBlink);
        imagenes.getItems().add(new javafx.scene.control.SeparatorMenuItem());

        // Submenú por viseme/letra: asigna la imagen elegida a esa letra.
        for (Viseme v : IMAGE_VISEMES) {
            MenuItem letter = new MenuItem("Asignar a '" + displayName(v) + "'");
            letter.setOnAction(e -> assignImageToViseme(v, configPath));
            imagenes.getItems().add(letter);
        }

        MenuItem removeLast = new MenuItem("Quitar última imagen asignada");
        removeLast.setOnAction(e -> removeLastImage(configPath));
        imagenes.getItems().add(removeLast);

        // ---- Archivo ----
        Menu archivo = new Menu("Archivo");
        javafx.scene.control.MenuItem salir = new javafx.scene.control.MenuItem("Salir");
        salir.setOnAction(e -> javafx.application.Platform.exit());
        archivo.getItems().add(salir);

        MenuBar bar = new MenuBar(archivo, imagenes, fondo);
        bar.setStyle("-fx-background-color: #2a2a3a; -fx-text-fill: #cdd6f4;");
        return bar;
    }

    /** Cambia el color de fondo de la escena y persiste en la config. */
    private void applyBackground(String hex, Path configPath) {
        Color c = safeColor(hex, Color.web("#00FF00"));
        scene.setFill(c);
        // Persiste
        try {
            ConfigLoader loader = new ConfigLoader();
            AppConfig cfg = loader.load(configPath);
            cfg.setBackgroundColor(hex);
            loader.save(configPath, cfg);
        } catch (RuntimeException e) {
            LOG.warn("No se pudo persistir el color de fondo", e);
        }
        LOG.info("Color de fondo cambiado a {}", hex);
    }

    // ===================== Imágenes propias por letra =====================

    /** Nombre legible de un viseme (grupo de letras) para mostrarlo en el menú. */
    private static String displayName(Viseme v) {
        return switch (v) {
            case NEUTRAL -> "silencio";
            case A       -> "a";
            case E       -> "e";
            case I       -> "i";
            case O       -> "o";
            case U       -> "u";
            case BMP     -> "b/m/p";
            case FV      -> "f/v";
            case L       -> "l/r";
            case S       -> "s/c/d/g/k/n/t/x/y/z/h/q/w";
            default      -> v.name();
        };
    }

    /** Clave especial en visemeImages para la imagen de parpadeo. */
    private static final String BLINK_KEY = "BLINK";

    /**
     * Carga las imágenes propias asignadas en la configuración y las guarda
     * en {@link #visemeImages} (viseme → imagen). Las rutas inválidas se
     * ignoran con un aviso. También carga la imagen de parpadeo si existe.
     */
    private void loadVisemeImages(AppConfig config) {
        visemeImages.clear();
        blinkImage = null;
        if (config.getVisemeImages() == null) {
            return;
        }
        for (Map.Entry<String, String> entry : config.getVisemeImages().entrySet()) {
            String key = entry.getKey();
            String path = entry.getValue();
            if (path == null || path.isBlank()) {
                continue;
            }
            File f = new File(path);
            if (!f.exists()) {
                LOG.warn("Imagen asignada no encontrada para '{}': {}", key, path);
                continue;
            }
            Image img = new Image(f.toURI().toString());

            if (BLINK_KEY.equals(key)) {
                blinkImage = img;
                LOG.info("Imagen de parpadeo cargada: {}", path);
            } else {
                Viseme v = parseViseme(key);
                if (v != null) {
                    visemeImages.put(v, img);
                }
            }
        }
        usingImages = !visemeImages.isEmpty();
        if (usingImages) {
            LOG.info("Cargadas {} imagen(es) propias de visemes", visemeImages.size());
        }
    }

    // ===================== Parpadeo periódico =====================

    /**
     * Inicia (o reinicia) el Timeline de parpadeo. Cada {@link #BLINK_INTERVAL_MS}
     * muestra la imagen de parpadeo durante {@link #BLINK_DURATION_MS} ms y luego
     * restaura la imagen que estaba visible. Funciona siempre que haya una imagen
     * de parpadeo asignada, independientemente del estado del micrófono.
     */
    private void startBlinkTimeline() {
        if (blinkTimeline != null) {
            blinkTimeline.stop();
        }
        if (blinkImage == null) {
            LOG.info("Sin imagen de parpadeo; parpadeo desactivado");
            return;
        }
        blinkTimeline = new Timeline(
                new KeyFrame(javafx.util.Duration.millis(BLINK_INTERVAL_MS), e -> triggerBlink()),
                new KeyFrame(javafx.util.Duration.millis(BLINK_INTERVAL_MS + BLINK_DURATION_MS), e -> endBlink()));
        blinkTimeline.setCycleCount(Animation.INDEFINITE);
        blinkTimeline.play();
        LOG.info("Parpadeo periódico iniciado (cada {} ms, duración {} ms)",
                BLINK_INTERVAL_MS, BLINK_DURATION_MS);
    }

    /** Muestra la imagen de parpadeo (ojos cerrados). */
    private void triggerBlink() {
        if (blinkImage == null) {
            return;
        }
        blinking = true;
        // Guarda la imagen actual para restaurarla al terminar.
        preBlinkImage = imageHolder.getImage();
        imageHolder.setImage(blinkImage);
        imageHolder.setVisible(true);
        imageHolder.toFront();
        LOG.debug("Blink: mostrando imagen de parpadeo");
    }

    /** Restaura la imagen que estaba antes del parpadeo. */
    private void endBlink() {
        if (!blinking) {
            return;
        }
        blinking = false;
        imageHolder.setImage(preBlinkImage);
        if (preBlinkImage == null) {
            imageHolder.setVisible(false);
        }
        LOG.debug("Blink: imagen restaurada");
    }

    /**
     * Asigna una imagen de parpadeo. Si hay imágenes pendientes, usa la primera;
     * si no, abre el selector de archivos.
     */
    private void assignBlinkImage(Path configPath) {
        try {
            ConfigLoader loader = new ConfigLoader();
            AppConfig cfg = loader.load(configPath);
            Map<String, String> map = cfg.getVisemeImages();

            String pending = map.get("__pending__");
            if (pending == null || pending.isBlank()) {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Elegir imagen de parpadeo (ojos cerrados)");
                chooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("Imágenes", "*.png", "*.jpg", "*.jpeg", "*.webp"));
                File f = chooser.showOpenDialog(stage != null ? stage : null);
                if (f == null) return;
                map.put(BLINK_KEY, f.getAbsolutePath());
            } else {
                String[] parts = pending.split("\\|");
                map.put(BLINK_KEY, parts[0]);
                if (parts.length > 1) {
                    map.put("__pending__", String.join("|", java.util.Arrays.copyOfRange(parts, 1, parts.length)));
                } else {
                    map.remove("__pending__");
                }
            }

            loader.save(configPath, cfg);
            loadVisemeImages(cfg);
            startBlinkTimeline();
            LOG.info("Imagen de parpadeo asignada");
        } catch (RuntimeException e) {
            LOG.error("No se pudo asignar la imagen de parpadeo", e);
        }
    }

    /** Reemplaza la imagen de parpadeo con una nueva. */
    private void replaceBlinkImage(Path configPath) {
        try {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Reemplazar imagen de parpadeo");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Imágenes", "*.png", "*.jpg", "*.jpeg", "*.webp"));
            File f = chooser.showOpenDialog(stage != null ? stage : null);
            if (f == null) return;

            ConfigLoader loader = new ConfigLoader();
            AppConfig cfg = loader.load(configPath);
            cfg.getVisemeImages().put(BLINK_KEY, f.getAbsolutePath());
            loader.save(configPath, cfg);
            loadVisemeImages(cfg);
            startBlinkTimeline();
            LOG.info("Imagen de parpadeo reemplazada");
        } catch (RuntimeException e) {
            LOG.error("No se pudo reemplazar la imagen de parpadeo", e);
        }
    }

    /** Quita la imagen de parpadeo y detiene el parpadeo. */
    private void removeBlinkImage(Path configPath) {
        try {
            ConfigLoader loader = new ConfigLoader();
            AppConfig cfg = loader.load(configPath);
            cfg.getVisemeImages().remove(BLINK_KEY);
            loader.save(configPath, cfg);
            loadVisemeImages(cfg);
            if (blinkTimeline != null) {
                blinkTimeline.stop();
                blinkTimeline = null;
            }
            LOG.info("Imagen de parpadeo quitada; parpadeo desactivado");
        } catch (RuntimeException e) {
            LOG.error("No se pudo quitar la imagen de parpadeo", e);
        }
    }

    // ===================== Imágenes por letra =====================

    /**
     * Abre un selector de archivos PNG/JPG y guarda las rutas elegidas en la
     * configuración (clave "pending"). El usuario luego las asigna a una letra
     * desde el submenú "Asignar a 'X'".
     *
     * <p>Para simplificar la UX: al elegir archivos, se guardan en la config
     * bajo la clave {@code "__pending__"} como lista separada por coma.
     */
    private void openImageFilePicker(Path configPath) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Agregar imágenes para las letras");
        chooser.setInitialDirectory(new File(System.getProperty("user.home")));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Imágenes", "*.png", "*.jpg", "*.jpeg", "*.webp"));
        List<File> files = chooser.showOpenMultipleDialog(stage != null ? stage : null);
        if (files == null || files.isEmpty()) {
            return;
        }
        try {
            ConfigLoader loader = new ConfigLoader();
            AppConfig cfg = loader.load(configPath);
            Map<String, String> map = cfg.getVisemeImages();
            // Guardamos en una clave especial "pendiente" para que el usuario
            // la asigne a una letra después.
            map.put("__pending__", String.join("|", files.stream().map(File::getAbsolutePath).toList()));
            loader.save(configPath, cfg);
            LOG.info("Añadidas {} imagen(es) a la lista de pendientes", files.size());

            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.INFORMATION,
                    "Se añadieron " + files.size() + " imagen(es).\n"
                    + "Ahora usa 'Imágenes → Asignar a <letra>' para elegir a qué letra corresponde cada una.",
                    javafx.scene.control.ButtonType.OK);
            alert.showAndWait();
        } catch (RuntimeException e) {
            LOG.error("No se pudieron guardar las imágenes", e);
        }
    }

    /**
     * Asigna una de las imágenes "pendientes" al viseme indicado. Si no hay
     * pendientes, abre el selector de archivos y asigna la primera elegida.
     */
    private void assignImageToViseme(Viseme v, Path configPath) {
        try {
            ConfigLoader loader = new ConfigLoader();
            AppConfig cfg = loader.load(configPath);
            Map<String, String> map = cfg.getVisemeImages();

            String pending = map.get("__pending__");
            if (pending == null || pending.isBlank()) {
                // No hay pendientes: abrir selector y asignar la primera.
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Elegir imagen para '" + displayName(v) + "'");
                chooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("Imágenes", "*.png", "*.jpg", "*.jpeg", "*.webp"));
                File f = chooser.showOpenDialog(stage != null ? stage : null);
                if (f == null) return;
                map.put(v.name(), f.getAbsolutePath());
            } else {
                // Tomar la primera pendiente y quitarla de la lista.
                String[] parts = pending.split("\\|");
                map.put(v.name(), parts[0]);
                if (parts.length > 1) {
                    map.put("__pending__", String.join("|", java.util.Arrays.copyOfRange(parts, 1, parts.length)));
                } else {
                    map.remove("__pending__");
                }
            }

            loader.save(configPath, cfg);

            // Recargar en memoria y aplicar visualmente.
            loadVisemeImages(cfg);
            applyViseme(v);
            LOG.info("Imagen asignada al viseme {}", v);
        } catch (RuntimeException e) {
            LOG.error("No se pudo asignar la imagen al viseme {}", v, e);
        }
    }

    /**
     * Quita la última imagen asignada (la última del mapa, excluyendo
     * "pendientes").
     */
    private void removeLastImage(Path configPath) {
        try {
            ConfigLoader loader = new ConfigLoader();
            AppConfig cfg = loader.load(configPath);
            Map<String, String> map = cfg.getVisemeImages();
            // Buscar la última clave que no sea __pending__ ni BLINK.
            String lastKey = null;
            for (String key : map.keySet()) {
                if (!"__pending__".equals(key) && !BLINK_KEY.equals(key)) {
                    lastKey = key;
                }
            }
            if (lastKey == null) {
                LOG.info("No hay imágenes asignadas para quitar");
                return;
            }
            map.remove(lastKey);
            loader.save(configPath, cfg);
            loadVisemeImages(cfg);
            LOG.info("Quitada imagen asignada: {}", lastKey);
        } catch (RuntimeException e) {
            LOG.error("No se pudo quitar la imagen", e);
        }
    }

    /** Convierte una clave de config a Viseme, o null si no se reconoce. */
    private static Viseme parseViseme(String name) {
        if (name == null) return null;
        try {
            return Viseme.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Convierte un hex a Color; devuelve fallback si no es válido. */
    private static Color safeColor(String hex, Color fallback) {
        if (hex == null) return fallback;
        try {
            return Color.web(hex);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Estilo CSS de los botones del panel derecho. */
    private static String panelButtonStyle(String hex) {
        return "-fx-background-color: " + hex + ";"
                + "-fx-text-fill: #1e1e2e;"
                + "-fx-font-weight: bold;"
                + "-fx-padding: 8 18 8 18;"
                + "-fx-cursor: hand;";
    }

    /**
     * Quita la imagen asignada a la <b>letra actual</b> (la última viseme
     * mostrada) y persiste el cambio.
     */
    private void removeCurrentLetterImage(Path configPath) {
        try {
            ConfigLoader loader = new ConfigLoader();
            AppConfig cfg = loader.load(configPath);
            cfg.getVisemeImages().remove(lastShownViseme.name());
            loader.save(configPath, cfg);
            loadVisemeImages(cfg);
            applyViseme(lastShownViseme);
            LOG.info("Quitada imagen de la letra actual: {}", lastShownViseme);
        } catch (RuntimeException e) {
            LOG.error("No se pudo quitar la imagen de la letra actual", e);
        }
    }

    /**
     * Abre el selector de archivos y reemplaza la imagen asignada a la
     * <b>letra actual</b>, persistiendo el cambio.
     */
    private void replaceCurrentLetterImage(Path configPath) {
        try {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Reemplazar imagen de '" + displayName(lastShownViseme) + "'");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Imágenes", "*.png", "*.jpg", "*.jpeg", "*.webp"));
            File f = chooser.showOpenDialog(stage != null ? stage : null);
            if (f == null) {
                return;
            }
            ConfigLoader loader = new ConfigLoader();
            AppConfig cfg = loader.load(configPath);
            cfg.getVisemeImages().put(lastShownViseme.name(), f.getAbsolutePath());
            loader.save(configPath, cfg);
            loadVisemeImages(cfg);
            applyViseme(lastShownViseme);
            LOG.info("Imagen reemplazada para la letra actual: {}", lastShownViseme);
        } catch (RuntimeException e) {
            LOG.error("No se pudo reemplazar la imagen de la letra actual", e);
        }
    }

    @Override
    public void stop() {
        stopMic();
        if (blinkTimeline != null) {
            blinkTimeline.stop();
        }
        LOG.info("App UI detenida");
    }

    /** Punto de entrada independiente (para ejecutar solo la UI sin Main). */
    public static void main(String[] args) {
        launch(PngVoiceApp.class, args);
    }

    /**
     * Resuelve la ruta (absoluta y estable) del archivo de configuración.
     *
     * <p>Antes se usaba una ruta <b>relativa</b> ({@code config/app.json}) que
     * dependía del directorio de trabajo, por lo que al lanzar la app desde un
     * directorio distinto (IDE vs. JAR por doble clic) se leía/escría otro
     * archivo y las imágenes asignadas parecían "olvidarse". Ahora se ancla a
     * la carpeta del usuario: {@code ~/.pngvoice/config/app.json}.
     *
     * <p>Si existe una config legacy en la ruta relativa del directorio actual
     * (y aún no hay una en la nueva ubicación), se <b>migra</b> a la nueva
     * ruta para no perder las imágenes asignadas.
     */
    private Path resolveConfigPath() {
        Path stable = Paths.get(System.getProperty("user.home"), ".pngvoice", "config", "app.json");
        Path legacy = Paths.get("config", "app.json");

        if (!Files.exists(stable) && Files.exists(legacy) && Files.isRegularFile(legacy)) {
            try {
                Files.createDirectories(stable.getParent());
                Files.copy(legacy, stable, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Config migrada de {} a {}", legacy.toAbsolutePath(), stable);
            } catch (IOException e) {
                LOG.warn("No se pudo migrar la config legacy; se usará la nueva ruta", e);
            }
        }
        return stable;
    }
}
