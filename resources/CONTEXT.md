# PNGVoice — Documento de Contexto

> Documento orientado a dar contexto completo a una IA (o desarrollador nuevo) para
> entender la app sin tener que leer archivo por archivo.

---

## 1. Propósito

App local tipo **Veadotube**: muestra una imagen de un avatar que reacciona a la voz
del usuario en tiempo real. Dos ejes de animación:

- **Volumen** → nivel 0-3 (silencio / bajo / medio / grito)
- **Viseme (boca)** → 10 formas (NEUTRAL, A, E, I, O, U, BMP, FV, L, S)

Sin internet, sin servidores. Todo local.

---

## 2. Stack tecnológico

| Área | Tecnología | Versión | Por qué |
|------|-----------|---------|---------|
| Lenguaje | Java | 26 | LTS moderno, `record`, sealed classes |
| Build | Maven | 3.9+ | `mvn javafx:run` para desarrollo |
| UI | JavaFX (OpenJFX) | 26.0.1 | Canvas + AnimationTimer, nativo |
| ASR local | Vosk (`com.alphacephei:vosk`) | 0.8.57 | Emite texto, API Java, 100% offline |
| Config | Jackson | 2.17.2 | JSON load/save |
| Logs | SLF4J + Logback | 2.0.13 / 1.5.12 | Logs limpios en consola |
| Tests | JUnit 5 + Mockito | 5.10.2 / 5.11.0 | Unit tests sin FX |

> **Nota:** DSP4J, JTransforms y WebRTC VAD están en el PLAN pero **no son
> dependencias actuales**. El volumen se calcula con código propio (RMS + EMA).

---

## 3. Pipeline de datos (flujo completo)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  HILo DE AUDIO (thread "audio-capture")                                     │
│                                                                             │
│  Micrófono ──► TargetDataLine.read() ──► byte[] PCM 16-bit mono @ 16 kHz   │
│      │                                                                     │
│      ├──► VolumeAnalyzer.analyzeLevel(int[])                               │
│      │       RMS → dBFS → EMA smoothing → nivel 0-3                        │
│      │       ──► AtomicReference<VolumeState> (thread-safe)               │
│      │                                                                     │
│      └──► AudioFrameProcessor.process(byte[], int)  [callback]            │
│              └──► VoskRecognizer.acceptWaveForm()                          │
│                      └──► Vosk: getPartialResult() / getResult()          │
│                            └──► Viseme.fromLetter(primeraLetra)           │
│                                  └──► ConcurrentLinkedQueue<Viseme>       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                              │
                              │  (handoff thread-safe, sin locks)
                              ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  JAVA FX APPLICATION THREAD (AnimationTimer, ~60 fps)                       │
│                                                                             │
│  AnimationTimer.handle(now):                                               │
│      state  = audioCapture.getState()       // VolumeState(level, norm)    │
│      viseme = voskRecognizer.pollViseme()   // Viseme o null              │
│      ──► AvatarRenderer.render(canvas, viseme, level, config)              │
│              └──► config.visemeImages.get(viseme.name())                   │
│                  ├── tiene imagen? → drawImage()                           │
│                  └── no?           → drawPlaceholder() (formas Canvas)     │
│                                                                             │
│  UI: ProgressBar (volumen), Label (viseme), Label (estado)                 │
      Botones: Iniciar/Detener micrófono, Vibración (ON/OFF), Panel de letras│
      (la vibración alterna el agitamiento del avatar; persiste en config)   │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Latencia objetivo:** < 80 ms (buffer de 1024 frames a 16 kHz = ~64 ms).

> **Nota importante (formato de salida de Vosk):** `Recognizer.getResult()` y
> `Recognizer.getPartialResult()` **no devuelven texto plano, devuelven JSON**:
> `{ "text" : "hola" }` (final) o `{ "partial" : "hola" }` (parcial).
> `VoskRecognizer.acceptWaveForm()` parsea ese JSON con Jackson
> (`VoskRecognizer.extractText(String)`) para extraer el campo `text`/`partial`
> antes de mapear al viseme. **No** se puede usar la cadena JSON tal cual
> (`Viseme.fromWord` no encuentra letras en `{...}` y devuelve siempre NEUTRAL).
> `endOfUtterance == false` es **esperado** mientras no haya silencio: Vosk solo
> devuelve `true` al detectar pausa.

---

## 4. Estructura de paquetes y responsabilidades

