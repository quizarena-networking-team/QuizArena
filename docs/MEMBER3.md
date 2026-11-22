# Member 3: quiz engine, scoring, concurrency and buzzer

Implementation branch: `feature/m3-multithreaded-networking`.

This module implements the Member 3 work package from architecture blueprint
section 12.3. It supplies server services, gameplay handlers, JDBC DAOs and
JavaFX question/buzzer/leaderboard screens. The existing welcome screen and
`ServerMain` remain unchanged: the production NIO gateway, TCP client, host TLS
coordinator and database foundation are other members' integration dependencies.

## Ownership and handoff

| Supplied here | Adapter supplied by teammate |
|---|---|
| `QuizService`, `QuizRequestHandler` | M1 registers the three gameplay handlers |
| `PlayerDirectory` | M1 reads authoritative room membership and display names |
| `GameEventSink` | M1 enqueues events to its thread-safe outbound queues |
| `QuizClientPort`, FXML controllers | M2 adapts its existing TCP client and navigation |
| Trusted Java host methods | M5 authenticates TLS host, checks room ownership and coordinates room/poll state |
| `JdbcQuizDao`, `JdbcResultDao` | M5 supplies a `DataSource`, blueprint schema, seed data and existing session ID |
| Ordered public quiz events | M4 can observe these for supplementary announcements |

There is no second production TCP server, TCP client, room registry, login
implementation, schema migration or credential configuration in this module.
All shared protocol Java classes, `pom.xml` and `docs/PROTOCOL.md` are unchanged.

## Backend integration

Create one service for the active room and its existing `quiz_sessions.id`:

```java
QuizService quiz = new QuizService(
    roomCode,
    sessionId,
    playerDirectory,          // M1 adapter
    event -> broadcastQueue.enqueue(event), // illustrative M1 adapter; non-blocking
    new JdbcQuizDao(dataSource),
    new JdbcResultDao(dataSource)
);
QuizRequestHandler handler = new QuizRequestHandler(quiz);
// Register handler for each handler.supportedTypes() in M1's dispatcher.
// Close quiz when the room/server is shut down.
```

`broadcastQueue` is illustrative, not an added dependency. The sink must enqueue
events in invocation order, return immediately and handle backpressure/disconnected
clients. It must not write sockets, call JavaFX or JDBC, or wait for another
service while the quiz lock is held. `PlayerDirectory` also must be non-blocking
and thread-safe. Supply only guest player identities, not a host observer.

The TCP handler accepts only:

- `SUBMIT_ANSWER`
- `PRESS_BUZZER`
- `GET_LEADERBOARD`

Identity is taken from `RequestContext.playerId()` and checked against the current
directory. `context.roomCode()` and the request room must both match this service.
Payload fields such as `playerId`, `role` or `score` never grant identity or points.
`RequestContext.reply` must enqueue thread-safely: it may run on a quiz worker.

M5's authenticated host coordinator calls these **internal Java methods**:

| Existing host command | Java call | Successful result |
|---|---|---|
| `START_QUIZ` | `startQuiz(quizId, buzzerQuestionId)` | `Void`; first question event is queued |
| `NEXT_QUESTION` | `nextQuestion()` | `Void`; next question event is queued |
| `OPEN_BUZZER` | `openBuzzer(buzzWindow, answerWindow)` | `Void`; buzzer event is queued |
| `FINISH_QUIZ` | `finishQuiz()` | Immutable `ResultSnapshot`, after commit |

These return `CompletableFuture`; attach completion handlers rather than calling
`join()` on selector, TLS accept or JavaFX threads. M5 wraps completions in the
existing `*_RESULT` envelope and preserves the host request ID. Public service
methods deliberately do not implement TLS or trust a client-provided role.

Before starting, M5 must exclude an active poll and authorize the host for this
room. M1 should freeze participant joins for the running quiz. The engine freezes
its participant roster at start; newly joined identities cannot submit mid-quiz.
Disconnected players retain their earned scores in final results but cannot send
new actions. Rejoining with a new identity does not restore participation.

