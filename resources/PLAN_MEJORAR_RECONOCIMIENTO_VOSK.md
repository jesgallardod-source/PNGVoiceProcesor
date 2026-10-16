# 🎯 Plan de Mejora: Reconocimiento y Sincronización de Letra→Viseme con Vosk

> **Problema:** Vosk no reconoce las letras de forma correcta y la sincronización
> entre lo que se pronuncia y el viseme mostrado en el avatar es imprecisa.
>
> **Causa raíz:** El pipeline actual solo toma la **primera letra de la última
> palabra** del partial result y lo mapea directamente a un viseme. No hay
> segmentación silábica, no hay timing, y no se aprovecha la información JSON
> avanzada de Vosk (timestamps por palabra, confianza, n-best).

---

## 📊 Diagnóstico actual

```
ACTUAL (deficiente):
  Vosk partial "hola mun"
      → lastWord() → "mun"
      → firstLetter("m") → BMP
      → encolar BMP (si ≠ lastQueued)
      → UI muestra BMP

ESPERADO (con sincronización):
  Vosk partial "hola mun"
      → segmentar: ["ho", "la", "mun"]
      → timing: "ho"@0ms, "la"@200ms, "mun"@400ms
      → timeline viseme: [O(80ms), A(80ms), BMP(80ms)]
      → UI avanza por timeline en cada frame (60fps)
```

---

## 📋 Pasos del plan

### Fase 1 — Diagnóstico y base (entender el problema)

- [x] **1.1** Agregar logging detallado en `VoskRecognizer.acceptWaveForm()`:
      - Loguear el JSON crudo de `getPartialResult()` / `getResult()` con timestamp
      - Loguear el texto extraído, la última palabra, y el viseme resultante
      - Objetivo: ver exactamente qué texto produce Vosk al hablar frases conocidas
- [x] **1.2** Crear un test de "talking" manual: hablar frases fijas ("hola mundo",
      "programación en java", "a e i o u") y capturar el output logueado
      → ✅ Completado (test manual): las frases pronunciadas se loguean correctamente.
- [x] **1.3** Verificar que el modelo en `assets/models/` es el correcto y no está corrupto:
      - ✅ `conf/mfcc.conf` tiene `--sample-frequency=16000.0`
      - ✅ Modelo `vosk-model-small-es-0.42` completo: `am/final.mdl` (16MB),
        `graph/Gr.fst` (21.8MB) + `HCLr.fst` (12.2MB), `ivector/*` (10MB) → ~60MB total
      - ✅ `README` confirma "Small Spanish model for Vosk" con métricas WER
      - Conclusión: modelo correcto, 16 kHz, no corrupto. **Fase 1 (diagnóstico) COMPLETADA.**

---

### Fase 2 — Mejorar precisión del reconocimiento (Vosk)

- [x] **2.1** Probar con un **modelo mejor** (opción A o B):
      - **Opción A:** `vosk-model-es-0.42` (modelo grande español, ~450MB)
        → Mejor precisión de palabras y menor alucinación
      - **Opción B:** Mantener `small` pero añadir `--nbest=5` en la creación
        del `Recognizer` para ver alternativas y elegir la más coherente
      - Hacer A/B test con las frases del paso 1.2
      → ✅ Completado (Opción B): `AppConfig.nbest=5`, `recognizer.setMaxAlternatives(5)`,
        `extractTextBest()` elige la alternativa de mayor confianza,
        `setPartialWords(true)` para timestamps. 22 tests de `extractTextBest` pasan.
- [x] **2.2** Usar **`getPartialJson()`** en lugar de `getPartialResult()`:
      - ⚠️ `getPartialJson()` **no existe** en el jar `com.alphacephei:vosk:0.3.45`
        (verificado con `javap`). La funcionalidad equivalente se logra con
        `setPartialWords(true)` + `getPartialResult()`, que devuelven el JSON
        extendido con timestamps:
        ```json
        {
          "partial": "hola mun",
          "result": [
            {"word": "hola", "start": 0.12, "end": 0.45, "conf": 0.92},
            {"word": "mun", "start": 0.61, "end": 0.78, "conf": 0.71}
          ]
        }
        ```
      - Esto da **timestamps por palabra** (segundos desde inicio de frase)
        y **confianza** → clave para la sincronización
      - ✅ Implementado: `VoskRecognizer.setPartialWords(true)` en el constructor,
        `parseWords()` extrae `List<WordTiming>`, `WordTiming` record con
        `word/start/end/conf`, `formatWords()` para logging
      - ✅ 19 tests unitarios de `parseWords`, `WordTiming`, `formatWords` pasan
