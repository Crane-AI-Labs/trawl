# Android index-open recovery

`FindrEngine` starts `StoreOpener` only after its properties are initialized. SQLite opening and privacy-rule preparation run on the IO dispatcher, never in the application constructor. `IndexStore.open` retains its existing bounded retries and recovery rules; an exhausted open is caught by `StoreOpener`, which keeps retrying with capped exponential backoff. The UI retry action wakes that loop without starting a parallel open or discarding the index.

While opening, the meter says “Opening your index”. A failed open says “Trawl could not open its index; retrying” and its detail sheet offers “Try again”. Searches and recent-file reads return immediately with honest empty results while the store is unavailable; search results are marked incomplete. The explicit opening flag changes the search refresh key when even an empty index becomes ready, so the current query is reissued. Existing records become searchable again as soon as the store is published.

Indexing requested before the first open is remembered for recovery, including when that first open subsequently fails. Background workers still await the store and retry missed work. Starting an already-ready opener does not replace its store. Cancelling a blocking open closes any store that has not been published, rather than losing it at the dispatcher boundary.

`FindrEngineOpenTest` reproduces the legacy synchronous SQLite exception, tests the same exhausted open through the real Android engine, and checks slow/opening searches and off-caller-thread opening. `StoreOpenerTest` exercises retry timing, recovery, manual retry, idempotence and cancellation. `IndexOpeningUiTest` checks the waiting message and retry control, while `RefreshKeyTest` covers ready-empty refreshes.

The engine's Robolectric fixture explicitly denies external-storage-manager access: the fake device has no storage volumes, so Android's real permission query would otherwise fail before the index recovery status can be exercised. Cached filenames remain searchable without that permission.
