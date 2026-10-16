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
│      ├──► VolumeAnalyzer.analyze(byte[], int)  [sobre PCM, sin int[]]     │
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
│      tick(): 1) boca OPEN/CLOSED por VOLUMEN (histéresis open/close +      │
│                       hold mouthReleaseMs)                                 │
│              2) drena cola del reconocedor → playbackQueue (sílabas)       │
│              3) viseme a mostrar:                                           │
│                   boca cerrada → NEUTRAL                                    │
│                   boca abierta + sílabas → siguiente de la fila             │
│                       (cada sílaba ~syllableMinMs, default 25 ms)          │
│                   boca abierta + sin sílabas → lastSpoken                   │
│              4) grito (si volumen ≥ gritoNormThreshold y boca abierta)      │
│              5) applyViseme(viseme)  (imagen por viseme, o NEUTRAL)         │
│                                                                             │
│  UI: ProgressBar (volumen), Label (viseme), Label (estado)                 │
      Botones: Iniciar/Detener micrófono, Vibración (ON/OFF), Panel de letras│
      (la vibración alterna el agitamiento del avatar; persiste en config)   │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Latencia objetivo:** mínima posible sin tocar parámetros de Vosk ni reducir
más el buffer de captura. La latencia total = buffer de captura +
reconocimiento (Vosk) + `syllableMinMs`. Con los defaults actuales:
**buffer de 256 frames a 16 kHz = ~16 ms de captura** + reconocimiento
(Vosk, ~10–30 ms según carga) + **`syllableMinMs` 25 ms de animación** →
~50–70 ms de boca por sílaba.

