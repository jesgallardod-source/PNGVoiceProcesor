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
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.PNGV.audio.AudioCapture;
import org.PNGV.audio.AudioFrameProcessor;
import org.PNGV.audio.MicSelector;
import org.PNGV.audio.VolumeAnalyzer;
import org.PNGV.config.AppConfig;
import org.PNGV.config.ConfigLoader;
import org.PNGV.logging.LetterLogger;
import org.PNGV.phoneme.Viseme;
import org.PNGV.phoneme.VoskRecognizer;
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
 *   <li>Avatar centrado que reacciona al volumen (imágenes por viseme).</li>
 *   <li>Barra de volumen + estado del micrófono + botón iniciar/detener.</li>
 * </ul>
 */
public class PngVoiceApp extends javafx.application.Application {

    private static final Logger LOG = LoggerFactory.getLogger(PngVoiceApp.class);

    /**
     * Tamaño (px) del área del avatar / imágenes propias. La ventana es 1024x768
     * y el avatar ocupa la mayor parte del centro para verse grande.
     */
    private static final double AVATAR_SIZE = 640;

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
    private static final long MIDDLE_MS = 200;

    /**
     * Visemes (grupos de letras) que se muestran como imágenes propias.
     * Cada grupo comparte una única imagen de referencia.
     * El orden importa: es el orden en el que aparecen en el menú "Imágenes".
     */
    private static final List<Viseme> IMAGE_VISEMES = List.of(
            Viseme.NEUTRAL, Viseme.A, Viseme.E, Viseme.I, Viseme.O, Viseme.U,
            Viseme.BMP, Viseme.FV, Viseme.L, Viseme.S,
            // Gritos: al final del panel lateral / menú. No se rotan
            // aleatoriamente en el habla normal; se muestran solo cuando el
            // micrófono detecta un nivel de decibeles muy alto (ver tick).
            // Al dispararse un grito, el avatar elige una de las variantes
            // disponibles y la mantiene durante el episodio; al volver a
            // gritar (nuevo cruce del umbral) rota a la siguiente variante.
            Viseme.GRITO, Viseme.GRITO2, Viseme.GRITO3);

    /** Clave especial en el mapa de imágenes para la imagen de parpadeo. */
    private static final String BLINK_KEY = "BLINK";

    /**
     * Entrada de una "letra" administrable en el panel lateral: puede ser un
     * viseme (con su {@link Viseme}) o la imagen de parpadeo (sin viseme).
     * {@code key} es la clave usada en la config; {@code displayName} el texto
     * legible; {@code viseme} es null en el caso del parpadeo.
     */
    private record LetterEntry(String key, String displayName, Viseme viseme) {}

    /**
     * Lista ordenada de entradas administrables del panel lateral. El
     * <b>parpadeo</b> va al principio, seguido de los visemes/letras.
     */
    private static final List<LetterEntry> LETTER_ENTRIES = buildLetterEntries();

    private static List<LetterEntry> buildLetterEntries() {
        List<LetterEntry> list = new ArrayList<>();
        list.add(new LetterEntry(BLINK_KEY, "parpadeo", null));
        for (Viseme v : IMAGE_VISEMES) {
            list.add(new LetterEntry(v.name(), displayName(v), v));
        }
        return list;
    }

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
    /**
     * Reconocedor de fonemas (Vosk) conectado al pipeline de audio.
     * Es {@code null} cuando el modelo no está disponible y se usa el
     * fallback aleatorio por volumen.
     */
    private VoskRecognizer voskRecognizer;
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

    // ---- Estado del grito (ver tick) ----
    /** true mientras el avatar está en un "episodio" de grito activo. */
    private boolean inScream = false;
    /** Viseme de grito elegido para el episodio actual (se mantiene hasta que se detenga). */
    private Viseme currentScream = Viseme.GRITO;

    // Nodos UI
    private ProgressBar volumeBar;
    private Label statusLabel;
    private Label visemeLabel;
    private Button toggleButton;
    private Button togglePanelButton;   // muestra/oculta el panel lateral de letras
    private Button vibrationButton;     // activa/desactiva la vibración del avatar
    private ComboBox<String> micBox;    // selector de micrófono (dropdown)
    /** true si la vibración (agitamiento) del avatar está habilitada. */
    private boolean vibrationEnabled = true;
    private Label rightLetterLabel;    // etiqueta del panel derecho con la letra actual

    // ---- Panel lateral (slider) de administración de imágenes por letra ----
    private Pane letterPanel;               // el panel lateral (para mostrar/ocultar)
    private boolean letterPanelVisible = true;
    private int letterPanelIndex = 0;       // posición actual dentro de LETTER_ENTRIES
    private Slider letterSlider;            // slider de todas las letras
    private ImageView letterPreview;        // previsualización grande de la imagen
    private Label letterPreviewLabel;       // nombre de la letra/viseme en foco
    private Label letterCountLabel;         // contador "n / total"
    private Label letterPathLabel;          // ruta del archivo asignado (o "sin imagen")

    private Scene scene;
    private Stage stage;

