# 🎤 PNGVoice — Plan de Desarrollo

> App local tipo **Veadotube**: muestra una imagen (PNG / sprite) que reacciona a tu voz.
> Funciones clave:
> 1. **Cambio de imagen según el volumen** de la voz (silencio / habla suave / grito).
> 2. **Reconocimiento de sílabas / fonemas** para animar la boca (visemes) según la sílaba pronunciada.

Todo en **Java**, con librerías sugeridas abajo. El proyecto ya usa **Maven** y **Java 26**.

---

## 🧩 Visión general del sistema

```
┌──────────────────────────────────────────────────────────────────────┐
│                          PNGVOICE  (JavaFX App)                       │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [Micrófono]                                                         │
│      │  (Java Sound API: TargetDataLine)                             │
│      ▼                                                               │
│  [Capturador de audio] ── PCM 16-bit, 44.1kHz, mono                  │
│      │                                                               │
│      ├──────────────────────────► [Analizador de VOLUMEN]            │
│      │                              │ RMS / dBFS                      │
│      │                              ▼                                 │
│      │                        [Niveles / Umbrales]                    │
│      │                        (silencio, bajo, medio, alto)           │
│      │                              │                                 │
│      └──────────────┐               ▼                                 │
│                     ▼        [Selector de SPRITE por nivel]          │
│        [Analizador de FONEMAS / SÍLABAS]                             │
│         (Mel-FCC + Kaldi/DeepSpeech, o ASR local)                    │
│              │                                                        │
│              ▼                                                        │
│         [Visemas / Viseme-Mapper]                                     │
│         (A, E, I, O, U, M, F, D, P, ...)                             │
│              │                                                        │
│              ▼                                                        │
│      [MOTOR DE ANIMACIÓN] ──► [JavaFX Canvas / ImageView]           │
│              │          (cambia sprite: base + boca + volumen)        │
│              ▼                                                        │
│        [UI: controles, sliders, selector de avatar, log]             │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 📚 Librerías sugeridas

| Área | Librería | Por qué |
|------|----------|---------|
| **UI** | **JavaFX 21+** (`org.openjfx:javafx-controls`, `javafx-graphics`) | Canvas/ImageView para renderizar el avatar y controles. Nativo, sin web. |
| **Audio (captura)** | **Java Sound API** (JDK) + **JLayer/JLayerMP3** si se necesita mp3 | `TargetDataLine` para leer el micrófono en tiempo real (PCM). Sin dependencias extra. |
| **DSP / volumen** | **JTransforms** (`com.github.wendykierp:JTransforms`) o **DSP4J** (`com.github.dsp4j:dsp4j`) | FFT, cálculo RMS, filtros. DSP4J es ideal para streams de audio. |
| **Fonemas / sílabas** | **Vosk** (`org.vosk:vosk`) | ASR **100% local**, sin internet, ligero, con API Java. Emite transcripción + confianza. |
| *(alternativa fonemas)* | **Kaldi** (`kaldi-asr` / binding) o **DeepSpeech** (TensorFlow Java) | Si se necesitan **fonemas exactos** (no solo palabras) para visemes precisos. |
| *(alternativa simple)* | **WebrtcVAD / WebRTC Java** | Detección de VAD (voz vs ruido) para activar/desactivar la animación. |
| **Sprites / animación** | JavaFX `Image` + `AnimationTimer` | Render de hojas de sprites. |
| **Config / persistencia** | **Jackson** (`com.fasterxml.jackson.core:jackson-databind`) | Guardar/cargar configuración (umbrales, avatar activo) en JSON. |
| **Logs** | **SLF4J + Logback** | Logs limpios. |
| **Testing** | **JUnit 5** + **Mockito** | Unit tests de analizador de volumen y mapeo de visemes. |

> **Nota sobre fonemas:** Vosk da transcripción a nivel de **palabra/sílaba** (soporta español). Para **visemes de precisión de estudio** (fonema a fonema) se recomienda **Kaldi** con un modelo que emita fonemas, o **DeepSpeech**. Para v1, el mapeo palabra→visemas de Vosk es suficiente y muy fácil.

---

## 🗂️ Estructura de paquetes propuesta

```
org.PNGV
├── Main.java                      // Entry point, inicia la app JavaFX
├── app
│   ├── Application.java           // Extiende javafx.application.Application
│   ├── Controller.java            // Glue entre audio, motor y UI
│   └── ui
│       ├── MainView.java          // Construye la escena (FXML o programático)
│       ├── AvatarCanvas.java      // Renderiza el avatar (sprites)
│       └── ControlPanel.java      // Sliders de volumen, selector de avatar, etc.
├── audio
│   ├── AudioCapture.java          // TargetDataLine, threads de lectura
│   ├── AudioBuffer.java           // Buffer circular de PCM
│   ├── VolumeAnalyzer.java        // RMS → dBFS → nivel (0..3)
│   └── AudioThread.java           // Thread de captura
├── phoneme
│   ├── PhonemeRecognizer.java     // Interfaz (Vosk / Kaldi)
│   ├── VoskRecognizer.java        // Implementación con Vosk
│   ├── Phoneme.java               // Enum de fonemas (A,E,I,O,U,M,F,D,P,NEUTRAL)
│   └── VisemeMapper.java          // Fonema → viseme (sprite de boca)
├── avatar
│   ├── Avatar.java                // Modelo: id, sprites por nivel, visemes
│   ├── AvatarSprite.java          // Un frame: imagen + rect en la hoja
│   ├── SpriteSheet.java           // Carga de hoja de sprites (PNG con atlas)
│   └── AvatarManager.java         // Gestiona avatares y estados actuales
├── engine
│   ├── AnimationEngine.java       // Loop: nivel de volumen + viseme → frame
│   └── StateMachine.java          // estados: IDLE, TALKING, SHOUT, SINGING
└── config
    ├── AppConfig.java             // POJO de configuración
    └── ConfigLoader.java          // Jackson load/save JSON