To demonstrate three normal questions plus one buzzer, seed **four questions**
and reserve one via `startQuiz(quizId, buzzerQuestionId)`. The reserved question is
excluded from the normal sequence and may be used once during a review interval.
Without a reservation, all questions are standard and opening a buzzer is rejected.
This prevents reusing a question whose correct answer has already been revealed.

## Lifecycle and concurrency

```text
LOBBY -> LOADING -> QUESTION_OPEN -> QUESTION_REVIEW
                      ^                  |
                      +-- NEXT_QUESTION --+

QUESTION_REVIEW -> BUZZER_OPEN -> BUZZER_AWAITING_ANSWER -> QUESTION_REVIEW
                         |                |
                         +--- timeout ----+  (winner answer also ends the round)

final QUESTION_REVIEW -> SAVING -> FINISHED
any state -> CLOSED
```

Four workers and a bounded queue of 256 tasks keep caller threads free. One room
lock protects state transitions, duplicate checks, scores and event ordering.
`BuzzerService` additionally uses `AtomicReference.compareAndSet` to select a
single winner. It is the first valid request accepted under the server lock,
not a promise about physical keypress order or network latency.

`ScheduledExecutorService` schedules question and buzzer deadlines.
`System.nanoTime()` enforces expiry even if wall time changes or a timer callback
is delayed. Requests are rejected at or after the deadline. A generation token
prevents cancelled callbacks from closing a later question/answer phase.
`deadlineEpochMs` is an indicative client countdown, never proof of timeliness.

Answers are accepted once per player per round. Correct standard answers earn
`question.points` (default 100), wrong answers earn zero, and correct buzzer answers
earn 50. Late/invalid/duplicate answers do not change statistics or scores.
Only the selected buzzer winner can answer that round.

Leaderboard ties use competition ranking: 100, 100, 0 produces ranks 1, 1, 3.
Names, then stable player IDs, make tied display order deterministic. Scores and
correctness stay hidden until `QUESTION_ENDED`, including leaderboard queries
during an open round. This prevents players discovering answers from score changes.

JDBC runs outside the room lock on workers. Final results are frozen before save;
after failure only a finish retry is allowed, using the same immutable snapshot.
`QUIZ_FINISHED` is queued only after commit. Successful finish calls are idempotent.
No database failure claims a successful save. Close cancels timers and rejects new
tasks; already accepted tasks drain and complete their futures. In-flight JDBC
may complete after close, but no finished event is emitted for a closed service.

## Member 3 payload proposal — review before integration

These are feature-specific schemas for existing v1 message names. This document
is the reviewable proposal; it does not change the authoritative shared envelope
or silently add message types. M1/M2/M5 must review these fields (especially
`roundId` and the reserved buzzer question) before merging/integrating, then record
the agreement in `docs/PROTOCOL.md`.

All IDs are strings. Each player request requires `requestId` and `roomCode`.
Field names and case are exact.

### Player requests and direct responses

| Request | Required payload | Success type and payload |
|---|---|---|
| `SUBMIT_ANSWER` | `questionId`, `roundId`, `optionId` (`A`–`D`) | `SUBMIT_ANSWER_RESULT`: `accepted: true`, `questionId`, `roundId` |
| `PRESS_BUZZER` | `questionId`, `roundId` | `PRESS_BUZZER_RESULT`: `winner: true`, `questionId`, `roundId` |
| `GET_LEADERBOARD` | `{}` | `LEADERBOARD_RESULT`: `entries` |

The server generates an unpredictable `roundId` for each round. It prevents stale
actions being applied to another round. A round ID is not an authentication token.
Replies preserve the original `requestId`; an accepted answer does not reveal
correctness or points before review. Retries cannot repeat answer or buzzer effects.