    // Imágenes propias del usuario (una por viseme/letra)
    private Pane imageLayer;                       // contenedor de la ImageView
    private ImageView imageHolder;                 // imagen actual mostrada
    private final Map<Viseme, Image> visemeImages = new HashMap<>(); // viseme -> imagen cargada
    private boolean usingImages = false;           // true si hay al menos una imagen asignada

    // ---- Agitamiento ligero del avatar mientras habla ----
    private javafx.scene.transform.Translate imageWiggleTranslate;
    private javafx.scene.transform.Rotate      imageWiggleRotate;
    /** Amplitud actual (0..1) del agitamiento: 1 = pleno, 0 = en reposo. */
    private double imageWiggleAmp = 0.0;
    /** Fase de la onda sinusoidal que produce el temblor. */
    private double imageWigglePhase = 0.0;
    /** Marca (ns) del último frame para calcular dt. */
    private long   imageWiggleLastNow = -1L;

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

        // ---- Avatar: capa de imágenes propias del usuario ----
        // Se muestra cuando el usuario asigna una imagen a un viseme.
        // La imagen se centra y escala para cubrir el área AVATAR_SIZE.
        Pane avatar = new Pane();
        avatar.setPrefSize(AVATAR_SIZE, AVATAR_SIZE);
        avatar.setMinSize(AVATAR_SIZE, AVATAR_SIZE);
        avatar.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);

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

        // Transformaciones de "agitamiento" ligero (pequeño temblor continuo
        // que se aplica mientras el avatar cambia de letra / habla).
        imageWiggleTranslate = new javafx.scene.transform.Translate(0, 0);
        // La rotación se ancla en el <b>centro inferior</b> del avatar (no en
        // el centro geométrico): así la <b>parte superior</b> (la "cabeza") se
        // mece más hacia los lados, como un vaivén de cabeza, mientras la base
        // queda casi fija. El desplazamiento (Translate) sigue siendo global.
        imageWiggleRotate = new javafx.scene.transform.Rotate(0,
                AVATAR_SIZE / 2, AVATAR_SIZE);
        imageLayer.getTransforms().addAll(imageWiggleTranslate, imageWiggleRotate);

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

        // ---- Botón de vibración (agitamiento del avatar al hablar) ----
        // Inicializado según la configuración persistida.
        vibrationEnabled = config.isVibrationEnabled();
        vibrationButton = new Button("Vibración: ON");
        vibrationButton.setStyle(panelButtonStyle(vibrationEnabled ? "#a6e3a1" : "#f38ba8"));
        vibrationButton.setOnAction(e -> toggleVibration(configPath));

        // ---- Selector de micrófono (dropdown) ----
        // Enumera los micrófonos de entrada disponibles (dispositivos de
        // captura del SO) + una entrada "(Predeterminado del sistema)" que
        // representa el default. Al cambiar la selección se persiste en la
        // config y, si el micrófono está activo, se reinicia para usar el
        // nuevo dispositivo.
        micBox = new ComboBox<>();
        List<String> micNames = MicSelector.listMicNames();
        micBox.getItems().addAll(micNames);
        String currentMic = config.getSelectedMic();
        if (currentMic == null || currentMic.isEmpty()
                || !micNames.contains(currentMic)) {
            micBox.setValue(MicSelector.DEFAULT_MIC);
        } else {
            micBox.setValue(currentMic);
        }
        micBox.setMaxWidth(260);
        micBox.setStyle(
                "-fx-background-color: #2a2a3a;"
                + "-fx-text-fill: white;"
                + "-fx-focus-color: #89b4fa;"
                + "-fx-font-size: 12px;"
                + ".combo-box-list-cell: -fx-text-fill: white; -fx-background-color: #2a2a3a;"
                + ".combo-box-list-cell:hover: -fx-background-color: #3a3a4a;"
                + ".combo-box-list-cell:selected: -fx-background-color: #4a4a5a;");
        micBox.setPromptText("Micrófono");
        micBox.valueProperty().addListener((obs, oldVal, newVal) -> onMicSelected(newVal));

        // ---- Fila de botones: iniciar/stop + micrófono + vibración (+ mostrar/ocultar panel) ----
        HBox controls = new HBox(12,
                toggleButton,
                micBox,
                vibrationButton);
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

        // ---- Panel lateral (slider) de administración de imágenes por letra ----
        letterPanel = buildLetterPanel(config, configPath);

        // ---- Botón para mostrar/ocultar el panel lateral ----
        togglePanelButton = new Button("Ocultar panel");
        togglePanelButton.setStyle(panelButtonStyle("#89b4fa"));
        togglePanelButton.setOnAction(e -> toggleLetterPanel());
        controls.getChildren().add(togglePanelButton);

        // ---- Root: menú arriba + (avatar + panel) al centro + controles abajo ----
        // El avatar (640 px) y el panel lateral (≈300 px) caben en 1024 px de ancho.
        HBox center = new HBox(avatar, letterPanel);
        center.setAlignment(Pos.CENTER);

        BorderPane root = new BorderPane();
        root.setTop(menuBar);
        root.setCenter(center);
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
                updateImageWiggle(now);
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
            // Con reconocedor, la primera imagen sale de la cola de letras;
            // si la cola está vacía se usa el fallback aleatorio por volumen.
            currentVisible = nextVisemeOrPick(level);
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
                    currentVisible = nextVisemeOrPickDifferent(level, currentVisible);
                    visibleImageStartNanos = now;
                }
            } else {
                // Ritmo normal: rotamos cada MIDDLE_MS, sin repetir la misma imagen.
                if (elapsedMs(now, visibleImageStartNanos) >= MIDDLE_MS) {
                    currentVisible = nextVisemeOrPickDifferent(level, currentVisible);
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

        // Grito: se muestra SOLO cuando el micrófono llega al <b>tope</b> de
        // decibeles (normalizado >= GRITO_NORM_THRESHOLD, cercano a escala
        // completa / ~-20 dBFS). NO se dispara al hablar normal: ese rango
        // queda muy por debajo del umbral. Si el usuario asignó alguna imagen
        // de grito, se muestra por encima del viseme normal de habla; si no,
        // se conserva el viseme habitual (el avatar no desaparece al gritar).
        //
        // Rotación entre variantes: el avatar puede tener hasta 3 imágenes de
        // grito (GRITO, GRITO2, GRITO3). Al <b>iniciar</b> un episodio de grito
        // se elige una al azar entre las disponibles y se <b>mantiene</b>
        // mientras el grito continúe. Si el grito se detiene (cae por debajo
        // del umbral) y luego vuelve a cruzarlo, se elige otra variante
        // distinta si es posible, para dar sensación de "variación".
        boolean screamNow = isTalking
                && normalized >= GRITO_NORM_THRESHOLD
                && !availableScreams().isEmpty();
        if (screamNow) {
            if (!inScream) {
                // Nuevo episodio de grito: elegir una variante (distinta a la
                // anterior si hay al menos dos disponibles).
                List<Viseme> pool = availableScreams();
                Viseme pick;
                if (pool.size() == 1) {
                    pick = pool.get(0);
                } else {
                    int idx;
                    do {
                        idx = random.nextInt(pool.size());
                    } while (pool.get(idx) == currentScream);
                    pick = pool.get(idx);
                }
                currentScream = pick;
                inScream = true;
            }
            toShow = currentScream;
        } else {
            inScream = false;
        }

        // Solo redibujamos si cambió la imagen visible (evita trabajo inútil).
        if (toShow != currentVisible) {
            currentVisible = toShow;
        }
        applyViseme(currentVisible);
        volumeBar.setProgress(normalized);
        visemeLabel.setText("Viseme: " + currentVisible);
    }

    /**
     * Devuelve la lista (en orden fijo GRITO → GRITO2 → GRITO3) de las
     * <b>variantes de grito que tienen imagen asignada</b>. Se usa para saber
     * si hay alguna imagen de grito disponible y, en su caso, rotar entre
     * variantes distintas al iniciar un episodio de grito (ver
     * {@link #tick(int, double, long)}).
     *
     * @return lista de visemes de grito con imagen cargada (puede estar vacía)
     */
    private List<Viseme> availableScreams() {
        List<Viseme> pool = new ArrayList<>(3);
        if (visemeImages.containsKey(Viseme.GRITO))  pool.add(Viseme.GRITO);
        if (visemeImages.containsKey(Viseme.GRITO2)) pool.add(Viseme.GRITO2);
        if (visemeImages.containsKey(Viseme.GRITO3)) pool.add(Viseme.GRITO3);
        return pool;
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

    /**
     * Devuelve el siguiente viseme <b>de la cola del reconocedor</b> (en orden de
     * aparición). Si no hay reconocedor o la cola está vacía, cae al fallback
     * aleatorio por volumen ({@link #pickTalkingViseme(int)}).
     *
     * <p>La cola solo se consume (poll) cuando hay algo realmente pendiente, para
     * no "quemar" letras reconocidas cuando el fallback ya es suficiente.
     *
     * @param level nivel de volumen (para el fallback)
     * @return el viseme a mostrar
     */
    private Viseme nextVisemeOrPick(int level) {
        if (voskRecognizer != null) {
            Viseme v = voskRecognizer.pollViseme();
            if (v != null) {
                return v;
            }
        }
        return pickTalkingViseme(level);
    }

    /**
     * Devuelve el siguiente viseme <b>de la cola del reconocedor</b>, evitando
     * repetir el que ya está visible. Si la cola no tiene un viseme distinto
     * (o no hay reconocedor), cae al fallback aleatorio por volumen.
     *
     * @param level  nivel de volumen (para el fallback)
     * @param avoid  viseme que ya está visible (para no repetirlo)
     * @return el viseme a mostrar
     */
    private Viseme nextVisemeOrPickDifferent(int level, Viseme avoid) {
        if (voskRecognizer != null) {
            Viseme v = voskRecognizer.pollViseme();
            if (v != null && v != avoid) {
                return v;
            }
            // Si el viseme de la cola repite el actual, se descarta (ya se
            // consumió) y se usa el fallback; la cola se irá drenando sola.
        }
        return pickDifferentTalkingViseme(level, avoid);
    }

    /** Milisegundos transcurridos desde una marca de tiempo del AnimationTimer. */
    private static long elapsedMs(long now, long start) {
        if (start <= 0) return Long.MAX_VALUE;
        return (now - start) / 1_000_000L;
    }

    /** Cambia la imagen del avatar según el viseme. */
    private void applyViseme(Viseme v) {
        // Registrar en el logger de letras (usa System.nanoTime como referencia)
        letterLogger.record(v, System.nanoTime());
        if (rightLetterLabel != null) {
            rightLetterLabel.setText("Letra actual: " + displayName(v));
        }
        // No sobrescribir la imagen durante un parpadeo activo.
        if (blinking) {
            return;
        }
        // Si el usuario asignó una imagen a este viseme, la muestra;
        // si no, se oculta la imagen.
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
    }

    /**
     * Agitamiento ligero del avatar mientras el micrófono está activo y el
     * avatar está cambiando de letra (hablando). Se modela como una amplitud
     * {@code 0..1} que <b>crece</b> mientras habla y <b>decae</b>
     * exponencialmente en silencio, multiplicando un temblor sinusoidal
     * pequeño (±{@link #WIGGLE_MAX_PX} px y ±{@link #WIGGLE_MAX_DEG}°).
     * El desplazamiento es global; la rotación se ancla en la <b>base</b> del
     * avatar, de modo que la parte superior (la "cabeza") se mece más hacia
     * los lados.
     *
     * <p>Es un movimiento "orgánico" (dos senos con fases distintas) para que
     * no se note como un simple vaivén recto, y queda tan sutil que no
     * distrae: el objetivo es dar vida a las imágenes sin que se vean
     * "temblorosas" ni inestables.
     *
     * <p><b>Excepción del grito:</b> cuando el avatar está en un episodio de
     * grito activo ({@link #inScream}), la vibración se aplica <b>siempre</b>,
     * incluso si el usuario desactivó el botón "Vibración" (que solo controla
     * el agitamiento durante el habla normal). Así el grito se siente "fuerte"
     * de forma consistente.
     *
     * @param now marca de tiempo del {@link AnimationTimer} (ns)
     */
    private void updateImageWiggle(long now) {
        if (imageWiggleTranslate == null) {
            return;   // aún no se ha construido la UI (no debería ocurrir)
        }
        // Si la vibración está desactivada Y no hay un grito activo, el avatar
        // permanece quieto: amplitud a cero y transformaciones neutras.
        //
        // Excepción: cuando el avatar está en un episodio de grito (inScream)
        // la imagen debe <b>SIEMPRE</b> vibrar, sin depender del botón de
        // vibración. Así el grito se siente "fuerte" aunque el usuario tenga
        // la vibración normal apagada.
        if (!vibrationEnabled && !inScream) {
            imageWiggleAmp = 0.0;
            if (imageWiggleTranslate.getX() != 0 || imageWiggleTranslate.getY() != 0
                    || imageWiggleRotate.getAngle() != 0) {
                imageWiggleTranslate.setX(0);
                imageWiggleTranslate.setY(0);
                imageWiggleRotate.setAngle(0);
            }
            return;
        }
        // dt en segundos (protegido de un primer frame o saltos muy grandes).
        double dt;
        if (imageWiggleLastNow <= 0) {
            dt = 1.0 / 60.0;
        } else {
            dt = Math.min(0.1, (now - imageWiggleLastNow) / 1_000_000_000.0);
        }
        imageWiggleLastNow = now;

        // Amplitud objetivo: 1.0 mientras habla O mientras hay un grito activo
        // (el grito SIEMPRE vibra, incluso con el botón de vibración OFF);
        // 0.0 en silencio.
        double target = (isTalking || inScream) ? 1.0 : 0.0;
        if (target > imageWiggleAmp) {
            // Crece de forma suave (~0.3 s para llegar a pleno).
            imageWiggleAmp = Math.min(target, imageWiggleAmp + dt / 0.3);
        } else {
            // Decae exponencialmente: ~0.5 s hasta casi 0.
            imageWiggleAmp *= Math.pow(0.05, dt);   // base 0.05 en 1 s
            if (imageWiggleAmp < 0.005) imageWiggleAmp = 0.0;
        }

        // Avanza la fase (≈ 4.0 Hz, un "temblor" algo más rápido y orgánico).
        imageWigglePhase += dt * 2.0 * Math.PI * 4.0;

        if (imageWiggleAmp <= 0.0) {
            // En reposo absoluto: asegura offsets a cero (idempotente).
            if (imageWiggleTranslate.getX() == 0 && imageWiggleTranslate.getY() == 0
                    && imageWiggleRotate.getAngle() == 0) {
                return;
            }
            imageWiggleTranslate.setX(0);
            imageWiggleTranslate.setY(0);
            imageWiggleRotate.setAngle(0);
            return;
        }

        // Dos senos con fases distintas → el desplazamiento describe una
        // pequeña "elipse" (movimiento menos predecible que un vaivén lineal).
        double dx = Math.sin(imageWigglePhase)              * WIGGLE_MAX_PX * imageWiggleAmp;
        double dy = Math.sin(imageWigglePhase * 1.37 + 1.1) * WIGGLE_MAX_PX * imageWiggleAmp;
        double da = Math.sin(imageWigglePhase * 0.83 + 2.0) * WIGGLE_MAX_DEG * imageWiggleAmp;

        imageWiggleTranslate.setX(dx);
        imageWiggleTranslate.setY(dy);
        imageWiggleRotate.setAngle(da);
    }

    /**
     * Umbral de volumen <b>normalizado (0..1)</b> a partir del cual el avatar
     * muestra la imagen de <b>grito</b>. Se elige un valor muy alto (0.85)
     * para que el grito solo aparezca cuando el micrófono llega casi al
     * <b>tope</b> de decibeles (~-20 dBFS, escala completa), y <b>NO</b> al
     * hablar normal (que suele rondar 0.3..0.6). Ver
     * {@link org.PNGV.audio.VolumeAnalyzer#getNormalizedLevel()}.
     */
    private static final double GRITO_NORM_THRESHOLD = 0.96;

    /** Amplitud máxima (px) del desplazamiento del avatar en pleno "agitamiento". */
    private static final double WIGGLE_MAX_PX  = 2.0;
    /** Amplitud máxima (grados) de rotación del avatar en pleno "agitamiento". */
    private static final double WIGGLE_MAX_DEG = 1.2;

    /**
     * Apaga el agitamiento (amplitud a 0 y transformaciones a su estado
     * neutro). Se llama al detener el micrófono, porque el
     * {@link AnimationTimer} deja de ejecutarse cuando no hay captura y la
     * amplitud no podría decaer por sí sola.
     */
    private void resetImageWiggle() {
        imageWiggleAmp = 0.0;
        imageWigglePhase = 0.0;
        imageWiggleLastNow = -1L;
        if (imageWiggleTranslate != null) {
            imageWiggleTranslate.setX(0);
            imageWiggleTranslate.setY(0);
        }
        if (imageWiggleRotate != null) {
            imageWiggleRotate.setAngle(0);
        }
    }

    private void toggleMic() {
        if (capture == null) {
            startMic();
        } else {
            stopMic();
        }
    }

    /**
     * Alterna la <b>vibración</b> (agitamiento) del avatar mientras habla.
     * Actualiza el texto/color del botón, aplica el efecto de inmediato y
     * persiste el estado en la configuración.
     *
     * @param configPath ruta del archivo de configuración a actualizar
     */
    private void toggleVibration(Path configPath) {
        vibrationEnabled = !vibrationEnabled;
        vibrationButton.setText("Vibración: " + (vibrationEnabled ? "ON" : "OFF"));
        vibrationButton.setStyle(panelButtonStyle(vibrationEnabled ? "#a6e3a1" : "#f38ba8"));
        // Si se desactiva, el avatar deja de moverse de inmediato.
        if (!vibrationEnabled) {
            resetImageWiggle();
        }
        // Persiste la preferencia.
        try {
            ConfigLoader loader = new ConfigLoader();
            AppConfig cfg = loader.load(configPath);
            cfg.setVibrationEnabled(vibrationEnabled);
            loader.save(configPath, cfg);
        } catch (RuntimeException e) {
            LOG.warn("No se pudo persistir el estado de la vibración", e);
        }
        LOG.info("Vibración del avatar: {}", vibrationEnabled ? "ON" : "OFF");
    }

    /**
     * Reacciona al cambio de selección del micrófono en el dropdown.
     *
     * <p>1) Persiste la selección en la config.
     * <p>2) Si el micrófono está activo ({@code capture != null}), lo reinicia
     *    (stop + start) para que el nuevo dispositivo tome efecto de inmediato.
     *    Si está detenido, simplemente guarda la preferencia para el próximo
     *    inicio.
     *
     * @param micName nombre del micrófono seleccionado (o {@link MicSelector#DEFAULT_MIC})
     */
    private void onMicSelected(String micName) {
        if (micName == null) {
            return;
        }
        // Normalizar: "(Predeterminado del sistema)" → null (default del SO).
        String toSave = MicSelector.DEFAULT_MIC.equals(micName) ? null : micName;

        // 1) Persistir.
        try {
            ConfigLoader loader = new ConfigLoader();
            AppConfig cfg = loader.load(configPath);
            cfg.setSelectedMic(toSave);
            loader.save(configPath, cfg);
        } catch (RuntimeException e) {
            LOG.warn("No se pudo persistir la selección de micrófono", e);
        }
        LOG.info("Micrófono seleccionado: {}", (toSave == null ? "default del sistema" : toSave));

        // 2) Si el micrófono está activo, reiniciarlo con el nuevo dispositivo.
        if (capture != null) {
            LOG.info("Micrófono activo: reiniciando para aplicar el nuevo dispositivo");
            stopMic();
            startMic();
        }
    }

    private void startMic() {
        try {
            AppConfig config = new ConfigLoader().load(configPath);
            VolumeAnalyzer analyzer = new VolumeAnalyzer(config);

            // Paso 1: intentar conectar VoskRecognizer si el modelo existe.
            // Si no, frameProcessor queda null y se usa el fallback por volumen.
            AudioFrameProcessor frameProcessor = null;
            if (VoskRecognizer.isModelAvailable(config)) {
                try {
                    voskRecognizer = new VoskRecognizer(config);
                    frameProcessor = voskRecognizer;
                    LOG.info("VoskRecognizer conectado al pipeline de audio");
                } catch (UnsatisfiedLinkError e) {
                    // La librería nativa de Vosk (JNI) no se pudo cargar: no hay
                    // binario compatible con el SO/CPU (o la extracción del jar
                    // falló). Es recuperable: la app sigue con el fallback por
                    // volumen, pero avisamos al usuario con un diálogo.
                    LOG.error("No se pudo cargar la librería nativa de Vosk (JNI)", e);
                    voskRecognizer = null;
                    showNativeModelError(e);
                } catch (Exception e) {
                    LOG.warn("No se pudo iniciar VoskRecognizer; se usará fallback por volumen", e);
                    voskRecognizer = null;
                    showNativeModelError(e);
                }
            } else {
                LOG.info("Modelo Vosk no disponible en {}; usando fallback por volumen",
                        config.getModelPath());
            }

            // Usar el micrófono seleccionado en el dropdown (null = default del SO).
            String micName = config.getSelectedMic();
            if (micName != null && !micName.isEmpty() && !MicSelector.DEFAULT_MIC.equals(micName)) {
                LOG.info("Iniciando captura con micrófono: {}", micName);
            } else {
                LOG.info("Iniciando captura con el micrófono por defecto del sistema");
            }
            AudioCapture cap = new AudioCapture(config, analyzer, frameProcessor, micName);
            cap.start();
            this.capture = cap;
            letterLogger.reset();
            resetImageWiggle();   // arranca con el avatar en reposo; crece al hablar
            statusLabel.setText("Micrófono: ON"
                    + (voskRecognizer != null ? " (Vosk)" : " (volumen)"));
            toggleButton.setText("Detener micrófono");
            LOG.info("Micrófono iniciado{}",
                    voskRecognizer != null ? " con Vosk" : " sin Vosk");
        } catch (RuntimeException e) {
            LOG.error("No se pudo iniciar el micrófono", e);
            statusLabel.setText("Error: " + e.getMessage());
            this.capture = null;
        }
    }

    /**
     * Muestra al usuario un diálogo de error cuando el modelo nativo (Vosk) no
     * pudo cargarse — ya sea por una {@link UnsatisfiedLinkError} (librería
     * JNI ausente/incompatible) u otra excepción — y le indica que la app
     * continuará en modo "fallback por volumen".
     *
     * <p>El texto del diálogo se construye con {@link #buildNativeModelErrorMessage(Throwable)}
     * para que sea fácil de testear sin necesidad de JavaFX.
     *
     * @param cause error original capturado al cargar el modelo
     */
    private void showNativeModelError(Throwable cause) {
        String message = buildNativeModelErrorMessage(cause);
        LOG.warn("Mostrando diálogo de error de carga de modelo: {}", message);
        try {
            Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
            alert.setTitle("No se pudo cargar el modelo de voz");
            alert.setHeaderText(
                    "La librería nativa de reconocimiento (Vosk) no se pudo cargar.\n"
                    + "PNGVoice continuará en modo fallback por volumen.");
            alert.initOwner(stage != null ? stage : null);
            alert.showAndWait();
        } catch (RuntimeException e) {
            // No queremos que el intento de mostrar el diálogo tumbe la app:
            // si falla (p. ej. sin toolkit JavaFX disponible), solo se loguea.
            LOG.error("No se pudo mostrar el diálogo de error del modelo", e);
        }
    }

    /**
     * Construye el mensaje legible que se muestra al usuario cuando el modelo
     * nativo no carga. Distingue el caso concreto de {@link UnsatisfiedLinkError}
     * (binario JNI incompatible/ausente) del resto de errores.
     *
     * <p>Es un método {@code static} y {@code public} para poder probarlo sin
     * iniciar JavaFX.
     *
     * @param cause error original (puede ser {@code null})
     * @return mensaje de error en español, listo para mostrarse
     */
    public static String buildNativeModelErrorMessage(Throwable cause) {
        StringBuilder sb = new StringBuilder();
        if (cause instanceof UnsatisfiedLinkError) {
            sb.append("No se encontró una librería nativa compatible para el sistema operativo "
                    + "y arquitectura actuales.\n\n")
              .append("Verifica que:\n")
              .append("  • La librería nativa de Vosk exista para tu plataforma (OS/CPU).\n")
              .append("  • Estés ejecutando la versión correcta del JAR (fat-jar con dependencias).\n");
        } else if (cause != null) {
            sb.append("Ocurrió un error al cargar el modelo de reconocimiento de voz.\n\n");
        }
        if (cause != null && cause.getMessage() != null) {
            sb.append("Detalle: ").append(cause.getMessage());
        }
        return sb.toString();
    }

    private void stopMic() {
        if (capture != null) {
            letterLogger.stop();
            capture.close();
            capture = null;
        }
        if (voskRecognizer != null) {
            try {
                voskRecognizer.close();
            } catch (Exception e) {
                LOG.warn("Error al cerrar VoskRecognizer: {}", e.getMessage());
            }
            voskRecognizer = null;
        }
        statusLabel.setText("Micrófono: OFF");
        toggleButton.setText("Iniciar micrófono");
        applyViseme(Viseme.NEUTRAL);
        volumeBar.setProgress(0.0);
        visemeLabel.setText("Viseme: NEUTRAL");
        resetImageWiggle();
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

    // ===================== Panel lateral (slider) de letras =====================

    /**
     * Construye el <b>panel lateral</b> que permite <b>visualizar</b> y
     * <b>administrar</b> las imágenes de todas las letras/visemes:
     * <ul>
     *   <li>Previsualización grande de la imagen del viseme actual.</li>
     *   <li><b>Slider</b> para recorrer todas las letras en orden.</li>
     *   <li>Botones <b>Anterior / Siguiente</b> para saltar de letra en letra.</li>
     *   <li>Botones <b>Asignar / Reemplazar / Borrar</b> sobre la letra en foco.</li>
     * </ul>
     *
     * <p>Todo opera sobre el <b>viseme en foco</b> (el que muestra el slider),
     * de forma independiente al viseme que el avatar está dibujando ahora.
     */
    private Pane buildLetterPanel(AppConfig config, Path configPath) {
        int total = LETTER_ENTRIES.size();
        letterPanelIndex = 0;

        // ---- Título ----
        Label title = new Label("Imágenes de letras");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #cdd6f4;");

        // ---- Previsualización ----
        letterPreview = new ImageView();
        letterPreview.setPreserveRatio(true);
        letterPreview.setFitWidth(220);
        letterPreview.setFitHeight(220);
        letterPreview.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 12, 0.2, 0, 4);");
        StackPane previewBox = new StackPane(letterPreview);
        previewBox.setPrefSize(220, 220);
        previewBox.setStyle("-fx-background-color: #3a3a4a; -fx-background-radius: 8;");

        letterPreviewLabel = new Label();
        letterPreviewLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #a6e3a1;");

        letterCountLabel = new Label();
        letterCountLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #89b4fa;");

        letterPathLabel = new Label();
        letterPathLabel.setWrapText(true);
        letterPathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #9aa0b5;");

        // ---- Slider de todas las letras ----
        Slider slider = new Slider(0, total - 1, 0);
        letterSlider = slider;
        slider.setShowTickMarks(true);
        slider.setSnapToTicks(true);
        slider.setMaxWidth(Double.MAX_VALUE);
        slider.setStyle("-fx-control-inner-background: #2a2a3a;");
        slider.valueProperty().addListener((obs, oldV, newV) -> {
            int idx = newV.intValue();
            if (idx != letterPanelIndex) {
                letterPanelIndex = idx;
                refreshLetterPanel(configPath);
            }
        });

        // ---- Botones de navegación ----
        Button prevButton = new Button("◀ Anterior");
        prevButton.setStyle(panelButtonStyle("#74c7ec"));
        prevButton.setMaxWidth(Double.MAX_VALUE);
        prevButton.setOnAction(e -> moveLetterPanel(-1, configPath));

        Button nextButton = new Button("Siguiente ▶");
        nextButton.setStyle(panelButtonStyle("#74c7ec"));
        nextButton.setMaxWidth(Double.MAX_VALUE);
        nextButton.setOnAction(e -> moveLetterPanel(1, configPath));

        HBox navRow = new HBox(8, prevButton, nextButton);
        navRow.setHgrow(prevButton, javafx.scene.layout.Priority.ALWAYS);
        navRow.setHgrow(nextButton, javafx.scene.layout.Priority.ALWAYS);

        // ---- Botones de administración (sobre la entrada en foco) ----
        Button assignButton = new Button("Asignar");
        assignButton.setStyle(panelButtonStyle("#a6e3a1"));
        assignButton.setMaxWidth(Double.MAX_VALUE);
        assignButton.setOnAction(e -> assignForEntry(LETTER_ENTRIES.get(letterPanelIndex), configPath));

        Button replaceButton = new Button("Reemplazar");
        replaceButton.setStyle(panelButtonStyle("#fab387"));
        replaceButton.setMaxWidth(Double.MAX_VALUE);
        replaceButton.setOnAction(e -> replaceForEntry(LETTER_ENTRIES.get(letterPanelIndex), configPath));

        Button deleteButton = new Button("Borrar");
        deleteButton.setStyle(panelButtonStyle("#f38ba8"));
        deleteButton.setMaxWidth(Double.MAX_VALUE);
        deleteButton.setOnAction(e -> deleteForEntry(LETTER_ENTRIES.get(letterPanelIndex), configPath));

        HBox adminRow1 = new HBox(8, assignButton, replaceButton);
        adminRow1.setHgrow(assignButton, javafx.scene.layout.Priority.ALWAYS);
        adminRow1.setHgrow(replaceButton, javafx.scene.layout.Priority.ALWAYS);
        HBox adminRow2 = new HBox(8, deleteButton);
        adminRow2.setHgrow(deleteButton, javafx.scene.layout.Priority.ALWAYS);

        // ---- Ensamblado del panel ----
        VBox panel = new VBox(10,
                title,
                previewBox,
                letterPreviewLabel,
                letterCountLabel,
                slider,
                navRow,
                adminRow1,
                adminRow2,
                letterPathLabel);
        panel.setPadding(new Insets(12));
        panel.setStyle("-fx-background-color: #2b2b2b; -fx-background-radius: 10;");
        panel.setPrefWidth(280);
        panel.setMinWidth(260);

        // Estado inicial.
        refreshLetterPanel(configPath);
        return panel;
    }

    /**
     * Alterna la visibilidad del panel lateral de letras. Al ocultarlo se
     * desactiva también {@code managed} para que el avatar se recentre y
     * recupere todo el ancho disponible.
     */
    private void toggleLetterPanel() {
        letterPanelVisible = !letterPanelVisible;
        if (letterPanel != null) {
            letterPanel.setVisible(letterPanelVisible);
            letterPanel.setManaged(letterPanelVisible);
        }
        togglePanelButton.setText(letterPanelVisible ? "Ocultar panel" : "Mostrar panel");
        LOG.info("Panel de letras {}", letterPanelVisible ? "mostrado" : "ocultado");
    }

    /** Cambia la entrada en foco (±1) con repetición circular. */
    private void moveLetterPanel(int delta, Path configPath) {
        int total = LETTER_ENTRIES.size();
        letterPanelIndex = ((letterPanelIndex + delta) % total + total) % total;
        // Refleja la posición en el slider sin disparar de nuevo el listener
        // (refreshLetterPanel ya sincroniza el valor).
        refreshLetterPanel(configPath);
    }

    /**
     * Actualiza la previsualización, el nombre, el contador y el slider según
     * {@link #letterPanelIndex}, y muestra la ruta del archivo asignado.
     */
    private void refreshLetterPanel(Path configPath) {
        LetterEntry entry = LETTER_ENTRIES.get(letterPanelIndex);
        letterPreviewLabel.setText("Letra: " + entry.displayName());
        letterCountLabel.setText((letterPanelIndex + 1) + " / " + LETTER_ENTRIES.size());

        Image img = entryImage(entry);
        letterPreview.setImage(img);
        if (img != null) {
            letterPathLabel.setText(img.getUrl() != null ? img.getUrl() : "(en memoria)");
        } else {
            letterPathLabel.setText("Sin imagen asignada");
        }

        // Sincroniza el slider con la posición actual (evita bucles de listener:
        // el listener solo reacciona si el índice realmente cambió).
        if (letterSlider != null) {
            letterSlider.setValue(letterPanelIndex);
        }
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
            case GRITO   -> "grito";
            case GRITO2  -> "grito 2";
            case GRITO3  -> "grito 3";
            default      -> v.name();
        };
    }

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

    /** Devuelve la imagen cargada para una entrada (parpadeo o letra). */
    private Image entryImage(LetterEntry entry) {
        return entry.viseme() == null ? blinkImage : visemeImages.get(entry.viseme());
    }

    /** Asigna una imagen a la entrada en foco (parpadeo o letra). */
    private void assignForEntry(LetterEntry entry, Path configPath) {
        if (entry.viseme() == null) {
            assignBlinkImage(configPath);
        } else {
            assignImageToViseme(entry.viseme(), configPath);
        }
        refreshLetterPanel(configPath);
    }

    /** Reemplaza la imagen de la entrada en foco (parpadeo o letra). */
    private void replaceForEntry(LetterEntry entry, Path configPath) {
        if (entry.viseme() == null) {
            replaceBlinkImage(configPath);
        } else {
            replaceImageForViseme(entry.viseme(), configPath);
        }
        refreshLetterPanel(configPath);
    }

    /** Borra la imagen de la entrada en foco (parpadeo o letra). */
    private void deleteForEntry(LetterEntry entry, Path configPath) {
        if (entry.viseme() == null) {
            removeBlinkImage(configPath);
        } else {
            deleteImageForViseme(entry.viseme(), configPath);
        }
        refreshLetterPanel(configPath);
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
     * Reemplaza la imagen asignada al viseme indicado (si no hay ninguna, la
     * asigna) abriendo el selector de archivos. Persiste y actualiza el panel.
     */
    private void replaceImageForViseme(Viseme v, Path configPath) {
        try {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Reemplazar imagen de '" + displayName(v) + "'");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Imágenes", "*.png", "*.jpg", "*.jpeg", "*.webp"));
            File f = chooser.showOpenDialog(stage != null ? stage : null);
            if (f == null) {
                return;
            }
            ConfigLoader loader = new ConfigLoader();
            AppConfig cfg = loader.load(configPath);
            cfg.getVisemeImages().put(v.name(), f.getAbsolutePath());
            loader.save(configPath, cfg);
            loadVisemeImages(cfg);
            applyViseme(v);
            refreshLetterPanel(configPath);
            LOG.info("Imagen reemplazada para el viseme {}", v);
        } catch (RuntimeException e) {
            LOG.error("No se pudo reemplazar la imagen del viseme {}", v, e);
        }
    }

    /**
     * Borra la imagen asignada al viseme indicado. Persiste y actualiza el
     * panel (la letra queda "sin imagen asignada").
     */
    private void deleteImageForViseme(Viseme v, Path configPath) {
        try {
            ConfigLoader loader = new ConfigLoader();
            AppConfig cfg = loader.load(configPath);
            cfg.getVisemeImages().remove(v.name());
            loader.save(configPath, cfg);
            loadVisemeImages(cfg);
            applyViseme(v);
            refreshLetterPanel(configPath);
            LOG.info("Borrada imagen del viseme {}", v);
        } catch (RuntimeException e) {
            LOG.error("No se pudo borrar la imagen del viseme {}", v, e);
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