```

---

## 🎨 Modelo de sprites del avatar

Cada **avatar** se define por una **hoja de sprites** (PNG con atlas) organizada en:

- **Filas = niveles de volumen**: `0` silencio, `1` bajo, `2` medio, `3` alto (grito).
- **Columnas = visemes (boca)**: `NEUTRAL, A, E, I, O, U, M, F, D, P, B, ...`.

Ejemplo de atlas 4×12:

```
         viseme →  NEU  A  E  I  O  U  M  F  D  P  B  ...
vol 0    fila 0   ░░░  ░░░  ░░░  ░░░  ░░░  ░░░  ░░░  ░░░ ...
vol 1    fila 1   ███  AAA  EEE  III  OOO  UUU  MMM  FFF ...
vol 2    fila 2   ███  AAA  EEE  III  OOO  UUU  MMM  FFF ...
vol 3    fila 3   ███  AAA  EEE  III  OOO  UUU  MMM  FFF ...
              (más abierto, más "expresión" según volumen)
```

> El frame a mostrar = `fila(nivelVolumen) + columna(visemeActual)`.

---

## 📋 Plan de trabajo (hitos)

### ✅ Hito 1 — Base del proyecto (día 1)
- [ ] Configurar `pom.xml` con dependencias JavaFX, DSP4J, Vosk, Jackson, Logback.
- [ ] Crear `Application.java` y una ventana JavaFX mínima con un `Canvas`.
- [ ] Verificar build con `mvn package` y ejecución con `mvn javafx:run`.

### ✅ Hito 2 — Captura de audio + volumen (día 2-3)
- [ ] `AudioCapture`: `TargetDataLine` (44100 Hz, 16-bit, mono, 1024 samples).
- [ ] `VolumeAnalyzer`: RMS → dBFS → mapeo a nivel 0-3 con **umbrales configurables**.
- [ ] `AudioThread`: hilo de lectura que publica el nivel actual (AtomicReference).
- [ ] UI: **barra de volumen en vivo** (Label/ProgressBar) para validar.

### ✅ Hito 3 — Motor de animación por volumen (día 4)
- [ ] `SpriteSheet`: cargar atlas PNG, cortar frames por rect.
- [ ] `Avatar` + `AvatarManager`: definir 1 avatar de prueba (4 filas × N columnas).
- [ ] `AnimationEngine`: loop con `AnimationTimer` → elige fila según nivel, columna NEUTRAL.
- [ ] UI: mostrar el avatar cambiando de "expresión" con el volumen.

### ✅ Hito 4 — Reconocimiento de sílabas / fonemas (día 5-7)
- [ ] `VoskRecognizer`: modelo español (`vosk-model-small-es-0.42`), streaming.
- [ ] Parsear transcripción → **segmentar en sílabas** (reglas de acentuación española).
- [ ] `VisemeMapper`: sílaba/fonema → viseme (A, E, I, O, U, M, F, ...).
- [ ] `AnimationEngine`: ahora elige **fila (volumen) + columna (viseme)** en tiempo real.

### ✅ Hito 5 — Pulido y experiencia (día 8-10)
- [ ] **Estado machine**: IDLE (respiración sutil), TALKING, SHOUT, SINGING.
- [ ] **Smoothing** de volumen (exponential moving average) para evitar parpadeo.
- [ ] **Selector de avatares** (múltiples PNGs en carpeta `assets/avatars/`).
- [ ] **Config JSON**: umbrales, sensibilidad, avatar activo, micrófono.
- [ ] **Log** de eventos (sílabas detectadas, niveles) en consola + panel.
- [ ] **Empaquetado**: `jlink` + `maven-shade` o `jpackage` para instalador local.

### ✅ Hito 6 — Avanzado (opcional)
- [ ] Modelo de fonemas de **Kaldi/DeepSpeech** para visemes aún más precisos.
- [ ] **VAD** (WebRTC) para distinguir "hablando" vs "ruido ambiental".
- [ ] **Lip-sync por fonema exacto** (duración por fonema → timing de animación).
- [ ] **Exportar a video** (JavaFX Media + FFmpeg) para grabar sesiones.
- [ ] **Modo "karaoke"**: animación por pista de audio externa (no solo micrófono).

---

## 🧪 Estrategia de tests

| Módulo | Test |
|--------|------|
| `VolumeAnalyzer` | Dado un buffer PCM conocido, RMS/dBFS y nivel 0-3 correctos. |
| `VisemeMapper` | "ca" → viseme A, "me" → M, "fi" → F, etc. |
| `SyllableSplitter` | "programación" → ["pro","gra","ma","ción"]. |
| `SpriteSheet` | Carga de atlas → frames correctos (tamaño, rect). |
| `AnimationEngine` | Con mock de volumen+viseme, frame elegido correcto. |
| `ConfigLoader` | Save/load JSON round-trip. |

---

## 📦 Estructura de carpetas del proyecto

```
PNGVoice/
├── pom.xml
├── resources/
│   ├── PLAN_PNGVOICE.md          ← este archivo
│   └── config.json               ← configuración por defecto
├── assets/
│   └── avatars/
│       └── avatar1.png           ← hoja de sprites 4×N
├── src/main/java/org/PNGV/...
├── src/test/java/org/PNGV/...
└── target/
```

---

## 🚀 Comandos de desarrollo

```bash
# Compilar
mvn clean compile