Errors use existing codes and `{}` payload: `BAD_REQUEST`, `ROOM_NOT_FOUND`,
`ROOM_CLOSED`, `INVALID_PLAYER`, `QUESTION_CLOSED`, `ANSWER_ALREADY_SUBMITTED`,
`BUZZER_CLOSED`, `UNAUTHORIZED`, `INTERNAL_ERROR`. Invalid round IDs return
`QUESTION_CLOSED`; losing/expired buzzer requests return `BUZZER_CLOSED`; a
non-winner's answer returns `UNAUTHORIZED`. JDBC failures use `INTERNAL_ERROR`,
not an unreviewed new database error code. Unexpected details remain server logs.

### Server-pushed events

| Event | Payload |
|---|---|
| `QUESTION_STARTED` | `questionId`, `roundId`, `mode: STANDARD`, `text`, `options: {A,B,C,D}`, `deadlineEpochMs` |
| `BUZZER_OPENED` | Same question fields, `mode: BUZZER`; deadline is the buzzing deadline |
| `BUZZER_WINNER` | `questionId`, `roundId`, `playerId`, `deadlineEpochMs` (new winner-answer deadline) |
| `QUESTION_ENDED` | `questionId`, `roundId`, `correctOptionId`, `reason: TIMEOUT` or `ANSWERED` |
| `LEADERBOARD_UPDATED` | `entries` |
| `QUIZ_FINISHED` | `quizId`, `persisted: true`, `entries` |

Each leaderboard entry has `playerId`, `playerName`, numeric `score`, numeric
`rank`. Internal correct/answered counters are persisted but not broadcast during
an open question. Events omit `requestId`; all include `roomCode`. Score events
follow the question-end event in enqueue order. Question payload sizes are
validated with the shared 16 KiB codec before starting.

### Host payload mapping to agree with M5

- `START_QUIZ`: `quizId`, optional `buzzerQuestionId`.
- `NEXT_QUESTION`: `{}`.
- `OPEN_BUZZER`: `buzzDurationSeconds`, `answerDurationSeconds` (1–600 each).
- `FINISH_QUIZ`: `{}`. Suggested success payload: `persisted: true`.

Host durations are converted to `java.time.Duration` by the coordinator. Do not
send the internal `ResultSnapshot` or JDBC objects directly onto the wire.

## JavaFX integration

M2 adapts its shared TCP connection to `QuizClientPort`, implementing asynchronous
request correlation/timeouts and ordered event subscriptions. No new socket is
opened by these controllers.

```java
FXMLLoader loader = new FXMLLoader(
    getClass().getResource("/fxml/quiz/QuizScreen.fxml"));
Parent view = loader.load();
QuizScreenController controller = loader.getController();
controller.bind(quizClientAdapter, roomCode, playerId);
// Mount view through M2's navigator, on the JavaFX Application Thread.
// Call controller.disconnected() on connection loss, on that same thread.
// Call controller.close() before navigating away/rejoining.
```

Bind after joining the lobby, before the host starts. This version does not invent
a state-sync protocol for joining an already-running round. Network events are
marshalled through `Platform.runLater`. A screen instance belongs to one joined
session; create a fresh one after rejoining. Stale answer completions cannot
disable a later question. An uncertain delivery disables a second choice until
review; the authoritative server events decide the outcome.

## Database compatibility

The DAOs target the blueprint's `quizzes`, `questions`, `quiz_sessions`,
`quiz_results` tables and column names. IDs in MySQL are numeric; use their
canonical decimal string representations in protocol/service calls.

`JdbcQuizDao` loads ordered metadata/questions in one prepared query.
`JdbcResultDao` locks the existing session row, verifies room/quiz association,
inserts result rows, updates session status/timestamps and commits in one
transaction. Any failure rolls back. A retry of an already-finished session
verifies that the saved result values match instead of overwriting them.
Connections/statements/result sets close automatically; the caller supplies the
DataSource and owns connection timeout configuration.