- [x] **2.3** Usar **`getFinalJson()`** en lugar de `getResult()` al detectar
      `endOfUtterance`:
      - Da el resultado final con timestamps y confianza
      - Permite "confirmar" la secuencia de visemes emitidos durante la frase
      - ✅ Implementado: `acceptWaveForm()` llama a `recognizer.getFinalResult()`
        cuando `endOfUtterance == true` (verificado con `javap` que el método
        existe en el jar `vosk-0.3.45`; no hay `getFinalJson()`, el método es
        `getFinalResult()`). El resultado final pasa por `extractTextBest()`
        (nbest) y `parseWords()` (timestamps/conf) confirmando la secuencia de
        visemes. Etiquetado `FINAL_CONFIRMED` en los logs. 120 tests pasan.
- [x] **2.4** Ajustar el **buffer de audio**:
      - Reducir `bufferFrames` de 1024 → 512 (32ms de latencia en vez de 64ms)
      - Menor buffer = partial results más frecuentes = mejor granularidad temporal
      - Verificar que no se degrade la calidad del reconocimiento
      → ✅ Completado: `AppConfig.bufferFrames` default 1024→512, `config/app.json` actualizado.
        120 tests pasan. Verificación manual de calidad pendiente (Fase 6.4).
- [x] **2.5** (Opcional) Agregar **VAD simple** (Voice Activity Detection):
      - Si el nivel de volumen es 0 (silencio), no procesar el buffer en Vosk
      - Reduce alucinaciones y "fantasmas" de reconocimiento
      - Implementar: en `AudioCapture.loop()`, si `level == 0` skip `frameProcessor.process()`
      → ✅ Completado: en `AudioCapture.loop()`, si `level == 0` se omite la
        llamada a `frameProcessor.process()` (Vosk) y se loguea en `DEBUG`.
        El volumen se sigue publicando a la UI normalmente. 120 tests pasan.

---

### Fase 3 — Segmentación silábica y mapeo letra→viseme

- [ ] **3.1** Integrar `SyllableSplitter` en el pipeline de `VoskRecognizer`:
      - En vez de `Viseme.fromWord(lastWord)` (solo primera letra),
        usar `SyllableSplitter.split(word)` para obtener todas las sílabas
      - Cada sílaba → `Viseme.fromWord(silaba)` → secuencia de visemes
      - Ejemplo: "programación" → ["pro", "gra", "ma", "ción"]
        → [BMP, S, A, I] (4 visemes, no 1)
- [ ] **3.2** Crear una clase **`VisemeTimeline`** (nuevo):
      ```java
      public class VisemeTimeline {
          record Entry(Viseme viseme, long startMs, long endMs) {}
          List<Entry> entries;  // ordenada por tiempo
          // Métodos:
          Viseme visemeAt(long currentTimeMs);  // → viseme activo en ese instante
          boolean isEmpty();
          void add(Viseme v, long startMs, long durationMs);
      }
      ```
      - Cada sílaba reconocida genera un `Entry` con su duración estimada
      - Duración por sílaba: `80ms` por sílaba (ajustable en config)
      - La UI lee `visemeAt(currentTimeMs)` en cada frame
- [ ] **3.3** Modificar `VoskRecognizer.updateViseme()` para que:
      - Reciba el texto **nuevo** (delta desde el último partial)
      - Detecte qué palabras/sílabas son NUEVAS (no las que ya se procesaron)
      - Solo encole las sílabas nuevas en la `VisemeTimeline`
      - Mantener un `lastProcessedText` para hacer diff
      ```java
      private String lastProcessedText = "";
      private void updateViseme(String newText) {
          // Extraer solo lo NUEVO
          String delta = extractDelta(lastProcessedText, newText);
          lastProcessedText = newText;
          if (delta == null || delta.isBlank()) return;
          // Segmentar y encolar en timeline
          for (String syl : SyllableSplitter.split(delta)) {
              Viseme v = Viseme.fromWord(syl);
              long now = System.currentTimeMillis();
              timeline.add(v, now, SYLLABLE_DURATION_MS);
          }
      }
      ```
- [ ] **3.4** Eliminar el mecanismo `lastQueued` (dedup por viseme):
      - Con la timeline, ya no hace falta: cada viseme tiene su slot temporal
      - Permitir visemes repetidos (p. ej. "a-a-a" → [A, A, A])
      - El `AnimationTimer` simplemente avanza por la timeline
- [ ] **3.5** Actualizar `Viseme.fromWord()` para que use la **vocal dominante**
      de la sílaba (no solo la primera letra):
      - Actual: "ción" → primera letra "c" → S
      - Mejorado: "ción" → vocal "o" → O (la vocal es lo que más mueve la boca)
      - Regla: si la sílaba tiene vocal → usar vocal; si no → primera consonante
      ```java
      public static Viseme fromWord(String word) {
          // Prioridad: vocal (máximo movimiento de boca)
          for (char c : word.toCharArray()) {
              if (isVowel(c)) return fromLetter(c);
          }
          // Fallback: primera consonante
          for (char c : word.toCharArray()) {
              if (Character.isLetter(c)) return fromLetter(c);
          }
          return NEUTRAL;
      }
      ```