```
org.PNGV
├── Main                          ← Entry point (JavaFX LauncherHelper)
├── app
│   ├── PngVoiceApp               ← Application JavaFX, orquesta todo
│   └── AvatarRenderer            ← Dibuja en Canvas (placeholder o imagen)
├── audio
│   ├── AudioCapture              ← TargetDataLine + hilo de lectura
│   ├── AudioFrameProcessor       ← @FunctionalInterface: callback por buffer PCM
│   └── VolumeAnalyzer            ← RMS → dBFS → EMA → nivel 0-3
├── phoneme
│   ├── PhonemeRecognizer         ← Interfaz (recognize, currentViseme, close)
│   ├── VoskRecognizer            ← Impl. real (ASR Vosk) + AudioFrameProcessor
│   ├── OfflineRecognizer         ← Impl. sin modelo (tests / demo)
│   ├── Viseme                    ← Enum 10 valores + mapeo letra→viseme
│   └── SyllableSplitter          ← Heurística de apertura + segmentador (legacy)
├── avatar
│   ├── Avatar                    ← Modelo: id, ruta, rows×cols, etiquetas
│   └── SpriteSheet               ← Carga PNG + corte de frames (NO usado aún)
└── config
    ├── AppConfig                 ← POJO serializable (Jackson)
    └── ConfigLoader              ← load/save JSON (crea defaults si no existe)
```

---

## 5. Clases clave y relaciones

```
Main
  └── PngVoiceApp (extends Application)
        ├── creates → AudioCapture(config, analyzer, voskRecognizer)
        ├── creates → VoskRecognizer(config)   [o null si modelo no existe]
        ├── creates → VolumeAnalyzer(config)
        ├── creates → AvatarRenderer
        └── AnimationTimer → PngVoiceApp.handle()
              ├── reads → AudioCapture.getState()         → VolumeState
              ├── reads → VoskRecognizer.pollViseme()     → Viseme | null
              └── calls → AvatarRenderer.render(canvas, viseme, level, config)

AudioCapture
  ├── implements → AutoCloseable
  ├── uses → VolumeAnalyzer.analyzeLevel(int[])
  └── callback → AudioFrameProcessor.process(byte[], int)
                    └── implemented by → VoskRecognizer (extends PhonemeRecognizer)

VoskRecognizer
  ├── implements → PhonemeRecognizer, AudioFrameProcessor
  ├── uses → Viseme.fromLetter(char)
  └── publishes → ConcurrentLinkedQueue<Viseme> (consumido por PngVoiceApp)

AvatarRenderer
  ├── uses → AppConfig.getVisemeImages()  (Map<String,String>)
  ├── uses → javafx.scene.canvas.Canvas  (placeholder shapes)
  └── (future) → SpriteSheet.frame(row, col)
```

---

## 6. Estado / mutabilidad

| Variable | Tipo | Thread | Inmutable? |
|----------|------|--------|-----------|
| `AudioCapture.stateRef` | `AtomicReference<VolumeState>` | escrito por audio, leído por FX | Sí (record inmutable) |
| `VolumeAnalyzer.smoothedDbfs` | `double` | solo audio thread | No (EMA stateful) |
| `VoskRecognizer.current` | `volatile Viseme` | escrito por audio, leído por FX | No |
| `VoskRecognizer.visemeQueue` | `ConcurrentLinkedQueue<Viseme>` | escrito audio, leído FX | No (cola) |
| `VoskRecognizer.lastQueued` | `volatile Viseme` | audio thread | No |
| `PngVoiceApp.lastDisplayedViseme` | `Viseme` | solo FX thread | No |
| `AppConfig` | POJO mutable | solo FX thread (config) | No |
| `Avatar`, `SpriteSheet` | inmutables | — | Sí |

**Regla de threading:**
- Hilo audio → publica estado (AtomicReference, ConcurrentLinkedQueue, volatile)
- FX thread → lee estado, renderiza. Nunca escribe en campos del hilo audio.
- `AudioFrameProcessor.process()` se invoca **desde el hilo audio**: debe ser rápido.

---

## 7. Configuración

Archivo: `config/app.json` (creado automáticamente con defaults si no existe).

```json
{
  "thresholdLow": -50.0,
  "thresholdMid": -40.0,
  "thresholdHigh": -28.0,
  "smoothing": 0.5,
  "bufferFrames": 1024,
  "sampleRate": 16000,
  "modelPath": "assets/models/vosk-model-small-es-0.42",
  "activeAvatar": "assets/avatars/avatar1.png",
  "backgroundColor": "#00FF00",
  "vibrationEnabled": true,
  "visemeImages": {
    "A": "assets/visemes/A.png",
    "E": "assets/visemes/E.png",
    ...
  }
}
```

| Campo | Default | Descripción |
|-------|---------|-------------|
| `thresholdLow` | -50 dBFS | Por debajo = nivel 0 (silencio) |
| `thresholdMid` | -40 dBFS | Por debajo = nivel 1 (bajo) |
| `thresholdHigh` | -28 dBFS | Por debajo = nivel 2 (medio), si no 3 (alto) |
| `smoothing` | 0.5 | Alfa EMA. 0=reactivo, 1=inerte |
| `bufferFrames` | 1024 | Samples por buffer (~64 ms a 16 kHz) |
| `sampleRate` | 16000 | **Debe ser 16000** (requisito del modelo Vosk) |
| `modelPath` | `assets/models/vosk-model-small-es-0.42` | Ruta al modelo Vosk |
| `activeAvatar` | `assets/avatars/avatar1.png` | Ruta sprite sheet (no usado aún) |
| `backgroundColor` | `#00FF00` | Fondo croma-key |
| `visemeImages` | `{}` | Mapa viseme→ruta PNG (placeholder si vacío) |
| `vibrationEnabled` | `true` | Vibración (agitamiento) del avatar al hablar: ON/OFF (botón en la UI) |

