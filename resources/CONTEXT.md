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

**Latencia objetivo:** < 50 ms (buffer de 256 frames a 16 kHz = ~16 ms + VAD instantáneo + nbest=1).

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
│   ├── MicSelector               ← Enumera/resuelve micrófonos (AudioSystem.getMixerInfo + probe TargetDataLine)
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
| `VolumeAnalyzer.lastInstantDbfs` | `double` | solo audio thread | No (valor instantáneo para VAD) |
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
  "bufferFrames": 256,
  "sampleRate": 16000,
  "nbest": 1,
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
| `bufferFrames` | **256** | Samples por buffer (~16 ms a 16 kHz). Historial: 1024→512 (Fase 2.4)→**256** (reducción de latencia). Buffer más pequeño = el driver entrega audio antes = el primer resultado de Vosk llega antes. |
| `sampleRate` | 16000 | **Debe ser 16000** (requisito del modelo Vosk) |
| `nbest` | **1** | Nº de alternativas (n-best) que pide a Vosk. **Reducido de 5→1** para minimizar latencia: el coste de Vosk crece linealmente con nbest y el uso actual (viseme por última palabra) no aprovecha las alternativas extra. Subir a 3-5 solo si se necesita mayor coherencia de texto. |
| `modelPath` | `assets/models/vosk-model-small-es-0.42` | Ruta al modelo Vosk |
| `activeAvatar` | `assets/avatars/avatar1.png` | Ruta sprite sheet (no usado aún) |
| `backgroundColor` | `#00FF00` | Fondo croma-key |
| `visemeImages` | `{}` | Mapa viseme→ruta PNG (placeholder si vacío) |
| `vibrationEnabled` | `true` | Vibración (agitamiento) del avatar al hablar: ON/OFF (botón en la UI). **No afecta al grito**: cuando el avatar está en un episodio de grito activo, la imagen SIEMPRE vibra aunque el botón esté OFF (ver `updateImageWiggle`). |

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
| **El grito SIEMPRE vibra, sin importar el botón "Vibración"** | El botón solo controla el agitamiento del habla normal. En `updateImageWiggle(long)` la condición de "quieto" es `!vibrationEnabled && !inScream`, y el `target` de amplitud es `(isTalking \|\| inScream) ? 1.0 : 0.0`. Así el grito se siente "fuerte" de forma consistente aunque el usuario apague la vibración de habla normal. |
| **Grito disparado por volumen NORMALIZADO al tope** | El viseme `GRITO` solo aparece cuando el volumen normalizado (0..1 de `VolumeAnalyzer.getNormalizedLevel()`) alcanza `GRITO_NORM_THRESHOLD = 0.85` (~-20 dBFS, escala completa). Antes se usaba `level >= 3`, pero el nivel 3 ya se alcanza desde `-28 dBFS` (habla normal), así que el grito salía "aleatoriamente" al hablar. Con el umbral normalizado el grito solo aparece al llegar al tope de decibeles. Solo se muestra si el usuario asignó una imagen a `GRITO` y el avatar está hablando; si no, conserva el viseme normal. |
| **Con Vosk activo, la imagen SOLO cambia si hay texto reconocido** | Antes, en `PngVoiceApp.tick()` cualquier sonido con `level != 0` entraba en modo "hablando" y, al no haber visemes en la cola, caía al **fallback aleatorio** `pickTalkingViseme(level)` (elige un viseme al azar). Eso hacía que la imagen "bailara" con ruido/eco aunque Vosk no reconociera nada. Ahora `nextVisemeOrPick()` / `nextVisemeOrPickDifferent()` devuelven **`null`** (no cambiar la imagen) cuando hay reconocedor y la cola está vacía o el viseme coincide con el actual. `tick()` solo actualiza `currentVisible`/`visibleImageStartNanos` cuando el resultado es no-null. El fallback aleatorio por volumen **solo** se usa cuando **no** hay reconocedor (`voskRecognizer == null`, modo "volumen"). Así la imagen solo cambia cuando Vosk reconoce texto real. |

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