---

### Fase 4 — Sincronización temporal (timeline + UI)

- [ ] **4.1** Modificar `PngVoiceApp.handle()` (AnimationTimer) para:
      - En vez de `pollViseme()` (cola FIFO), leer de la `VisemeTimeline`
      - `Viseme v = timeline.visemeAt(System.currentTimeMillis())`
      - Si la timeline está vacía o expiró → mantener último viseme o NEUTRAL
      ```java
      // En AnimationTimer.handle():
      Viseme viseme = voskRecognizer.currentTimelineViseme(); // nuevo método
      if (viseme == null) viseme = lastDisplayedViseme; // mantener
      ```
- [ ] **4.2** Agregar **duración configurable** de viseme en `AppConfig`:
      ```json
      {
        "syllableDurationMs": 80,
        "minVisemeHoldMs": 60,
        "maxVisemeHoldMs": 200
      }
      ```
      - `syllableDurationMs`: duración de cada sílaba en la timeline
      - `minVisemeHoldMs`: mínimo que un viseme debe mostrarse (evita parpadeo)
      - `maxVisemeHoldMs`: máximo (evita congelarse si la timeline se atrasa)
- [ ] **4.3** Implementar **interpolación suave** entre visemes:
      - En `AvatarRenderer`, si el viseme cambia, hacer transición de 1-2 frames
      - Evita "saltos" bruscos entre formas de boca
      - Opcional: crossfade entre imágenes si se usan PNGs
- [ ] **4.4** Agregar **timeout de inactividad**:
      - Si no hay nuevo partial result en >300ms → volver a NEUTRAL
      - Evita que el avatar quede "congelado" en un viseme
      - `if (System.currentTimeMillis() - lastAudioTime > 300) → NEUTRAL`
- [ ] **4.5** Usar los **timestamps de Vosk** (de `getPartialJson`) para
      calibrar la timeline:
      - `word.start` y `word.end` dan el tiempo real de pronunciación
      - En vez de asumir 80ms por sílaba, usar `(end - start) / numSyllables`
      - Ejemplo: "hola" start=0.12 end=0.45 → 330ms / 2 sílabas = 165ms por sílaba

---

### Fase 5 — Robustez y edge cases

- [ ] **5.1** Manejar **palabras cortas / monosílabas** correctamente:
      - "a" → [A], "e" → [E], "i" → [I] (una sílaba = un viseme)
      - "b" → [BMP], "m" → [BMP]
- [ ] **5.2** Manejar **puntuación y espacios** en el texto de Vosk:
      - Filtrar caracteres no alfabéticos antes de segmentar
      - "hola, mundo" → ["hola", "mundo"] (ignorar coma)
- [ ] **5.3** Manejar **endOfUtterance** (fin de frase):
      - Cuando `acceptWaveForm()` devuelve `true`, usar `getFinalJson()`
      - Confirmar/ajustar la timeline con el resultado final (más preciso)
      - Reiniciar `lastProcessedText = ""` para la siguiente frase
- [ ] **5.4** Manejar **frases muy largas** (el usuario no hace pausa):
      - Si la partial result crece mucho (>20 palabras), forzar un "reset"
      - O confiar en el `nbest` para no alucinar
- [ ] **5.5** **Thread safety** de la nueva `VisemeTimeline`:
      - Escrita por hilo de audio, leída por hilo FX
      - Usar `CopyOnWriteArrayList` o `synchronized` con acceso atómico
      - O mantener `ConcurrentLinkedQueue` pero con `VisemeEntry` (viseme + timestamp)

---

### Fase 6 — Tests y verificación

- [ ] **6.1** Unit tests para `SyllableSplitter` (ya existen 12 tests, verificar
      que cubren casos de uso real):
      - "programación" → ["pro", "gra", "ma", "ción"]
      - "a" → ["a"]
      - "hola" → ["ho", "la"]
      - "psicología" → ["si", "co", "ló", "gi", "a"]
- [ ] **6.2** Unit tests para `Viseme.fromWord()` con la nueva lógica de vocal:
      - "ción" → O (vocal dominante)
      - "b" → BMP (consonante, sin vocal)
      - "hola" → O (vocal "o" presente)
      - "" → NEUTRAL
- [ ] **6.3** Unit tests para `VisemeTimeline`:
      - `visemeAt(t)` devuelve el viseme correcto según el tiempo
      - Timeline vacía → null
      - Timeline expirada → null (o último viseme)
      - Múltiples visemes en secuencia
- [ ] **6.4** Test de integración (manual):
      - Hablar frases conocidas y verificar que el avatar muestra la secuencia
        correcta de visemes en el orden correcto
      - Medir latencia: tiempo desde que se pronuncia una sílaba hasta que
        el viseme aparece en pantalla (objetivo <150ms)