---

## 8. Decisiones de arquitectura y "por qué"

| Decisión | Razón |
|----------|-------|
| **16 kHz, no 44.1 kHz** | Vosk `vosk-model-small-es-0.42` está entrenado a 16 kHz. Vosk **no hace re-muestreo interno**: a 44.1 kHz el modelo interpreta la señal acelerada y produce texto aleatorio. |
| **Verificar `line.getFormat().getSampleRate()` tras `open()`** | `TargetDataLine.open(format)` puede "exitosamente" abrir la línea entregando otra frecuencia (SRC interna del SO en Windows/WASAPI) sin lanzar excepción. `AudioCapture.openLine()` lee la frecuencia real y construye el `SampleRateFilter` con ella, no con la pedida. Fallbacks probados: 32k → 48k → 44.1k → 8k (32k es el más cercano a 16k, mejor calidad de interpolación). |
| **10 visemes (no 12+ columnas)** | En 2D, separar S/C/D/G/K/N/T/X/Y/Z/H/Q/W en formas de boca distintas no aporta movimiento visible. Agrupar en BMP/FV/L/S + 5 vocales + NEUTRAL es suficiente. |
| **Primera letra de la última palabra** (VoskRecognizer) | Es el "ataque" de la sílaba, la forma de boca más reconocible. Simple y suficiente para v1. (El `SyllableSplitter.dominantViseme()` por apertura de vocal existe pero no se usa en el pipeline actual.) |
| **Placeholder Canvas si no hay imagen** | Permite que la app funcione desde el primer `mvn javafx:run` sin que el usuario tenga que crear PNGs. Se ve color + forma de boca + texto. |
| **Fallback: sin modelo Vosk → solo volumen** | La app no debe fallar si el modelo no está descargado. `VoskRecognizer.isModelAvailable()` decide. |
| **ConcurrentLinkedQueue para visemes** | Productor (audio thread) y consumidor (FX thread) distintos. No se necesitan locks. `poll()` en el FX thread en cada frame = consumo ordenado. |
| **EMA smoothing en VolumeAnalyzer** | Evita que el avatar "parpadee" con picos de audio. Alfa configurable (0..1). |
| **AudioFrameProcessor como @FunctionalInterface** | Desacopla AudioCapture del reconocedor. AudioCapture no sabe si es Vosk, Kaldi, o nada. |
| **`sampleRate` en AppConfig, no hardcoded** | Si el usuario cambia de modelo Vosk (p. ej. modelo a 8 kHz), solo cambia la config. |

---

## 9. Puntos de extensión / TODO

| Item | Estado | Dónde |
|------|--------|-------|
| **Sprite sheet real** (4 filas × N columnas) | `SpriteSheet` + `Avatar` existen pero `PngVoiceApp` no los usa aún | `AvatarRenderer` → usar `SpriteSheet.frame(level, visemeOrdinal)` |
| **Estado machine** (IDLE, TALKING, SHOUT, SINGING) | No implementado | Nuevo paquete `org.PNGV.engine` |
| **Selector de múltiples avatares** | No implementado | `AvatarManager` (en PLAN) |
| **VAD (WebRTC)** | No implementado | Distinguir voz vs ruido |
| **Exportar a video** | No implementado | JavaFX Media + FFmpeg |
| **SyllableSplitter en pipeline** | Existe pero no se usa (VoskRecognizer usa primera letra) | Integrar si se necesita precisión por sílaba |
| **Kaldi / DeepSpeech** | No implementado | Para fonemas exactos (v2) |
| **Modo karaoke (audio externo)** | No implementado | Leer archivo de audio en vez de micrófono |

---

## 10. Comandos

```bash
# Compilar
mvn clean compile

# Testear (sin JavaFX)
mvn test

# Ejecutar (requiere micrófono)
mvn javafx:run

# Limpiar
mvn clean
```

> **Prerrequisito para reconocimiento:** modelo Vosk en
> `assets/models/vosk-model-small-es-0.42/` (descargar de
> https://alphacephei.com/vosk/models, ~50 MB).

---

## 11. Tests existentes

| Clase | Cubre |
|-------|-------|
| `VolumeAnalyzerTest` (9 tests) | RMS, dBFS, nivel 0-3, EMA, clamping, null/empty |
| `SyllableSplitterTest` (12 tests) | DominantViseme por apertura, split por sílabas, edge cases |
| `ConfigLoaderTest` (5 tests) | Save/load round-trip, defaults, custom values |
| `AvatarRendererTest` (6 tests) | Placeholder rendering, estado, viseme→nombre |
| `VoskRecognizerTest` (9 tests) | `extractText`: parseo JSON→texto de Vosk (`text`/`partial`, vacíos, texto plano, JSON inválido) |