> **Optimizaciones de latencia/fluidez (sin tocar parámetros de Vosk y sin
> reducir más el buffer):**
> - **Boca por volumen (con histéresis):** la boca se ABRE al cruzar
>   `mouthOpenThreshold` (default 0.05) y se CIERRA cuando el volumen cae por
>   debajo de `mouthCloseThreshold` (default 0.02) durante `mouthReleaseMs`
>   (default 160 ms). El avatar reacciona **al instante** al empezar a hablar,
>   sin esperar a que Vosk confirme la primera palabra (~200–400 ms).
> - **`syllableMinMs` 50 → 25** (~40 sílabas/s; ≈ 1.5 frames a 60 fps): la
>   boca avanza muy rápido por la fila de sílabas. Si una palabra de N sílabas
>   llega en un frame, se reproduce en N × 25 ms en vez de N × 50 ms.
> - **Volumen/Vosk en el mismo hilo de audio, por buffer:** el hilo de audio ya
>   hace `analyze()` + `acceptWaveForm()` secuencialmente por buffer
>   (256 frames), así el reconocedor procesa cada chunk en cuanto llega.
> - **UI sin "jank":** `tick()` y `applyViseme()` ya **no** hacen
>   `setText()`/`setProgress()`/`setVisible()` en **cada frame** (~60/s); solo
>   cuando el valor efectivamente cambia (`lastVisemeLabel`, umbral 0.002 en la
>   barra, guards en `rightLetterLabel`/`imageHolder`). Evita re-layouts
>   innecesarios en el hilo JavaFX que se notaban como tirones.
> - **Volumen/Vosk en el mismo hilo de audio, por buffer:** el hilo de audio ya
>   hace `analyze()` + `acceptWaveForm()` secuencialmente por buffer pequeño
>   (128 frames), así el reconocedor procesa cada chunk en cuanto llega.

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
| `VoskRecognizer.current` | `volatile Viseme` | escrito por audio, leído por FX | No |
| `VoskRecognizer.visemeQueue` | `ConcurrentLinkedQueue<Viseme>` | escrito audio, leído FX | No (cola) |
| `PngVoiceApp.playbackQueue` | `ArrayDeque<Viseme>` | solo FX thread | No (fila de reproducción) |
| `PngVoiceApp.lastSpoken` | `Viseme` | solo FX thread | No (última sílaba mostrada) |
| `PngVoiceApp.mouthOpen` | `boolean` | solo FX thread | No (estado de la boca por volumen) |
| `PngVoiceApp.mouthOpenThreshold` | `double` | solo FX thread | No (umbral de apertura) |
| `PngVoiceApp.mouthCloseThreshold` | `double` | solo FX thread | No (umbral de cierre, histéresis) |
| `PngVoiceApp.mouthReleaseMs` | `long` | solo FX thread | No (hold de cierre) |
| `PngVoiceApp.lastMouthActivityNanos` | `long` | solo FX thread | No (marca de última actividad) |
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
  "screamThreshold": 0.98,
  "smoothing": 0.3,
  "bufferFrames": 256,
  "sampleRate": 16000,
  "syllableMinMs": 50,
  "silenceHoldMs": 120,
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
| `bufferFrames` | 128 | Samples por buffer (~8 ms a 16 kHz). Menos = menos latencia de captura. No afecta a Vosk (acumula internamente). |
| `sampleRate` | 16000 | **Debe ser 16000** (requisito del modelo Vosk) |
| `modelPath` | `assets/models/vosk-model-small-es-0.42` | Ruta al modelo Vosk |
| `activeAvatar` | `assets/avatars/avatar1.png` | Ruta sprite sheet (no usado aún) |
| `backgroundColor` | `#00FF00` | Fondo croma-key |
| `visemeImages` | `{}` | Mapa viseme→ruta PNG (placeholder si vacío) |
| `vibrationEnabled` | `true` | Vibración (agitamiento) del avatar al hablar: ON/OFF (botón en la UI). **No afecta al grito**: cuando el avatar está en un episodio de grito activo, la imagen SIEMPRE vibra aunque el botón esté OFF (ver `updateImageWiggle`). |
| `syllableMinMs` | `40` | Duración mínima (ms) de **cada sílaba** en la fila de reproducción antes de avanzar a la siguiente. Más bajo = boca más fluida (más sílabas/s). A 60 fps, 40 ms ≈ 2 frames/sílaba (no se comprime a 1 frame). **No es parámetro de Vosk**, solo el ritmo de animación de la UI. Se refresca en `startMic()` → campo `PngVoiceApp.syllableMinMs`. |
| `silenceHoldMs` | `200` | Hold (ms) que se mantiene la última sílaba antes de volver a NEUTRAL. **Si la siguiente sílaba llega dentro de este tiempo, el avatar NO vuelve a NEUTRAL**: conserva la última boca hasta que llegue la siguiente (movimiento continuo, sin "parpadeo" a boca cerrada). Solo las pausas reales (> hold) cierran la boca. 200 ms cubre las pausas típicas entre sílabas/palabras (~80–160 ms). Se refresca en `startMic()` → campo `PngVoiceApp.silenceHoldMs`. |

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
| **La imagen mostrada SIEMPRE corresponde a la letra de la sílaba reconocida** | `Viseme.fromLetter(c)` devuelve el viseme del grupo de esa letra; si la letra es del alfabeto pero **no** cae en ningún grupo asignado, el default es **`S`** (posición de boca intermedia que funciona para cualquier consonante no clasificada). Solo caracteres no-letter (puntuación, espacios, dígitos) devuelven `NEUTRAL`. Así "siempre se muestra la imagen de la letra": A→A, E→E, …, y cualquier consonante extra→S. |
| **No se muestran visemes aleatorios cuando Vosk no reconoce palabra** | La UI (`PngVoiceApp.tick`) ya **no** usa el fallback aleatorio por volumen (`TALKING`/`pickTalkingViseme` eliminados). Solo consume visemes de la cola del reconocedor (`pollNextViseme()`). Si la cola está vacía (Vosk no ha identificado sílabas), **no se muestra ninguna imagen nueva**: se conserva la imagen actual (normalmente `NEUTRAL`). Las imágenes solo aparecen cuando hay sílabas identificadas en la cola. |
| **La boca se anima SÍLABA A SÍLABA (no solo la última)** | Antes `tick()` drenaba toda la cola y conservaba **solo el último** viseme (las intermedias se descartaban en el mismo frame; la boca solo mostraba la última sílaba de cada palabra). Ahora `tick()` drena la cola del reconocedor hacia una **fila de reproducción** (`playbackQueue`, `ArrayDeque<Viseme>`) y avanza por ella mostrando **cada** sílaba al menos `syllableMinMs` ms antes de pasar a la siguiente. El default bajó de 150 a **80 ms** (~12 sílabas/s, más rápido que el habla natural) para que el cambio de imagen sea **fluido** y no se vea a "pulsos". Es **configurable** en `app.json` (`syllableMinMs`). Así "gobierno" → O → E → O se ve completo. |
| **Las sílabas repetidas dan ilusión de sonido sostenido** | Al quitar el colapso `lastQueued` en `VoskRecognizer.updateVisemesFromSyllables()`, **todas** las sílabas se encolan (incluidas las repetidas). Como cada sílaba se muestra `SYLLABLE_MIN_MS` ms, las repetidas (p. ej. "bajaban" → A A A) **suman duración** en pantalla y producen la sensación de un sonido sostenido, en vez de colapsar a 1 único A. |
| **Hold de silencio arranca al terminar la última sílaba** | Cuando `playbackQueue` queda vacía, la última sílaba hablada (`lastSpoken`) se mantiene `silenceHoldMs` ms a partir de `lastFinishNanos` (marco en que la fila se vació) y luego vuelve a `NEUTRAL`. **Clave para naturalidad:** si la siguiente sílaba llega **dentro** de ese hold, el avatar **no** vuelve a NEUTRAL — conserva la última boca hasta que llegue la nueva. Así las sílabas/palabras cercanas se ven **continuas**, sin "parpadeo" a boca cerrada. El default subió de 50 a **200 ms** para cubrir las pausas típicas entre sílabas/palabras (~80–160 ms) sin saltar a NEUTRAL, dejando que solo las pausas reales (>200 ms) cierren la boca. Es **configurable** en `app.json` (`silenceHoldMs`). El `isTalking` (para vibración/grito) se maneja por la cola, ya no por el nivel de volumen. |
| **`tick` protege contra `peekFirst()` null al vaciar la cola (bug NPE)** | En el paso de avance de la fila, tras `playbackQueue.pollFirst()` (la sílaba actual terminó) la cola **puede quedar vacía** si era la última. Leer `currentVisible = playbackQueue.peekFirst()` en ese caso devuelve `null` y tumba el hilo JavaFX con `NullPointerException` en `displayName` (switch sobre `v.ordinal()`). Ahora, tras el `poll`, se comprueba `playbackQueue.isEmpty()`: si quedó vacía se usa `lastSpoken` (última sílaba) y se marca `lastFinishNanos`/`isTalking=false` (arranca el hold de silencio); solo si aún hay elementos se hace `peekFirst()`. |
| **Al drenar sílabas a una fila vacía se reinicia el reloj de la fila (bug: sílabas saltadas)** | Antes, cuando una sílaba nueva entraba en `playbackQueue` **vacía**, `visibleImageStartNanos` seguía con el timestamp de la última sílaba de hace un rato. Al entrar el paso de avance, `elapsedMs(now, visibleImageStartNanos) >= syllableMinMs` era **true** (siempre: entre sílabas pasan cientos de ms) y `pollFirst()` **consumía la sílaba nueva en el mismo frame sin mostrarla**. Efecto visible (confirmado por logs): las sílabas solitarias (p. ej. "de"→E) desaparecían sin cambiar la imagen, y en las de varias sílabas solo se veía la última ("vice"→I E → solo E). Solución: en `tick()`, al drenar con `queueWasEmpty` se hace `visibleImageStartNanos = now` y `isTalking = true`, para que la primera sílaba nueva tenga sus `syllableMinMs` completos. |
| **El grito SIEMPRE vibra, sin importar el botón "Vibración"** | El botón solo controla el agitamiento del habla normal. En `updateImageWiggle(long)` la condición de "quieto" es `!vibrationEnabled && !inScream`, y el `target` de amplitud es `(isTalking \|\| inScream) ? 1.0 : 0.0`. Así el grito se siente "fuerte" de forma consistente aunque el usuario apague la vibración de habla normal. |
| **La UI solo escribe propiedades JavaFX cuando cambian (anti-jank)** | `tick()` y `applyViseme()` se ejecutan en **cada frame** (~60/s) del `AnimationTimer`. Antes llamaban `visemeLabel.setText`, `rightLetterLabel.setText` y `volumeBar.setProgress` **siempre**, aunque el valor no variara: cada `setText` invalida el layout y cada `setProgress` invalida la barra → ~60 re-layouts/s de nada que el usuario percibe como **tirones / poca fluidez**. Ahora: `lastVisemeLabel` guarda el último viseme mostrado en la etiqueta (solo `setText` al cambiar), la barra usa un umbral `0.002` (evita el spam de `setProgress` por ruido de float), y `applyViseme` solo toca `rightLetterLabel`/`imageHolder` cuando el viseme cambia o la visibilidad difiere. El dibujo del avatar queda limpio y a 60 fps reales. |
| **Grito disparado por volumen NORMALIZADO al tope** | El viseme `GRITO` solo aparece cuando el volumen normalizado (0..1 de `VolumeAnalyzer.getNormalizedLevel()`) alcanza `GRITO_NORM_THRESHOLD = 0.85` (~-20 dBFS, escala completa). Antes se usaba `level >= 3`, pero el nivel 3 ya se alcanza desde `-28 dBFS` (habla normal), así que el grito salía "aleatoriamente" al hablar. Con el umbral normalizado el grito solo aparece al llegar al tope de decibeles. Solo se muestra si el usuario asignó una imagen a `GRITO` y el avatar está hablando; si no, conserva el viseme normal. |

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