# Testear
mvn test

# Ejecutar (requiere javafx-maven-plugin)
mvn javafx:run

# Empaquetar instalador
mvn clean package jpackage:jpackage
```

---

## ⚠️ Consideraciones

1. **Latencia**: el pipeline audio→viseme debe ser **< 80 ms**. Usa buffers pequeños (512-1024 samples) y hilos dedicados.
2. **Modelo Vosk**: descarga el modelo español una vez (`~50 MB small`) y guárdalo en `assets/models/`.
3. **Sprites**: los PNGs deben tener **fondo transparente** y tamaño uniforme (p. ej. 256×256 por frame).
4. **Permisos de micrófono**: en Windows/Linux/macOS se pide permiso al primer uso.
5. **Threading**: audio (hilo) → publica estado → JavaFX (Application Thread) renderiza. Usa `AtomicReference` o `Platform.runLater`.

---

## 📌 Próximos pasos inmediatos

1. Agregar dependencias en `pom.xml` (JavaFX, DSP4J, Vosk, Jackson, Logback).
2. Crear `Application.java` con ventana JavaFX mínima.
3. Implementar `AudioCapture` + `VolumeAnalyzer` y ver la barra de volumen en vivo.

> ¿Quieres que empiece a implementar el **Hito 1** (base del proyecto con JavaFX + pom.xml completo)?