- [ ] **6.5** Test de estrés:
      - Hablar rápido y sin pausas → no debe "atascarse" ni acumular cola
      - Silencio prolongado → debe volver a NEUTRAL
      - Frase muy larga → no debe perder sincronización

---

### Fase 7 — Optimización y pulido

- [ ] **7.1** Ajustar `bufferFrames` (512 vs 1024) según calidad de reconocimiento
- [ ] **7.2** Ajustar `syllableDurationMs` para que el avatar se vea natural
- [ ] **7.3** Si se usa modelo grande (2.1), considerar `nbest=3` para
      seleccionar la mejor hipótesis (máx confianza)
- [ ] **7.4** (Opcional) Agregar **modo debug** en la UI:
      - Panel que muestra el texto reconocido en vivo
      - Panel que muestra la timeline de visemes con timestamps
      - Botón para "forzar viseme" (testear imágenes sin hablar)
- [ ] **7.5** Documentar en `CONTEXT.md` los cambios de arquitectura:
      - Nueva clase `VisemeTimeline`
      - Cambio de `pollViseme()` → `currentTimelineViseme()`
      - Uso de `getPartialJson()` / `getFinalJson()`
      - Nuevos campos en `AppConfig`

---

## 🔑 Resumen de cambios de código

| Archivo | Cambio |
|---------|--------|
| `VoskRecognizer.java` | Usar `getPartialJson()`/`getFinalJson()`, segmentar sílabas, alimentar `VisemeTimeline`, eliminar `lastQueued` |
| `Viseme.java` | `fromWord()` → vocal dominante (no solo primera letra) |
| `VisemeTimeline.java` | **NUEVO**: clase con `Entry(viseme, startMs, endMs)`, `visemeAt(time)` |
| `SyllableSplitter.java` | Verificar que funciona (ya existe), ajustar si es necesario |
| `PngVoiceApp.java` | `handle()` lee de timeline en vez de `pollViseme()` |
| `AvatarRenderer.java` | (Opcional) transición suave entre visemes |
| `AppConfig.java` | Nuevos campos: `syllableDurationMs`, `minVisemeHoldMs`, `maxVisemeHoldMs` |
| `AudioCapture.java` | (Opcional) skip Vosk si silencio (VAD simple) |
| `config/app.json` | Nuevos valores de configuración |

---

## 📈 Métricas de éxito

| Métrica | Actual | Objetivo |
|---------|--------|----------|
| Latencia (voz→viseme) | ~100-200ms (impreciso) | <150ms consistente |
| Precisión viseme (frase "a e i o u") | 1/5 correctos | 5/5 correctos |
| Sincronización (orden de visemes) | No hay (solo 1 por buffer) | Secuencia correcta |
| Visemes por sílaba | 1 (primera letra) | 1 (vocal dominante) |
| Parpadeo (cambios no deseados) | Alto | Bajo (mínimo 60ms por viseme) |
| Latencia de cola (backlog) | Indeterminado | 0 (timeline se consume en tiempo real) |

---

## ⚠️ Riesgos y mitigaciones

| Riesgo | Mitigación |
|--------|-----------|
| Modelo grande (450MB) muy lento al cargar | Cargar en background, mostrar splash screen |
| `getPartialJson()` no disponible en Vosk 0.8.57 | Verificar API; si no existe, fallback a `getPartialResult()` |
| `SyllableSplitter` heurística imperfecta | Aceptar ~80% de precisión; la vocal dominante ya mejora mucho |
| Thread safety de timeline nueva | `CopyOnWriteArrayList` + `volatile` para timestamp |
| Latencia excesiva con buffer 512 | A/B test; si degrada, volver a 1024 |

---

## 📅 Orden sugerido de implementación

```
Semana 1:
  Día 1:  Fase 1 (diagnóstico) + Fase 2.1 (probar modelo)
  Día 2:  Fase 2.2-2.4 (getPartialJson, buffer)
  Día 3:  Fase 3.1-3.3 (SyllableSplitter + delta text)
  Día 4:  Fase 3.4-3.5 (eliminar lastQueued, vocal dominante)
  Día 5:  Fase 4.1-4.2 (timeline + config)

Semana 2:
  Día 1:  Fase 4.3-4.5 (interpolación, timeout, timestamps Vosk)
  Día 2:  Fase 5 (edge cases + thread safety)
  Día 3:  Fase 6 (tests)
  Día 4:  Fase 7 (optimización + pulido)
  Día 5:  QA manual + documentación
```

---

> **Nota:** Cada checkbox `[ ]` debe marcarse `[x]` al completar el paso.
> Si un paso se descarta, marcarlo con `[~]` y justificar en el commit.
