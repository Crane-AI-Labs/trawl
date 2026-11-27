# Desktop Compose tests

From the repository root, the desktop suite is a separate Gradle build:

```sh
DISPLAY=:97 ./gradlew -p desktop cleanTest test
```

`cleanTest` ensures the tests execute again rather than reusing an up-to-date result. On Linux,
an existing X display (Xvfb is sufficient) also enables the native-window tests. The offscreen
tests do not require a display.

Use the test-only helpers in `src/test/kotlin/trawl/desktop/ComposeTestHarness.kt` for Compose
tests, including screenshot tests:

- `runComposeUiTest` and `runDesktopComposeUiTest` run the test scheduler, scene rendering,
  effects and cleanup on Swing's event-dispatch thread. They use Compose's queued dispatcher
  APIs so background state emissions do not resume composition on the emitting thread.
- `TestImageComposeScene` creates, renders and closes its scene on that same thread, with
  `Dispatchers.Swing` for effects and recomposition. Each call is dispatched separately so
  background work can resume while a screenshot test waits between frames.

Do not import the unconfined Compose test entry points or instantiate `ImageComposeScene`
directly in tests. Rendering a scene on the test worker while state collection resumes on a
background worker violates `SnapshotStateObserver`'s thread ownership. Serial Gradle test
execution alone does not prevent that race.

`ComposeTestHarnessTest` checks thread ownership across background state emissions, effect
resumption, rendering and disposal, plus reentrant dispatch and assertion-error propagation.
Existing UI assertions and screenshot thresholds remain unchanged.