## Tests and demonstration

### Run the Member 3 preview now

From the repository root on Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-member3-preview.ps1
```

Or run `com.quizarena.client.quiz.preview.Member3PreviewLauncher.main()` in
IntelliJ using the project's Java/Maven dependencies. This entry point is separate
from the shared welcome screen; no shared launcher or Maven configuration changed.

The preview opens one window with five player tabs and local host controls:

1. Click **Start quiz**.
2. In one or more player tabs, select an option and click **Submit answer**.
3. Wait for the 15-second deadline. Correct answer and leaderboard appear at review.
4. Click **Next question** to move through all three normal questions.
5. During any review, click **Open buzzer**, then press **BUZZ!** in a player tab
   or click **Race 5 players**. Only the winning tab can answer the bonus question.
6. After the third normal question ends, click **Finish quiz** and compare results
   across the tabs. Click **Reset preview** to try again.

Sample normal answers are A, B, C, and the buzzer answer is D. Each standard answer
earns 100 and the buzzer earns 50. Scores come from the actual quiz engine.

This is a **local developer preview**: sample players and quizzes, in-process JSON
messages, real engine executors/timers, and an in-memory result adapter. It does
not test LAN connectivity, TLS, MySQL durability or M1/M2 integration. The preview
banner explicitly identifies these limitations; saved results disappear on reset
or exit. The actual TCP path is separately exercised by `QuizSocketTest`.

For this sandbox's Maven cache, add
`-MavenRepository 'C:\Users\bchat\.m2\repository'` to the script invocation.
The optional `-SmokeTest` flag loads all five views and exits without opening a
window. The script builds locally and launches Java; keep its terminal open until
you close the preview.

### Automated checks

Run the standard team check:

```powershell
mvn clean test
```

On a desktop, include FXML/controller tests (no windows are opened):

```powershell
mvn '-Dquizarena.uiTests=true' clean test
```

If Maven defaults to an inaccessible repository in this sandbox, the verified
local command supplies the user's existing cache:

```powershell
mvn '-Dmaven.repo.local=C:/Users/bchat/.m2/repository' '-Dquizarena.uiTests=true' -B -ntp clean test
```

Coverage includes duplicate and simultaneous answers, repeated five-player buzzer
races, late/invalid answers, stale/early timers, wall-clock changes, disconnected
and late-joining identities, host command rejection on the player handler,
score confidentiality, transactional rollback/retry and responsive FXML controls.
`QuizSocketTest` sends real JSON through five loopback TCP clients to a test-only
ephemeral gateway. It proves handler/codec behavior over sockets, not M1 NIO,
TLS authorization, discovery or complete production integration.

The optional live MySQL test requires a **disposable test database** already
initialized with M5's blueprint schema. Set `QUIZ_M3_TEST_DB_URL`,
`QUIZ_M3_TEST_DB_USER`, `QUIZ_M3_TEST_DB_PASSWORD` locally, then run:

```powershell
mvn '-Dtest=JdbcMySqlTest' test
```

It creates uniquely named fixture rows, checks quiz loading, rollback, committed
results through new connections and idempotent retry, and removes only its own
rows. It is skipped when the URL is absent. A real server restart and multi-client
JavaFX/TLS flow remain team integration checks once those services are available.

Two-minute viva plan:

1. Show a timed question, accepted answer and duplicate rejection.
2. Run `mvn '-Dtest=QuizSocketTest' test` for five actual TCP buzzer requests.
3. Show the single winner, winner-only answer and 50-point bonus.
4. Explain the room lock, executor, monotonic deadline and atomic winner code.
5. Show committed final results using M5's database once integrated.

Before PR: fetch/merge `origin/develop`, resolve shared-interface changes with the
owner, run `mvn clean test`, push only the M3 feature branch and request review
into `develop`. Do not self-merge or push to shared branches.
