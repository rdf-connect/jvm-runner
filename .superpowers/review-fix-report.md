# Review fixes — feat/remote-server

Five verified review findings, fixed on `feat/remote-server`, one commit, not pushed.

## 1. Slot exhaustion past the handshake (security)

**Was:** `Handshake.TIMEOUT_MILLIS` (5 s) covers the IRI line only. A peer that wrote
`x\n` and then went silent passed the handshake and pinned a connection slot for the
life of the process: `SocketBridge` sets `soTimeout(0)` on the pumps, the loopback
`ManagedChannel` has no keepalive and no deadline, and `awaitEnd()` blocks in an untimed
`anyOf(...).get()`. 32 of those and `MAX_GRPC_CONNECTIONS` is spent until restart.

**Now:** the whole unestablished phase is bounded.

- `RunnerServer.ESTABLISH_MILLIS = 30_000`, documented against the orchestrator's own
  30 s budget for connect-back + identify. Only the unestablished phase — an established
  connection still has no deadline, because pipelines legitimately run for hours.
- A single shared daemon `ScheduledExecutorService` (`rdfc-deadline-`, one thread) fires
  one check per connection at the deadline. If the channel never reached READY the check
  closes the bridge; that completes `bridge.done()`, unblocks `awaitEnd`, and the
  existing error path does the rest — runner told, history entry written, socket closed,
  slot handed back. Nothing new had to be taught about cleanup.
- "Established" = the gRPC channel observed READY, i.e. the orchestrator answered the
  HTTP/2 preface, so the Runner's connect stream really is up. No protocol change:
  `watchChannel` already mirrors channel state, it now also reports READY through an
  `onReady` callback. The check is cancelled on the first READY, and again in `serve()`'s
  `finally` so a connection that ended some other way leaves no armed timer behind.
- `deadlines.shutdownNow()` in `stop()`. `schedule()` during a shutdown is caught
  (`RejectedExecutionException`) and shrugged off — that connection is being ended anyway.
- `SocketBridge` constructor now sets `SO_KEEPALIVE` on the orchestrator socket: the
  cheap OS-level backstop for a peer that dies silently *after* establishment.

**Test:** `ConnectionFlowTest.aConnectionThatNeverEstablishesIsEvicted` — connect, write
`urn:test:silent\n`, send nothing else, with a 500 ms deadline through a new
package-private constructor parameter (the minimal seam, mirroring the existing
`maxConnections` one). Asserts the slot returns to 0, the socket is closed, and the
runner is in the history with status `error`. Verified it exercises the new path: the
eviction warning ("did not establish its stream within 500 ms") appears in the test
output.

## 2. Stderr wall on shutdown and abrupt disconnect

**Was:** quieting keyed on the per-handler `closed` flag, which only `Runner.closeLogStreams()`
sets. On the server's shutdown path `Connection.cancel()` dropped the transport without
ever telling the runner, so every log stream died of its own accord a moment later and
each one printed — one line per processor, out of a shutdown that went perfectly.

**Now (a):** `Connection.cancel()` tears the runner down *before* it drops the transport —
`runner.onError(new StatusRuntimeException(Status.UNAVAILABLE...))`, the same shape gRPC
would report a moment later anyway, only in time to be useful. `finish()` then runs
`closeLogStreams()` while there is still a channel to half-close on, and what arrives
after the transport goes is the routine teardown status a closed handler stays quiet
about.