# Generar .exe (app-image autocontenido con JRE) → dist/PNGVoice/PNGVoice.exe
mvn clean package

# Saltarse la generación del .exe (build rápido)
mvn clean package -Dskip.jpackage=true
```

> **Generación del `.exe` (jpackage):** se usa `org.panteleyev:jpackage-maven-plugin`
> en la fase `package`, después del fat-jar de `maven-shade-plugin`.
> - Tipo por defecto: `APP_IMAGE` (carpeta con `PNGVoice.exe`, sin instalar, no requiere WiX).
>   Para instalador: `-Djpackage.type=EXE` o `MSI` (requieren WiX Toolset en Windows).
> - Salida: `dist/PNGVoice/PNGVoice.exe` (propiedades `jpackage.destination`, `jpackage.appVersion`).
> - **Requisitos en el `pom.xml` que rompen si faltan:**
>   - `jpackage.type` debe ser un valor del enum `org.panteleyev.jpackage.ImageType`
>     **en MAYÚSCULAS** (`APP_IMAGE`, `EXE`, `MSI`, `DMG`, `PKG`, `DEB`, `RPM`).
>     NO usar `app-image` (con guion/minúsculas) → Maven falla:
>     `Cannot convert 'app-image' to Enum`.
>   - `<input>${project.build.directory}</input>` es **obligatorio** para apps no
>     modulares; sin él jpackage falla: `Missing --input parameter`.
> - La main class empaquetada es `org.PNGV.Main` (fat-jar de shade).

> **Prerrequisito para reconocimiento:** modelo Vosk en
> `assets/models/vosk-model-small-es-0.42/` (descargar de
> https://alphacephei.com/vosk/models, ~50 MB).

---

## 11. Planes activos

| Documento | Descripción |
|-----------|-------------|
| `resources/PLAN_MEJORAR_RECONOCIMIENTO_VOSK.md` | Plan paso a paso (con checkboxes) para mejorar el reconocimiento de letras con Vosk y la sincronización letra→viseme. Incluye: diagnóstico, modelo mejor, `getPartialJson()`, segmentación silábica, `VisemeTimeline`, vocal dominante, thread safety, tests. |

---

## 12. Tests existentes

| Clase | Cubre |
|-------|-------|
| `VolumeAnalyzerTest` (9 tests) | RMS, dBFS, nivel 0-3, EMA, clamping, null/empty |
| `SyllableSplitterTest` (12 tests) | DominantViseme por apertura, split por sílabas, edge cases |
| `ConfigLoaderTest` (5 tests) | Save/load round-trip, defaults, custom values |
| `AvatarRendererTest` (6 tests) | Placeholder rendering, estado, viseme→nombre |
| `VoskRecognizerTest` (9 tests) | `extractText`: parseo JSON→texto de Vosk (`text`/`partial`, vacíos, texto plano, JSON inválido) |

> **Fase 1.1 (PLAN_MEJORAR_RECONOCIMIENTO_VOSK) — COMPLETADA:**
> `VoskRecognizer.acceptWaveForm()` ahora loguea en nivel `DEBUG`:
> - El JSON crudo de `getPartialResult()` / `getResult()` con elapsed time
> - El texto extraído tras `extractText()`
> - En `updateViseme()`: la última palabra, el viseme resultante, y si se encoló o se descartó
>
> Para activar los logs: cambiar el nivel del logger `org.PNGV.phoneme.VoskRecognizer`
> a `DEBUG` en `logback.xml` (o el perfil equivalente).
>
> **Reducción de ruido de logs — COMPLETADA:**
> `acceptWaveForm()` ahora loguea el `rawJson` **solo cuando el resultado contiene
> texto o un partial result** (`extractTextBest()` devuelve un texto no vacío).
> Los buffers sin reconocimiento (silencio / sin contenido) ya no generan ruido
> en los logs. `updateViseme()` y `parseWords()` también se ejecutan únicamente
> cuando hay contenido. El log `acceptWaveForm: ...` del `catch` y el log de
> `updateViseme()` (encolado/descartado) se mantienen.
>
> **Fase 1.2 (PLAN_MEJORAR_RECONOCIMIENTO_VOSK) — COMPLETADA (test manual):**
> Se hizo el test de "talking" manual hablando frases fijas y el output
> logueado muestra que las frases pronunciadas se loguean correctamente.
>
> **Fase 1.3 (PLAN_MEJORAR_RECONOCIMIENTO_VOSK) — COMPLETADA (verificación de modelo):**
> Verificado que `assets/models/vosk-model-small-es-0.42` es el modelo correcto
> y no está corrupto:
> - `conf/mfcc.conf` → `--sample-frequency=16000.0` ✅
> - Estructura completa: `am/final.mdl` (16MB), `graph/Gr.fst` (21.8MB) +
>   `HCLr.fst` (12.2MB), `ivector/*` (10MB) → ~60MB total ✅
> - `README` → "Small Spanish model for Vosk" con métricas WER ✅
>
> **FASE 1 (DIAGNÓSTICO) — COMPLETA.**
>
> **Fase 2.1 (PLAN_MEJORAR_RECONOCIMIENTO_VOSK) — COMPLETADA (Opción B nbest):**
> `AppConfig.nbest=5`, `recognizer.setMaxAlternatives(5)`, `extractTextBest()`
> elige la alternativa de mayor confianza, `setPartialWords(true)` para timestamps.
>
> **Fase 2.2 (PLAN_MEJORAR_RECONOCIMIENTO_VOSK) — COMPLETADA:**
> `setPartialWords(true)` + `parseWords()` → `List<WordTiming>` (word/start/end/conf).
> 19 tests de `parseWords`, `WordTiming`, `formatWords` pasan.
>
> **Fase 2.3 (PLAN_MEJORAR_RECONOCIMIENTO_VOSK) — COMPLETADA:**
> `acceptWaveForm()` llama a `recognizer.getFinalResult()` cuando
> `endOfUtterance == true`. Resultado pasa por `extractTextBest()` y
> `parseWords()`. Etiquetado `FINAL_CONFIRMED` en logs.
>
> **Fase 2.4 (PLAN_MEJORAR_RECONOCIMIENTO_VOSK) — COMPLETADA:**
> `bufferFrames` reducido de 1024 → 512 (~32 ms de latencia en vez de 64 ms).
> Partial results más frecuentes = mejor granularidad temporal.
> `AppConfig.bufferFrames` default actualizado, `config/app.json` actualizado.
> 120 tests pasan. Verificación manual de calidad de reconocimiento pendiente
> (Fase 6.4 del plan).
>
> **Fase 2.5 (PLAN_MEJORAR_RECONOCIMIENTO_VOSK) — COMPLETADA:**
> VAD simple en `AudioCapture.loop()`: si `level == 0` (silencio), se omite
> `frameProcessor.process()` (Vosk). El volumen se sigue publicando a la UI.
> Log en `DEBUG`: "VAD: silencio detectado (nivel 0), se omite frameProcessor".
> Reduce alucinaciones de reconocimiento durante pausas. 120 tests pasan.
>
> **FASE 2 — COMPLETA (2.1-2.5).** Siguiente: **Fase 3** (segmentación silábica
> y mapeo letra→viseme).
>
> ---
>
> **FASE: REDUCCIÓN DE LATENCIA — COMPLETADA (120 tests pasan).**
> Diagnóstico: los resultados de Vosk "se tardaban" en aparecer en los logs tras
> decir una palabra. Se identificaron 4 fuentes de latencia en el pipeline
> `Mic → line.read → VAD → Vosk → logs` y se corrigieron:
>
> | # | Causa | Impacto | Cambio |
> |---|-------|---------|--------|
> | 1 | **`nbest = 5`** (`setMaxAlternatives(5)`) | 🟥 ALTO | `AppConfig.nbest` 5 → **1**. Vosk calculaba 5 hipótesis/buffer en vez de 1 (el mayor coste de CPU). Para visemes solo usamos la última palabra de la ganadora, así que las 4 extra no aportaban nada visible. |
> | 2 | **VAD con EMA suavizado** | 🟥 ALTO | `VolumeAnalyzer` expone `getInstantDbfs()` / `hasVoice()` (dBFS **instantáneo**, sin EMA). `AudioCapture.loop()` ahora usa `analyzer.hasVoice()` en vez de `level==0`. Antes el EMA (alfa 0.5, desde -100 dBFS) tardaba ~3 buffers (~96 ms) en salir de "silencio" y **descartaba los primeros ~100 ms de la palabra**. La barra de volumen de la UI sigue usando el valor suavizado. |
> | 3 | **Log `INFO "silaba dominante"`** en cada buffer | 🟨 MEDIO | `VoskRecognizer.updateViseme()`: ese log ejecutaba el segmentador silábico (regex + loops) en **cada** buffer (~31x/seg). Bajado a `TRACE` con guard `LOG.isTraceEnabled()`, eliminando el coste del hot-path sin perder la info diagnóstica. |
> | 4 | **`bufferFrames = 512`** (~32 ms) | 🟨 BAJO-MEDIO | `AppConfig.bufferFrames` 512 → **256** (~16 ms). El driver entrega el audio antes y los partial results son el doble de frecuentes. |
>
> **Archivos modificados:** `AppConfig.java` (nbest, bufferFrames),
> `VolumeAnalyzer.java` (lastInstantDbfs, getInstantDbfs, hasVoice),
> `AudioCapture.java` (VAD vía hasVoice), `VoskRecognizer.java` (log TRACE),
> `config/app.json` (nbest=1, bufferFrames=256).
>
> **Nota de threading:** `VolumeAnalyzer.lastInstantDbfs` solo lo escribe y lee
> el hilo de audio (igual que `smoothedDbfs`), por lo que no necesita ser volátil.
>
> ---
>
> **FASE: VISEME POR SÍLABA DOMINANTE + LETRA ALEATORIA — COMPLETADA (130 tests pasan).**
>
> Antes, `VoskRecognizer.updateViseme()` usaba `Viseme.fromWord(lastWord)` que
> tomaba la **primera letra** de la última palabra. Ahora se usa la **sílaba
> dominante** (tónica) de la palabra, que es la que más se percibe al escuchar.
>
> **Cambios en `VoskRecognizer`:**
>
> 1. `updateViseme(String text)`:
>    - Antes: `Viseme.fromWord(lastWord)` (primera letra).
>    - Ahora: `transformarTextoASilabasDominantes(lastWord)` → sílaba dominante
>      → `obtenerVisemeDeSilabaDominante(sílaba)` (primera letra de la sílaba).
>    - El log DEBUG ahora incluye la sílaba dominante para diagnóstico.
>
> 2. **`obtenerVisemeDeSilabaDominante(String silaba)`** (nuevo, `static`):
>    - Recibe la sílaba dominante y devuelve el viseme de su primera letra
>      mapeable (usa `Viseme.fromLetter(char)`).
>    - Si no hay letras mapeables, devuelve `Viseme.NEUTRAL`.
>
> 3. **`obtenerVisemeLetraAleatoria(String texto)`** (nuevo, `static`):
>    - Calcula la sílaba dominante de la palabra.
>    - Recopila todas las letras presentes en esa sílaba.
>    - Elige una al azar (`java.util.Random`) y devuelve su viseme.
>    - Sobrecarga `obtenerVisemeLetraAleatoria(String, Random)` para tests
>      determinísticos (inyección del generador aleatorio).
>    - Si no hay letras, devuelve `Viseme.NEUTRAL`.
>    - **Uso:** añadir "variación" al habla cuando la sílaba dominante produce
>      un viseme poco expresivo, o para dar sensación de movimiento más natural.
>
> **Archivos modificados:**
> - `VoskRecognizer.java`: `updateViseme()` usa sílaba dominante; nuevas funciones
>   `obtenerVisemeDeSilabaDominante()` y `obtenerVisemeLetraAleatoria()` (+ sobrecarga).
> - `VoskRecognizerTest.java`: +24 tests (16 para `obtenerVisemeDeSilabaDominante`,
>   8 para `obtenerVisemeLetraAleatoria`). Total: 130 tests pasando.
