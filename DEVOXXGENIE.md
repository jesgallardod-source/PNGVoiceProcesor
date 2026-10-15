# DEVOXXGENIE.md

## Project Guidelines

### Build Commands

- **Build:** `./gradlew build`
- **Test:** `./gradlew test`
- **Single Test:** `./gradlew test --tests ClassName.methodName`
- **Clean:** `./gradlew clean`
- **Run:** `./gradlew run`

### Code Style

- **Formatting:** Use IDE or checkstyle for formatting
- **Naming:**
  - Use camelCase for variables, methods, and fields
  - Use PascalCase for classes and interfaces
  - Use SCREAMING_SNAKE_CASE for constants
- **Documentation:** Use JavaDoc for documentation
- **Imports:** Organize imports and avoid wildcard imports
- **Exception Handling:** Prefer specific exceptions and document throws
-antes de comenzar con el desarrollo o investigacion de cualquier tarea, revisa el documento CONTEXT.md para ahorrar memoria de lectura del poryecto
-despues de terminar con cada tarea o promp, actualiza el archivo de contexto para hacer match con los cambios que hiciste

### Dependencies

The project uses the following main dependencies:

- **JUnit** - Testing framework
- **Mockito** - Mocking framework for tests

See build.gradle.kts or pom.xml for the complete dependency list.



### Project Tree

```
PNGVoice/
  src/
    main/
      java/
      resources/
    test/
      java/
  .mvn/
  assets/
    models/
      vosk-model-small-es-0.42/
  config/
    app.json
  pom.xml
  resources/
    LOG.md
    RULES.md
    CONTEXT.md
    PLAN_PNGVOICE.md
  DEVOXXGENIE.md
  dependency-reduced-pom.xml

```