## 11. Tests existentes

| Clase | Cubre |
|-------|-------|
| `VolumeAnalyzerTest` (9 tests) | RMS, dBFS, nivel 0-3, EMA, clamping, null/empty |
| `SyllableSplitterTest` (12 tests) | DominantViseme por apertura, split por sílabas, edge cases |
| `ConfigLoaderTest` (5 tests) | Save/load round-trip, defaults, custom values |
| `AvatarRendererTest` (6 tests) | Placeholder rendering, estado, viseme→nombre |
| `VoskRecognizerTest` (9 tests) | `extractText`: parseo JSON→texto de Vosk (`text`/`partial`, vacíos, texto plano, JSON inválido) |

> **Logging (deduplicado):** `VoskRecognizer.acceptWaveForm()` loguea a nivel
> **INFO** solo cuando el texto reconocido **cambia** (guard `lastLoggedText`).
> El JSON crudo y el log MAPPED se eliminaron para reducir ruido.
> Formato:
> ```
> [Vosk 1717050123456] PARTIAL text='hola'
> [Vosk 1717050124000] FINAL   text='hola'
> [Vosk 1717050125000] PARTIAL text='el'
> [Vosk 1717050126000] PARTIAL text='el programa'
> ```
> Al llegar `FINAL` se reinicia `lastLoggedText=""` para que la siguiente
> frase (aunque repita la misma palabra) se loguee de nuevo.
> `updateViseme()` ya no loguea (solo mapea y encola).
>
> **Logging diagnóstico de la imagen de pronunciación** (para saber cuándo la
> imagen SÍ/NO se actualiza):
> - `VoskRecognizer.logNewWord()` loguea cada **nueva palabra** detectada
>   (guard `lastRecognizedWord`):
>   `[VOSK] WORD PARTIAL → 'programa' (… ms)` / `[VOSK] WORD FINAL → 'programa' (… ms)`.
> - `PngVoiceApp.applyViseme()` loguea solo cuando la **imagen visible cambia**
>   (guard `lastAppliedViseme`) y si el viseme tiene imagen asignada:
>   `[UI] IMG → A | imagen asignada: SÍ/NO (… ms)`.
> - **Interpretación:** sin líneas `[VOSK] WORD` → el reconocedor no produce
>   texto nuevo. Con `WORD` pero sin `[UI] IMG` → los visemes no llegan a
>   consumirse o falta imagen asignada a ese viseme.