**Ordering inside `Runner.finish` was verified, not changed.** `closeLogStreams()` already
runs before `onComplete` (the CLI's channel shutdown) and after steps that only touch the
runner's own state — none of the steps above it drops the stream or the channel
(`stream.onCompleted()` half-closes the *connect* stream, not the log calls). The comment
there now states the invariant explicitly, including the requirement it puts on callers
outside the class.

**Now (b):** the residual race is covered by finding 3.

**Test:** `ShutdownTest.shuttingDownDoesNotReportTheLogStreamsAsFailed` — real server, live
post-handshake connection, `shutdown()`, asserts zero WARNING-or-worse records on the
`io.github.rdfc.GrpcLogHandler` logger. Confirmed it fails without the fix (disabled the
`runner.onError` call, test went red, restored).

## 3. Post-close `onError` demoted genuine faults

**Was:** anything after `close()` went to FINE, so an INTERNAL on a closed handler was
indistinguishable from a clean teardown.

**Now:** status-aware, via `Status.fromThrowable`.

| when | status | where |
|---|---|---|
| before `close()` | any | `LOGGER` at WARNING |
| after `close()` | UNAVAILABLE, CANCELLED | `LOGGER` at FINE |
| after `close()` | anything else | `LOGGER` at WARNING |

**Decision, documented in the class:** the pre-close report is a clean swap from
`System.err.println` to the named `LOGGER` at WARNING. Safe for the same reason the FINE
line already was — a `GrpcLogHandler` is only ever attached to the anonymous logger
`loggerFor` builds, so no record on the named logger can come back into this handler.
Output stays formatted like every other line, is filterable, and is assertable in tests.
The one other `System.err.println` in the class, in `close()`, was swapped for the same
reason and to keep one policy rather than two. `publish()`'s `printStackTrace` was left
alone — different path, not in scope.

Something that is not a gRPC status at all (`Status.fromThrowable` → UNKNOWN) counts as
non-routine: a close explains UNAVAILABLE and CANCELLED, it explains nothing else.

**Tests:** the two stderr-capture tests were rewritten onto log records
(`aStreamThatFailsWhileOpenIsReportedLoudly`, `aStreamThatFailsAfterAnIntentionalCloseIsQuiet`)
plus two new ones — `aRealFaultAfterACloseIsStillReported` (post-close INTERNAL → one
WARNING) and `aCancelledStreamAfterACloseIsQuietToo`.

## 4. Dead exception ceremony in the test helper

`PrintStream(OutputStream, boolean, String)` → `PrintStream(OutputStream, boolean, Charset)`.
Dropped the `try`/`catch`, the `AssertionError`, the `.name()` and the
`UnsupportedEncodingException` import. The catch had also been wrapping `action.run()`,
which would have reported an unrelated failure as "UTF-8 is not optional".

The helper is still used, by the quiet-teardown test, which now asserts stderr is
*empty*. It also forces the root logger's handlers to be built before it redirects
`System.err`: a `ConsoleHandler` binds to whatever `System.err` was when it was
constructed, so one built inside the capture window would have kept writing into a
discarded buffer for the rest of the JVM. (That is not hypothetical — it is what made
the first draft of these tests fail.)

## 5. Unreachable clamp

`Math.min(millis, Integer.MAX_VALUE)` could never bind: the budget it counts down from is
an `int` number of milliseconds. Now `(int) Math.max(1, millis)`, with the cast kept
obvious and a comment saying why no upper clamp is needed. The floor comment above the
method (never zero, because `setSoTimeout(0)` means wait forever) is untouched.

## Verification

- `./gradlew clean build test` — BUILD SUCCESSFUL, **192 tests, 0 failures, 0 skipped**
  (was 188; +4: one eviction test, one shutdown-quietness test, two log-policy tests).
- `./tests/e2e/run-e2e.sh` — **PASS**, both messages travelled send → echo → log through
  the remote runner against the real `@rdfc/orchestrator-js`. `tests/e2e/server.log` after
  the run contains **zero** "log stream to the orchestrator failed" lines.

## Known gaps / concerns

- **Abrupt disconnect still logs one WARNING per handler.** When the orchestrator vanishes,
  `awaitEnd` learns about it from `bridge.done()` — the transport is *already* dead at that
  point — so the log streams fail before `closeLogStreams()` can close them, and those are
  pre-close failures, reported at WARNING. This is the residual race the review anticipated;
  finding 3 makes it formatted and filterable rather than raw stderr, but it does not make
  it silent. Silencing it properly would mean the handlers learning that the *runner* is
  ending, independently of their own `close()` — a bigger change than this review asked for.
- **Establishment eviction runs on one thread.** A `SocketBridge.close()` that hits its
  documented 8 s worst case delays the eviction of another connection that has already
  been waiting 30 s. Bounded and self-correcting, but worth remembering if the cap ever
  grows a lot.
- **`SO_KEEPALIVE` uses the OS interval** (two hours by default on Linux). It is a backstop
  against a peer that is simply gone, not a liveness check.
- The e2e never exercises the eviction path (its orchestrator always speaks gRPC promptly);
  that path is covered by the unit test only.

---

# Follow-up: winner-take-all establishment gate

Second commit, addressing the residual Medium race the re-review found in the
eviction mechanism added above.

## The race

`evictUnlessEstablished` read `established.get()` once on the way in and then went
on to log a warning and close the bridge. A READY landing anywhere inside that
window — and the window included a synchronous `LOGGER.warning`, so it was not
narrow — found a connection that was about to be evicted for not being
established. Cancelling could not help: `cancel(false)` is non-interrupting and
does not stop a task that is already running, and `cancel(true)` would have been
worse (an interrupt in the middle of `SocketBridge.close()`).

Consequence: at the deadline boundary, a legitimately established connection could
be dropped. Rare, but a real orchestrator that connects back slowly is exactly the
case the 30 s deadline was sized for, so the boundary is where real traffic lands.

## The fix

`RunnerServer.Establishment` — a package-private static gate holding an
`AtomicReference<Phase>` over `PENDING / ESTABLISHED / EVICTING`:

- the READY callback calls `establish()` → `CAS(PENDING → ESTABLISHED)`
- the deadline check calls `evict()` → `CAS(PENDING → EVICTING)`, and **only closes
  the bridge if its own CAS won**

Exactly one side can win, atomically, with no window. `cancel(false)` is kept, now
explicitly as a best-effort optimisation — a check that is already running is left
to finish and find that it lost, which costs nothing. The warning moved to *after*
the gate, so it is only ever logged about a connection that really is being
dropped.

## Test — and which option was chosen

**The targeted unit test of the gate**, `EstablishmentTest`, not the integration
route. Lining a gRPC channel reaching READY up against a scheduled task to within
microseconds, repeatedly, is a test that would pass for reasons nobody can name and
fail on a loaded machine. Two threads on a `CyclicBarrier` hit the window every
round instead.

- `theStreamComingUpFirstWins`, `theDeadlineFirstWins`, `neitherSideWinsTwice` —
  the uncontested transitions, both directions, and no double win (the READY
  callback fires on every observation, not only the first).
- `exactlyOneSideWinsWhenTheyRaceHeadOn` — 20 000 rounds, two threads released
  together on a barrier, asserting exactly one winner per round.

Verified the test detects the bug it exists for: replacing `evict()`'s CAS with the
read-then-act the flag version did makes `exactlyOneSideWinsWhenTheyRaceHeadOn` (and
`theStreamComingUpFirstWins`) fail; restored afterwards.

## Verification

- `./gradlew clean build test` — BUILD SUCCESSFUL, **196 tests, 0 failures, 0 skipped**
  (192 + 4 gate tests).
- `ConnectionFlowTest` and `ShutdownTest` re-run green as part of it.
- `./tests/e2e/run-e2e.sh` re-run — **PASS**. Worth doing: the normal path now goes
  through `establish()`, so the e2e exercises the winning side of the new gate.

## Concerns

None new. The single-threaded eviction scheduler and the abrupt-disconnect WARNING
noise noted above are unchanged.

---

# Review fixes — the establishment gate, round two

Four verified findings on commit `4291b52`, all in and around the connection
establishment gate.

## 1. Spurious eviction warning when a connection ends at the deadline boundary

`serve()`'s `finally` only cancelled the scheduled check and never resolved the
gate. `cancel(false)` does not stop a task that is already running, so a check that
had started when the connection ended by itself found the phase unclaimed, won
`evict()`, warned that a connection which had *already ended* "did not establish its
stream within N ms; dropping the connection", and closed an already-closed bridge.
Harmless but misleading — and it falsified the javadoc claim that the warning is
only ever about a connection really being dropped.

**Fix.** A third resolution arm, `close()`. The gate is hoisted out of the `try` so
the `finally` can reach it, and the `finally` claims it *before* cancelling the
timer — cancelling first would leave exactly the already-running check free to
evict. A check that runs afterwards loses and stays silent. Winner-take-all is
unchanged: exactly one of establish / evict / close resolves the phase.

The javadoc on `Establishment` and on `evictUnlessEstablished` now describes all
three arms and says what `close()` takes off the table.

## 2. Cancelled checks lingering on the scheduler queue

`Executors.newSingleThreadScheduledExecutor` returns a delegating wrapper, so
`setRemoveOnCancelPolicy(true)` could not be reached through it. Every connection
that came up normally cancelled its check and left it queued — capturing the bridge
— until its original delay had passed, up to `ESTABLISH_MILLIS` per connection ever
served.

**Fix.** `newDeadlineScheduler()` builds a `ScheduledThreadPoolExecutor(1,
daemonThreads("rdfc-deadline-"))` with `setRemoveOnCancelPolicy(true)`. The field
stays a `ScheduledExecutorService`; nothing else changes, and the comment now says
why the factory method is not used.

## 3. Write-only winner state — resolved by shrinking the gate

**What remained: an `AtomicBoolean`, and the three named methods over it. The enum
is gone.**

The `Phase` enum recorded *which* side won and nothing ever read it — `phase.get()`
appeared nowhere. Finding 1 was the thing that might have earned it back, and it
does not: `close()` needs to claim the phase, not to be distinguishable from the
other claimants afterwards. Every loser's job is the same nothing, and the winner is
the one holding the answer, so what is worth keeping is the single bit saying the
phase has been claimed.

So `Establishment` is one `AtomicBoolean resolved` and one private
`resolve() → compareAndSet(false, true)`. `establish()`, `evict()` and `close()`
each delegate to it and exist so the three call sites can say what they mean — which
is a naming choice with no state behind it, rather than a tri-state that pretends to
be consulted. A tri-state whose value is never read is precisely the misleading part
the review flagged; removing it is the honest reading of the finding.

## 4. Dead `rounds` counter in `EstablishmentTest`

An `AtomicInteger` touched only by the main thread and asserted against the loop
bound it mirrors — `assertEquals(ROUNDS, rounds.get())` could not fail. Deleted,
counter and assertion. The per-round exactly-one-winner assert and the final
`established + evicted + closed == ROUNDS` sum already carry the invariant.

## Tests

- `EstablishmentTest.theConnectionEndingFirstWins` — the uncontested CLOSED arm:
  `close()` wins, and `evict()` and `establish()` both lose afterwards.
- `theStreamComingUpFirstWins` / `theDeadlineFirstWins` extended so each asserts the
  other two arms lose; `neitherSideWinsTwice` → `noSideWinsTwice`, now covering
  `close()` too.
- `exactlyOneSideWinsWhenTheyRaceHeadOn` extended from two racers to **three** —
  establisher, evicter, closer, four barrier parties, 20 000 rounds. A round that
  pits all three against each other is a round that pits each pair against each
  other, so close-vs-establish and close-vs-evict are both covered without a second
  contested test. Deterministic in the same way as before: the barrier releases all
  three together and the assertion is the invariant, never a distribution.
- `ConnectionFlowTest.aConnectionThatEndsBeforeEstablishingIsNotAnnouncedAsDropped`
  — the observable behaviour of finding 1. A capturing `java.util.logging.Handler`
  on the `RunnerServer` logger, a connection that hands its IRI over and then closes
  well inside a 1.5 s deadline, and an assertion that nothing about *that* runner
  IRI was ever logged as not having established once the deadline has passed. The
  IRI is part of the match, so a warning from any other test cannot make it pass or
  fail.

## Verification

- `./gradlew clean build test` — BUILD SUCCESSFUL, **198 tests, 0 failures, 0
  skipped** (196 + `theConnectionEndingFirstWins` +
  `aConnectionThatEndsBeforeEstablishingIsNotAnnouncedAsDropped`).
- `./tests/e2e/run-e2e.sh` re-run — **PASS**, both messages through
  send → echo → log on the remote runner.

## Concerns

- The new `ConnectionFlowTest` case sleeps past the deadline (~2 s) because proving
  that *nothing* was logged means waiting for the moment it would have been. It is
  the only sleep of its kind in the suite. The margin is generous — the connection
  ends in milliseconds against a 1.5 s deadline — so it should not be load-sensitive,
  but it is a timing test and worth knowing about.
- With `removeOnCancelPolicy` on, that test's check is normally removed from the
  queue before it can run at all, so the case mostly exercises the *ordinary* path
  rather than the raced one. The raced one is what `EstablishmentTest` covers, at the
  level where it can be hit deterministically.

---

# Review fixes, round 2 — feat/remote-server

Three verified findings from the whole-branch review, fixed on `feat/remote-server`,
one commit, not pushed.

## 1. A failed init could end the runner as a SUCCESS (medium, correctness)

**Was:** `startProcessor` claims two callbacks at `proc` receipt (`awaiting += 2`) and
`failedToInitialize` hands both back with `decreaseAndCheckEnd(2)`. When the failure
happened before a sibling's `proc` had arrived, that give-back landed on zero and called
`finish(null)` — the orderly-completion path. The main stream was half-closed, `completion`
completed *normally*, and in server mode `RunnerServer.serve` took the no-exception branch:
`setStatus(id, DONE)` and "Runner … completed" for a pipeline of which not one processor
ever ran. Concretely: `proc(A)` whose jar URL 404s → `startProc` throws synchronously
inside `onNext` → 2→0 → finished; `proc(B)` then found a torn-down runner
("runner was torn down") and a half-closed stream.

**Now:** the runner remembers the first init that failed and never calls a zero reached
that way an orderly completion.

- New `AtomicReference<Throwable> initFailure` on `Runner`, set in `failedToInitialize`
  **before** the callbacks are handed back (the give-back can be what ends the runner, so
  whoever observes the zero must already see the failure). First failure wins; the stored
  value is `Errors.unwrap(error)`, so `completion` fails with the root cause rather than
  with future plumbing.
- `decreaseAndCheckEnd` now calls `finish(this.initFailure.get(), true)` at zero. Null on
  the ordinary path — the all-succeeded run still finishes with `null`, exactly as before.
- `finish` was split into `finish(error)` and `finish(error, sayGoodbye)`. "Nothing left
  to wait for" and "is the stream still up" are two different questions: reaching zero
  half-closes the main stream whether the run ended in a failure or not (the connection is
  alive and the orchestrator deserves the goodbye), while `onError`/`onCompleted` still
  tear down without touching a dead call. Without this split, ending on an init failure
  would have stopped saying goodbye and left the CLI cancelling the call instead.
- Counter arithmetic is untouched: no extra decrement, no negative, still exactly-once
  teardown through the existing `finished` CAS.
- Ordering verified: `sendProcInit(error)` (and now a `severe` log line naming the
  processor and the failure) go out *inside* `failedToInitialize`, before the
  `decreaseAndCheckEnd(2)` in its `finally`. The new test asserts that the
  `ProcessorInitialized` is the last message on the stream and that exactly one goodbye
  followed it.

**Tests:** new `RunnerLifecycleTest.aFailedInitWithNoSiblingsYetIsNotAnOrderlyCompletion`
— `proc(A)` with nothing registered (so `startProc` throws synchronously, the 404-jar
shape) → `completion()` completes **exceptionally**, the failure names the cause, the
`ProcessorInitialized` carries the error and went out before the teardown, `onComplete`
ran exactly once.

**Changed expectations (deliberate):** `aFailedInitHandsBothCallbacksBack`,
`aFailedInitDoesNotStrandItsSiblings` and `aFailedInitThatLandsLastStillEndsTheRunner`
gained an assertion that `completion()` now completes exceptionally. Their existing
assertions (counter back to baseline, exactly one teardown, no produce for the broken
processor, siblings not stranded) are unchanged and still pass — the prior fixes they
encode are intact. The behaviour change is intended and is the finding: a run in which a
processor never initialized is not a run that succeeded, whichever processor's callback
happened to land last.

## 2. Error strings dropped the exception type (low)

**Was:** `Errors.describe` returned `root.getMessage()`. For the commonest startup
failures the type *is* the payload: `ClassNotFoundException("rdfc.test.Echo")` reached the
user as `rdfc.test.Echo`, `NoSuchFileException` as a bare path, `FileNotFoundException` as
a bare URL. `master` sent `e.toString()`, which at least named the failure.

**Now:** `SimpleName: message`, still after unwrapping `CompletionException` /
`ExecutionException` to the root cause. A null or blank message falls back to the simple
name alone; an anonymous class (empty simple name) falls back to `toString()`.

**Changed expectations:** `ErrorsTest` (four cases rewritten to the new shape) and
`RunnerLifecycleTest.theAckOfAFailedMessageCarriesTheError` (`"consumer blew up"` →
`"RuntimeException: consumer blew up"`). Three cases added: the three type-is-the-payload
exceptions, a blank message, an anonymous exception. `anExceptionWithoutAMessageFallsBackOnItsType`
now expects `NullPointerException` rather than `java.lang.NullPointerException` — the
simple name, consistently with the message case; the package of an exception is noise in a
line an orchestrator puts in front of somebody.

## 3. One out-of-tree import collapsed the serve root (low, security-ish)

**Was:** `ServeRoot.of` took the common ancestor of the config dir *and every whitelisted
file*, and `Whitelist.build` follows `owl:imports <file:…>` anywhere on disk. So
`/srv/rdfc/config/processors/echo.ttl` importing `file:///opt/ontologies/shapes.ttl` made
the serve root `/`: the index advertised absolute filesystem paths, and `ServedJars`'
documented containment check `real.startsWith(serveRoot)` became vacuous.

**Now:** the root is clamped to the configuration directory and never widens past it.

- `ServeRoot.of(configDir, whitelist, log)` returns the normalized config dir and logs one
  warning per whitelisted file that lies outside it, naming the file and saying it is
  unreachable over HTTP (the js-runner's "unreachable" handling). Since the config dir was
  always part of the input, the old common-ancestor computation could only ever equal or
  widen past it — so the clamp *is* the config dir, and `ServeRoot.commonPath` became dead
  code and was removed with its tests.
- Whitelisted files outside the root stay whitelisted (they were parsed, and their imports
  belong to the served set) but are neither advertised nor served. `IndexGenerator` already
  skips what it cannot relativize; `RunnerServer.serveFile` now also requires
  `real.startsWith(this.serveRoot)` next to whitelist membership. That second half was
  genuinely missing: serving was *only* whitelist membership, so before the clamp a raw
  `GET /../shapes.ttl` (a client that does not normalize) would have handed out a
  whitelisted file outside the tree.
- README: a paragraph on what the HTTP root maps onto and what happens to imports outside
  it.

**Changed expectations:** `ServeRootTest` rewritten around `of` —
`isTheDeepestDirectoryHoldingEverything` is gone (that behaviour is the bug), the
`commonPath` cases (`doesNotConfusePrefixesOfNamesWithDirectories`,
`fallsBackOnTheFilesystemRoot`, `normalizesBeforeComparing`, `refusesAnEmptyCollection`)
went with the method; the clamp, the warning, sibling-prefix containment and normalization
are covered instead. New end-to-end case
`HttpEndpointsTest.aWhitelistedFileOutsideTheConfigDirectoryIsNotServed`: a description
importing a file next to (not under) the config dir → root stays the config dir, the import
is still on the whitelist, `GET /echo.ttl` is 200 and `GET /../shapes.ttl` and
`GET /%2e%2e/shapes.ttl` are 403.

The e2e fixture is unaffected: `tests/e2e/server.ttl` sits in `tests/e2e/`, every
catalogue and the served jar live under it, so the root is the same as before.

## Verification

- `./gradlew clean build test` — BUILD SUCCESSFUL, **201 tests, 0 failures, 0 skipped**
  (198 + 3 net: +3 `ErrorsTest`, +1 `RunnerLifecycleTest`, +1 `HttpEndpointsTest`,
  −2 `ServeRootTest`).
- `./tests/e2e/run-e2e.sh` re-run — **PASS**: both messages through send → echo → log on
  the remote runner, server log still ends in "Runner http://localhost:3000/jvmRunner
  completed" (the success path is untouched).

## Concerns

- Marking the whole run a failure when *one* processor fails to initialize is a policy
  choice. It matches the js-runner (an init failure aborts the pipeline) and the
  orchestrator already learns which processor failed through `ProcessorInitialized`, but a
  pipeline that could meaningfully carry on without one processor is now reported as
  errored on the dashboard. That is the intended reading of the finding.
- `completion` carries the *first* init failure only. With several failing processors the
  later ones are in the log and in their own `ProcessorInitialized` messages, not in the
  connection's error string.
- The type prefix widens error strings the orchestrator shows verbatim; anything downstream
  matching those strings exactly (nothing in this repo does) would need updating.

---

## Round: serve-root canonicalization invariant (follow-up on c69150d)

Three low-severity findings from the re-review of c69150d, all fixed in one commit.

### 1. `ServeRoot.of` invariant was documentary, not structural

`ServeRoot.of` returned `configDir.toAbsolutePath().normalize()`, while
`RunnerServer.serveFile` compares that root against a `toRealPath()` result
(`runner/src/main/java/io/github/rdfc/server/RunnerServer.java:882-887`). The two only agreed
because `ServerConfig` happens to canonicalize the configuration path before handing the
directory over. A caller passing a symlinked directory would have made every containment
check fail and 403 every served file, with nothing in the response or the log to point at
the cause.

`ServeRoot.of` now canonicalizes the root itself: `toRealPath()`, falling back to
`toAbsolutePath().normalize()` on `IOException` with a FINE line naming the fallback. The
fallback matters because the configuration directory need not exist yet, and that is not
worth refusing a start over — in that case the invariant is exactly as good as the caller's,
which is what it was everywhere before. The javadoc now states the invariant is enforced
here rather than assumed.

Tests (`runner/src/test/java/io/github/rdfc/server/ServeRootTest.java`):

- `resolvesASymlinkedDirectoryToItsRealPath` — `@TempDir` plus `Files.createSymbolicLink`,
  guarded by an assumption for filesystems that refuse symlinks. Asserts the root is the real
  path, that a file in the directory passes the same `startsWith(root)` check the file handler
  makes (so it serves rather than 403s), and that nothing was warned about as unreachable.
  It runs, not skips, on this platform — confirmed in the XML results.
- `fallsBackToTheAbsolutePathWhenTheDirectoryDoesNotExist` — a configuration directory that is
  not on disk still yields a root.
- `normalizesTheWhitelistBeforeComparing` — the re-review noted nothing covered the
  normalization of each whitelist entry before the containment test since the old
  `normalizesBeforeComparing` went away. A `..` inside the tree is not reported unreachable.

The pre-existing tests use paths like `/srv/conf` that do not exist, so they exercise the
fallback branch and were unaffected.

### 2. A logging failure could have suppressed the failure report

In `Runner.failedToInitialize` (`runner/src/main/java/io/github/rdfc/Runner.java`) the
`logger.severe(...)` sat inside the same `try` as, and ahead of, `sendProcInit(uri,
Optional.of(...))`. A throwing log call would have been caught by the outer handler and the
orchestrator would never have learnt which processor failed. Not reachable today —
`GrpcLogHandler.publish` swallows `Exception` — but the ordering put the diagnostic ahead of
the report that the orchestrator acts on.

`sendProcInit` now goes first and the log call sits in its own `try` that swallows, so a
logging handler cannot affect the report path in either order. Message content is unchanged.

### 3. README overstated the serve-root clamp as imports-only

`README.md` described the clamp as applying to imported files. It applies to every whitelisted
file, including the operator-named `rdfc:processorConfig` entries: with
`rdfc:processorConfig <../processors/echo.ttl>` the file is read and parsed, but
`IndexGenerator` skips the whole processor entry (not only its `rdfs:isDefinedBy`), so the
processors are advertised in no index and the file 403s — with startup warnings naming the
file and each processor left out. The paragraph now says so explicitly, since an operator
reading the old text would have expected a named description to be served.

### Verification

- `./gradlew clean build test` — BUILD SUCCESSFUL, 204 tests, 0 failures, 0 skipped
  (201 before; +3 in `ServeRootTest`).
- `./tests/e2e/run-e2e.sh` — `PASS: both messages travelled send -> echo -> log through the
  remote runner`.

### Concerns

- The `toRealPath()` fallback keeps a start working when the configuration directory is
  missing, so a symlinked *and* not-yet-created directory would still mismatch. That
  combination cannot serve anything anyway, and refusing to start over it would be a
  behaviour change beyond this round.
- The symlink test depends on the platform allowing `Files.createSymbolicLink`; on a
  filesystem that does not, it reports as an assumption failure rather than coverage.
