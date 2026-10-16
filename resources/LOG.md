# LOG — PNGVoice

> Últimos 3 cambios en la aplicación. Al superar 3 entradas, se elimina la más antigua y se renumera.

1. **Logging de actualización de la imagen de pronunciación (diagnóstico).**
   - `VoskRecognizer`: nuevo método `logNewWord(word, source)` que registra a
     nivel INFO cada vez que se detecta una **nueva palabra** reconocida
     (guard `lastRecognizedWord` para no repetir). Se llama desde `acceptWaveForm`
     tanto en el branch **PARTIAL** como **FINAL**. Formato:
     `[VOSK] WORD PARTIAL → 'programa' (1717050123456 ms)`.
   - `PngVoiceApp.applyViseme()`: nuevo log `[UI] IMG → A | imagen asignada: SÍ/NO`
     que se emite **solo cuando la imagen visible realmente cambia** (guard
     `lastAppliedViseme`). Indica si el viseme tiene imagen asignada, lo que
     permite detectar el caso "se reconoce la palabra pero no hay imagen para
     ese viseme".
   - Diagnóstico: si no hay líneas `[VOSK] WORD`, el reconocedor no produce
     texto nuevo; si hay `WORD` pero no `[UI] IMG`, el fallo está entre la cola
     y la UI (o falta imagen asignada al viseme).

2. Creación del proyecto: estructura Maven, JavaFX, audio capture, VolumeAnalyzer, VoskRecognizer, AvatarRenderer, ConfigLoader, tests.
