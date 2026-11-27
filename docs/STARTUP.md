# Process startup and scheduled work

`FindrApp.onCreate` only registers a temporary activity counter and launches
startup. Diagnostics setup, test heap ballast, engine construction and privacy
preferences run on `Dispatchers.IO`. This keeps the main looper available to
JobScheduler even when cold preferences, device queries or model file checks
take a long time.

The application publishes one engine through `awaitEngine()`. `IndexWorker`
and `ChangeWorker` suspend on that shared readiness signal, then use the existing
asynchronous `StoreOpener`/`awaitStore()` path. Cancelling a waiter does not cancel
startup. Initialization failures still propagate to the process's crash handler
and also complete readiness exceptionally.

The engine's power receiver also hands its status recount and scheduling to its
background scope: a power event must not wait on SQLite on the main looper.
Screen-off and UI-hidden scheduling are handed off in the same way.

There is no app-defined `JobService`. WorkManager's manifest-provided
`SystemJobService` remains the JobScheduler entry point; worker constructors do
not look up or construct the engine. IndexWorker's expedited foreground-info
callback only builds its notification. No WorkManager scheduler or constraints
are replaced.

The main screen keeps its splash until readiness. Quick search and keep-learning
defer their content without blocking their activity callbacks. Bench entry points
also wait asynchronously before using the engine. Privacy is initialized on the
main thread from preferences loaded in the background, before any results can be
composed. Its lifecycle counter and the engine's visibility tracker are seeded
with activities that started while initialization was pending; registration and
handoff happen together on the main thread. Memory-trim callbacks do not force
initialization.

`attachBaseContext` still applies the stored app locale synchronously because the
localized context must exist before Android creates components. Startup does not
load inference models or open SQLite on the main thread, and it does not change
model-presence decisions or expose temporary model-missing state to the UI.

`StartupNonBlockingTest` holds an injected engine factory behind a latch and
checks application/main-looper responsiveness, both workers' suspension, waiter
cancellation, SystemJobService return, activity callbacks before readiness and
privacy/lifecycle handoff. The existing store-opener tests cover slow/failed
store opening separately. Phone ANR verification remains a device gate.
