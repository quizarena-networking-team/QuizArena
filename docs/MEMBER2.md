# Member 2 — gameplay TCP client and polling

Phase A supplies the reusable gameplay TCP client and loopback tests. It is the
connection that the lobby, live polls, and Member 3 quiz screens will share.
`ServerMain` is currently a placeholder. The loopback peer lives in the tests.

## TCP client API

| Class | Responsibility |
|---|---|
| `TcpQuizClient` | Connect, serialize writes, correlate replies, and deliver pushed events and connection-loss notifications. |
| `JsonFrameReader` | Accumulate bytes up to the shared limit, then validate one UTF-8 JSON object and decode the envelope. |
| `TcpQuizClientTest` | Exercise the transport against an ephemeral loopback socket. |

- `connect(host, port)` returns a `CompletableFuture<Void>`. The host and port
  come from the caller. The shared protocol's default gameplay port is `5000`.
  Duplicate connection attempts fail. Connection failure is reported through the
  future. The default socket connect timeout is five seconds after address
  resolution, which runs on the same background worker as connect.
- `request(ProtocolMessage)` returns a `CompletableFuture<ProtocolMessage>`.
  Use a fresh nonblank `requestId` for every request on a connection, typically
  a UUID. The server can reply out of order. `OK` and `ERROR` are application
  replies and both complete the future with the original envelope. Unmatched
  replies are ignored. The default five-second timeout includes time waiting to
  write. A timeout or cancellation can happen after the server has received the
  request. Treat message payloads as read-only while using the client.
- `subscribe(listener)` receives pushed events. A pushed event has no `status`
  and may carry a `requestId`. `onConnectionLost(listener)` receives the
  `IOException` for unexpected loss after an established session. Close the
  returned subscription to remove its listener. Events are delivered to
  listeners subscribed when the frame is received, with no replay.
- `disconnect()` closes the socket, stops the session workers and fails pending
  work. The client and its subscriptions can be reused on reconnect. `close()`
  releases subscriptions and closes the client permanently. Each connection
  has its own pending requests, timer tasks, socket and workers.

## Framing and threading

The client uses `java.net.Socket` with an `InputStream` and `OutputStream`. A
single writer serializes complete frames using `MessageCodec`. A dedicated
reader uses buffered reads, finds the newline delimiter, and enforces the
16,384-byte limit before decoding. Partial reads and multiple frames in one
read are handled by the same framing logic. Invalid UTF-8, invalid JSON, invalid
reply status or ID, oversized frames, and EOF end the session and fail pending
requests. A local encode error fails the individual request.

One lock protects the current session and its request map. A scheduled executor
expires requests and drops cancelled timers. Up to 128 requests can be pending,
and the writer queue is bounded. Request and connection future completions are
scheduled on the Java common pool. A continuation attached to an already
completed future can run on the attaching thread. Reply completions and event
callbacks run independently. A screen that needs early room events should
subscribe before `JOIN_ROOM` and apply them after membership is confirmed.

One background event worker per session delivers pushed events in wire order.
It logs listener runtime exceptions and continues. Listeners should return
promptly. A slow listener delays other callbacks for that session while socket
reads and request completions continue. If the queue reaches 256 waiting events
and another arrives, the client closes the connection and attempts to report the
loss after the accepted events. Explicit disconnect discards queued events. A
new connection supersedes queued callbacks from the earlier session, and an
already-running callback can finish. JavaFX code should use `Platform.runLater`
for updates and check that the callback still belongs to the displayed session.

## Team integration

- **Member 1** supplies the NIO server, room membership, and request dispatcher.
  Gameplay replies use `OK` or `ERROR` and echo the request ID. The server writer
  must preserve each connection's event order and use the same UTF-8 newline
  framing and byte limit. Poll vote handling will register with the existing
  dispatcher in Phase B and will obtain identity from `RequestContext.playerId()`.
- **Member 5** supplies authenticated TLS host controls. Phase B will expose
  trusted methods for starting and closing in-memory polls. Members 1 and 5 need
  to agree on when a poll may start relative to quiz state and on the remaining
  poll payload fields. Gameplay TCP handles guest votes and public poll updates.
- **Member 3** supplies `QuizClientPort` and the quiz FXML/controllers. Phase D
  will delegate `request` and `subscribe` to the same client used by the lobby
  and polls, bind the quiz controller after room join, and report disconnects
  on the JavaFX thread. The shared envelope is compatible with Member 3's
  proposed payloads. Integration still needs review of fields such as `roundId`,
  leaderboard `rank` and the reserved buzzer question in `docs/MEMBER3.md`.
  `LEADERBOARD_RESULT` arrives through the explicit request future. The quiz
  controller also listens for pushed `LEADERBOARD_UPDATED` events.
- **Member 4** can later pass a discovered host and port to the same `connect`
  method. Discovery and heartbeat delivery belong to the UDP module.

Phase C will add connection, room, lobby, and poll screens. Production tests
will follow once the NIO and host services are available. Current tests cover
real TCP framing and lifecycle on one machine.

## Tests and two-minute viva

```powershell
mvn clean test
mvn "-Dquizarena.uiTests=true" clean test
mvn "-Dtest=TcpQuizClientTest" test
```

1. Run `TcpQuizClientTest` and show the loopback peer binding an ephemeral port.
2. Show the test splitting a UTF-8 character across writes and sending the next
   frame in the same batch. Explain why the reader waits for the newline and
   counts bytes before decoding.
3. Show concurrent `PING` requests, then send their `PONG` replies in reverse
   order. Point to the pending map and writer queue.
4. Show the EOF and reconnect test. Explain how the event worker delivers the
   last accepted room event, how pending futures fail, and how the next socket
   gets a fresh request map.

## Phase B — Live polling backend

`PollService` owns one current poll for the room. A trusted host call creates an
`OPEN` poll with a server-generated UUID and zero counts. Accepted votes update
the totals while the poll is open. Closing moves the poll to `CLOSED` and keeps
its final counts available through `currentPoll()`. Closing the same current
poll again returns the same results. Starting a new poll replaces the previous
one with fresh votes, counts, and a new ID. Room shutdown releases the poll state.

`Poll` stores the player-to-option votes and aggregate counts. A single service
lock covers membership lookup, validation, the duplicate check, vote recording,
and event enqueueing. It also serializes voting against host close and room
shutdown. `PollSnapshot` and event payloads contain immutable copies of the
public data. Public events expose aggregate counts and never include voter IDs
or individual choices.

The event sink receives `POLL_STARTED`, then one `POLL_RESULTS` per accepted
vote, then `POLL_CLOSED` with the final totals. The service orders these enqueue
calls under its lock. A failed enqueue is logged, and an accepted vote remains
recorded. The next successful result or close event carries the full totals.
Direct replies use a separate handoff and may arrive around those events.

### Poll payload proposal for team agreement

The existing envelope, message types, and the shared `SUBMIT_VOTE` and
`POLL_RESULTS` fields apply. Review the remaining payload fields below with the
team before integration. All IDs and labels are strings. `options` is a JSON
object mapping option IDs to labels. `counts` maps the same IDs to integer
totals, including zero. Choose display order by option ID in the UI if needed.

| Channel | Message | Payload |
|---|---|---|
| Gameplay TCP request | `SUBMIT_VOTE` | `pollId`, `optionId` |
| Gameplay TCP success | `SUBMIT_VOTE_RESULT` | `pollId`, `optionId`, `accepted: true` |
| Gameplay push | `POLL_STARTED` | `pollId`, `question`, `options`, `counts` |
| Gameplay push | `POLL_RESULTS` | `pollId`, `counts` |
| Gameplay push | `POLL_CLOSED` | `pollId`, `counts` |
| Authenticated TLS request | `START_POLL` | `question`, `options` |
| Authenticated TLS success | `START_POLL_RESULT` | `pollId` |
| Authenticated TLS request | `CLOSE_POLL` | `pollId` |
| Authenticated TLS success | `CLOSE_POLL_RESULT` | `pollId`, `counts` |

Poll messages carry `roomCode`. Direct replies echo `requestId` and use `OK` or
`ERROR`. Pushes omit `requestId` and `status`. A poll needs a nonblank question
and at least two options with nonblank IDs and labels. The service checks that
the public event payloads fit the shared 16 KiB frame limit before opening it.

| Error code | Polling use |
|---|---|
| `BAD_REQUEST` | Malformed request, unknown option or stale poll ID, invalid definition, or start while a poll is open |
| `ROOM_NOT_FOUND` | Requested room or trusted context room differs from the handler's room |
| `ROOM_CLOSED` | The room's poll service has been shut down |
| `INVALID_PLAYER` | The connection has no player ID or the player is absent from the authoritative room directory |
| `ALREADY_VOTED` | That player ID already voted in the open poll |
| `POLL_CLOSED` | Voting or closing before the first poll, or voting after the identified poll closes |
| `UNAUTHORIZED` | Host command presented on gameplay TCP |
| `INTERNAL_ERROR` | Unexpected server error, with details available in server logs |

Error responses carry an empty payload. Unknown extra payload fields are ignored.
The handler obtains identity from `RequestContext.playerId()` and confirms
membership through `PlayerDirectory`.

### Member 1 — NIO registration and room lifecycle

Create a `PollService` for the active room using the existing room membership and
room broadcast adapters. The dispatcher uses `PollRequestHandler.supportedTypes()`
to route `SUBMIT_VOTE` to this instance. For example, where the dispatcher already
keeps a map of types to handlers:

```java
PollService polls = new PollService(roomCode, playerDirectory, roomEventSink);
PollRequestHandler votes = new PollRequestHandler(polls);
votes.supportedTypes().forEach(type -> handlers.put(type, votes));
```

Use the server's gameplay worker handoff so the selector stays free while
concurrent handlers contend for the short room poll lock. Supply trusted room and
player identity from the TCP session. `PlayerDirectory.find` and
`GameEventSink.broadcast` must return promptly and be thread-safe. The sink
enqueues for the event's `roomCode`, preserves enqueue order, and manages
slow/disconnected recipients through the existing outbound queues. Keep socket
writes and calls back into the poll service outside these adapters.
`RequestContext.reply` enqueues the direct response after the service call.
Call `polls.close()` during room shutdown, before tearing down the room's
broadcast path. Member 1 publishes the room's `ROOM_CLOSED` event.

### Member 5 — Authenticated host calls and quiz coordination

After TLS authentication and room ownership checks, call
`polls.startPoll(question, options)` and use its snapshot's `pollId()` for
`START_POLL_RESULT`. Call `polls.closePoll(pollId)` and use `pollId()` and
`counts()` for `CLOSE_POLL_RESULT`. Map `PollException.code()` to the existing
error envelope and preserve the host request ID. The gameplay handler accepts
only player votes and rejects poll host commands.

Members 1, 3, and 5 should agree when a room allows polling during the quiz
lifecycle. The proposed first-release policy allows polls in the lobby or after
the quiz finishes, while the room is open. Coordinate the poll and quiz start
checks in the same room coordinator. Reserve the room mode while an asynchronous
quiz start is in progress so a poll cannot start between the check and the quiz
transition. `currentPoll()` exposes the state for that coordination. The polling
service relies on the coordinator for cross-feature policy.

### Testing and remaining integration

```powershell
mvn clean test
mvn "-Dtest=PollServiceTest,PollRequestHandlerTest,PollSocketTest" test
```

The fake request context covers trusted identity, rejection and response
correlation. Worker races exercise duplicate votes, distinct players, and
closing during a vote. The TCP test sends concurrent gameplay requests from
Phase A clients through a test-only loopback peer.

State lives in memory. A poll identifies voters by Member 1's player ID, so
rejoining under a new ID is treated as a new voter. Production NIO routing and
TLS host commands depend on the respective teammates. Phase C will add voting
and results screens using the existing TCP client. Showing a current poll to a
late joiner will need a room-level snapshot handoff with Member 1.

## Phase C — JavaFX player client

`ClientLauncher` starts `ClientApp`. The app loads `PlayerShell.fxml`, binds
its `PlayerSessionController` to one `TcpQuizClient`, and closes that session
on application shutdown. The shell keeps a connection-status bar and switches
between the views in `src/main/resources/fxml/player`. `player.css` uses the
same navy and purple palette as the quiz screens.

| View | Behavior |
|---|---|
| `Connection.fxml` | Validates the hostname or IP and TCP port, defaults to port `5000`, and shows progress, errors, and cancel or reconnect actions. |
| `JoinRoom.fxml` | Sends `JOIN_ROOM` with a fresh request ID, the room code, and `payload.playerName`. The lobby opens after an `OK` `JOIN_ROOM_RESULT` with a server-assigned `playerId`. |
| `Lobby.fxml` | Shows the joined room, display name, and the latest player-list snapshot supplied by the server. Sends `LEAVE_ROOM` and waits for its reply. |
| `Poll.fxml` | Opens for `POLL_STARTED`, allows selecting and submitting one option at a time, and displays aggregate counts from `POLL_RESULTS` and `POLL_CLOSED`. The lobby can reopen the active poll or its final results. |

The shell tracks disconnected, connecting, connected, joining, joined, leaving,
and lost states. The same TCP connection carries join, lobby, polls, and leave.
Player actions use sequential request IDs unique to the session for correlation.
A successful join uses the server's `roomCode` when included, or the submitted
code otherwise. An uncertain join or leave closes the connection so a retry
starts with a fresh server session. Confirmed leave and `ROOM_CLOSED` return to
the join screen while the gameplay socket is still available.

### Event delivery and voting

The session subscribes to connection loss before connecting and to room events
before requesting to join. It buffers up to 256 relevant early room events and
applies events for the confirmed room after `JOIN_ROOM_RESULT`. Network events
and reply completions enter JavaFX through `Platform.runLater`. Connection and
room generations reject already queued callbacks from former sessions, and
vote replies additionally match the active poll ID. Leave, disconnect, and
shutdown release the corresponding subscriptions.

Poll views use the Phase B payload proposal above. Options are displayed by
option ID. An accepted vote locks the controls for that poll. The server
supplies the counts, including after a vote acknowledgement. An explicit
`BAD_REQUEST` lets a player select and submit again while the poll is open.
An uncertain acknowledgement leaves voting locked for that poll. Closure
disables voting and preserves the latest valid counts if the closing event
omits or malforms its totals. A newly started poll resets the controls.

`LobbyRoster` isolates the provisional Member 1 snapshot shape. The client
currently reads `payload.players` as an array of objects with `playerName`.
If the list is absent or malformed, the previous valid snapshot stays visible.
The room header displays the local player's joined name independently of that
list. Member 1 still needs to confirm the lobby payload and send the current
poll definition to late joiners. Member 5's TLS host controls still need to be
wired before hosts can drive poll events in production.

Phase D can extend the session's joined-room event routing and mount the
existing quiz FXML in the shell's `playerPages` container, using `joinedRoom()`
for the confirmed room and player ID.
Its `QuizClientPort` adapter can delegate to the same gameplay transport.

### Launch and verification

```powershell
mvn clean javafx:run
mvn clean test
mvn "-Dquizarena.uiTests=true" clean test
```

Use the production launcher with a gameplay server that supports the shared
protocol. The opt-in UI tests load the FXML in isolated JavaFX processes and
cover networking failures, navigation, room and poll events, and stale
callbacks. The loopback UI test sends real `JOIN_ROOM`, `SUBMIT_VOTE`, and
`LEAVE_ROOM` frames through the Phase A client to a test-only peer. It uses the
same socket through the whole flow. The production NIO entry point is still a
placeholder in this checkout.

## Phase D — Quiz screens over the gameplay connection

`PlayerSessionController` loads Member 3's `QuizScreen.fxml` after a successful
`JOIN_ROOM_RESULT`. `JoinedQuizView` binds the existing controller with the
confirmed room code and server-assigned player ID before the shell drains
events buffered during join. The player shell mounts the quiz view in its
center, and the quiz keeps its own scroll pane. Returning to the lobby or
polling keeps the same room membership, TCP connection, and quiz controller.

`QuizTransportAdapter` implements `QuizClientPort`. `request` passes the
original `ProtocolMessage` to the session's `PlayerTransport`, which delegates
to `TcpQuizClient` in production. This preserves request IDs, asynchronous
replies, timeouts, and connection errors. The adapter's subscriber receives
quiz pushes from the shell's existing room subscription on the JavaFX thread.
The subscription belongs to the current quiz view and is removed when it
closes. `QuizEvents` checks the fields the existing controllers consume before
forwarding a push. Member 3's controllers generate UUIDs when sending answers
and buzzer requests. The shell initializes the UUID source on a background
worker and enables quiz input once it is ready.

`QUESTION_STARTED` and `BUZZER_OPENED` bring the quiz into view. The screen
reveals the buzzer card when the host opens a buzzer round and returns to the
question for the player identified by `BUZZER_WINNER`. `QUESTION_ENDED`
reveals the server's correct answer, and `LEADERBOARD_UPDATED` refreshes the
server's scores. `QUIZ_FINISHED` with `persisted: true` shows the saved final
standings and hides completed input cards. Final standings remain available
from the lobby for the current room session. The shell can still receive live
poll updates while the quiz is visible. Players can switch to the poll from
the lobby, and an incoming poll leaves a visible quiz or its final standings
in place.

The integration uses the `docs/MEMBER3.md` payload proposal. Input requests
send `questionId` and `roundId`, plus `optionId` for `SUBMIT_ANSWER`. The
adapter returns `SUBMIT_ANSWER_RESULT`, `PRESS_BUZZER_RESULT`, and any
`LEADERBOARD_RESULT` through their correlated futures. The screen's current
leaderboard and results come from pushes. Leaderboard rows need integer `rank`
and `score` values and a `playerName`. The rank field extends the leaderboard
example in the shared protocol and needs agreement before production rollout.
The server owns round deadlines, answer validity, buzzer winners, points, and
when the correct option and final results can be revealed.

On a TCP loss or `ROOM_CLOSED` event the shell calls `disconnected()` on the
quiz controller before closing the room's view. Confirmed leave and app
shutdown also close the controller and remove its listener. Rejoining binds
a fresh quiz instance to the new player ID. The room generation rejects queued
events from the previous subscription, and the old quiz controller ignores
late replies after it closes.

### Verification and remaining integration

```powershell
mvn clean test
mvn "-Dquizarena.uiTests=true" clean test
mvn clean javafx:run
```

The adapter tests exercise reordered TCP replies, timeout, disconnect, event
validation, and subscription removal. The opt-in JavaFX scenarios cover
early quiz events, questions, buzzer winner identity, leaderboard and final
results, polling on the same loopback socket, leaving, and reconnecting. The
loopback peer and its gameplay data are test fixtures.

Production still needs Member 1's NIO dispatcher to route quiz requests and
send ordered room announcements, and Member 5's authenticated host controls
and result persistence wiring. The initial lifecycle assumes one quiz per
joined room session. Joining or reconnecting during an already running quiz
requires the server to provide current state and participation rules. The
player UI handles subsequent server events and still needs that team handoff.

## Phase E — Final integration handoff

The Member 2 client is ready for team integration. The Java 21 suites cover
the client, poll service, and player flow using local fixtures and real
loopback sockets. `ServerMain` in this checkout is a placeholder. The full
LAN flow, host TLS commands, and live MySQL persistence need the team services.

### Member 1 — Gameplay NIO server

- Listen on a configurable gameplay TCP address and port. The player defaults
  to port `5000`. Encode each frame as one UTF-8 JSON object plus `\n`. The
  frame limit is 16 KiB in bytes before the delimiter. Accumulate partial
  reads and queue partial writes per connection.
- Echo the original `requestId` in direct replies and set `status` to `OK`
  or `ERROR`. Use a shared `errorCode` on failure. Room pushes carry the
  canonical `roomCode` and omit `status`. Preserve the enqueue order of
  pushes to a connection. Reply and event callbacks can reach the UI in
  either order, so the client subscribes before sending `JOIN_ROOM` and holds
  early events until the join is confirmed.
- Bind the joined `playerId` and room to the connection. Route `SUBMIT_VOTE`
  to the room's `PollRequestHandler` on the gameplay worker path. Provide
  `RequestContext.playerId()` from that trusted session and use the existing
  `PlayerDirectory` for membership. Use `GameEventSink` to enqueue room
  broadcasts without socket writes or blocking I/O under the poll lock.
  Host commands are handled on the authenticated TLS path.
- On accepted `LEAVE_ROOM`, remove session membership and reply with
  `LEAVE_ROOM_RESULT`. On room shutdown call `PollService.close()`, close the
  quiz service, and send `ROOM_CLOSED` for that room before removing the
  outbound path. Once a player leaves, stop queuing events for that former
  membership.

| Message | Player-side contract |
|---|---|
| `JOIN_ROOM` / `JOIN_ROOM_RESULT` | The request sends `roomCode` and `payload.playerName`. `OK` includes the assigned `payload.playerId` and should include the canonical `roomCode`. |
| `LOBBY_UPDATED` | The provisional `LobbyRoster` adapter reads `payload.players` as an array of objects with `playerName`. Confirm the authoritative roster shape with Member 1. |
| `SUBMIT_VOTE` / `SUBMIT_VOTE_RESULT` | The request sends `pollId` and `optionId`. The proposed `OK` payload echoes both and sets `accepted: true`. |
| `POLL_STARTED` | The Phase B proposal sends `pollId`, `question`, an `options` object from IDs to labels, and a `counts` object from the same IDs to integer counts. |
| `POLL_RESULTS` / `POLL_CLOSED` | Send `pollId` and the full `counts` snapshot, including zeros. Public events contain aggregate totals. |
| `ROOM_CLOSED` | The push identifies the affected room through the envelope `roomCode`. The player leaves the room view and clears all quiz and poll state. |

Use a fresh request ID for each request on a socket. Closing or reconnecting
interrupts a retired TCP event worker and discards its queued callbacks. A
request future may reach the UI before the corresponding loss notification.
Join, leave, and vote callbacks recheck both the session and transport state
before displaying a result.

### Member 3 — Quiz client integration

`JoinedQuizView` mounts the existing `QuizScreen.fxml` after join. Its
`QuizTransportAdapter` implements `QuizClientPort` on the same gameplay
transport and receives ordered room events through the single room
subscription. The shell hands events to JavaFX with `Platform.runLater`.
Navigating between quiz, lobby, and poll retains the current view and
subscription. Leaving the room removes the listener and closes the quiz
controller and timers. Rejoining binds a new view to the newly assigned ID.
At the minimum player window size, the shell brings the buzzer into view and
fits the final leaderboard columns within the available width.

| Message | Fields needed for the existing game flow |
|---|---|
| `QUESTION_STARTED` / `BUZZER_OPENED` | `questionId`, `roundId`, `text`, options `A`–`D`, and `deadlineEpochMs`. Member 3's proposal also sends `mode`. |
| `BUZZER_WINNER` | `questionId`, `roundId`, winning `playerId`, and the new answer `deadlineEpochMs`. |
| `QUESTION_ENDED` | `questionId`, `roundId`, `correctOptionId`, and the proposed end `reason`. Publish the correction after the round. |
| `SUBMIT_ANSWER` / `PRESS_BUZZER` | Keep `questionId` and `roundId` in the request. Answers also send `optionId`. Successful replies echo both IDs and include `accepted: true` or `winner: true`, respectively. |
| `LEADERBOARD_UPDATED` / `LEADERBOARD_RESULT` | `entries` with `playerId`, `playerName`, numeric integer `score`, and Member 3's proposed integer `rank`. Only pushes update the current screen. |
| `QUIZ_FINISHED` | `quizId`, `persisted: true`, and final `entries` after persistence succeeds. The final standings remain accessible while the room session remains open. |

The shared protocol describes the envelope and message names. The team still
needs to confirm the full poll acknowledgement and event shapes, the lobby
roster shape, and Member 3's `roundId`, `rank`, and final-persistence fields.
The shared leaderboard example currently omits `rank`. The player uses the
Member 3 proposal, including that field. `deadlineEpochMs` is Unix milliseconds
on the server. A UI countdown is indicative, and the server decides validity.

The quiz adapter checks a reply's type and room against the request. For
successful answer and buzzer replies it also verifies the confirmation flag
and question and round IDs. A mismatched response is an unconfirmed action in
the quiz UI. Explicit server rejection codes still reach the existing controller.

### Member 5 — TLS, room mode, and persistence

Authenticate the host via TLS on the configured host-control listener
(default `8443`), then check permission for the requested room. Call
`PollService.startPoll(question, options)` and `closePoll(pollId)` as trusted
server operations. Return `START_POLL_RESULT` or `CLOSE_POLL_RESULT` with the
original host request ID. Map expected `PollException.code()` values to
shared errors. The guest TCP client has no host credentials or ownership
context, so authorizing host commands belongs to the TLS service.

Serialize poll and quiz start checks through the same room coordinator, with
a reservation while quiz start is asynchronous. The proposed first-release
policy allows polling in the lobby or after quiz completion. `currentPoll()`
exposes open and closed poll state. Polls are in memory. MySQL is required for
production quiz content and final quiz results, including the data source and
schema used by Member 3's JDBC adapters. Emit saved final results only after
the server confirms persistence.

### Run, verify, and troubleshoot

```powershell
mvn clean javafx:run
mvn clean test
mvn "-Dquizarena.uiTests=true" clean test
```

Use JDK 21 and Maven 3.9+. For the complete flow, enter the listening game
server's hostname and actual TCP port and join a room created by the host.
Run the JavaFX suite in a desktop session or under the CI virtual display.
The optional MySQL test requires the test database supplied by Member 5.

| Final Java 21 command | Result |
|---|---|
| `mvn clean test` | 85 passed, 0 failed, 14 skipped |
| `mvn "-Dquizarena.uiTests=true" clean test` | 98 passed, 0 failed, 1 skipped |
| `mvn clean javafx:run` | The desktop window opened and Maven exited cleanly after it was closed. |

The standard run skipped 13 opt-in UI tests. Both runs skipped the optional
live MySQL test. The UI-enabled run includes the new vote-loss race and compact
quiz regressions. Rendered checks at 1000×690 and 704×501 confirmed the quiz
views keep the buzzer and final results readable at the smaller size.

| Symptom | Check |
|---|---|
| Connection refused or timed out | Verify the NIO listener is running at the selected address and port. This checkout provides a placeholder entry point. |
| Joined with no roster | Check the `LOBBY_UPDATED` room code and the provisional `players` array. |
| Poll options or totals are missing | Check the `POLL_STARTED` definition, then match the `pollId` and option IDs across result messages. Late join needs the current poll definition from the server. |
| Quiz view or leaderboard is missing | Inspect the room code, required round fields, and leaderboard `rank` values. Join before quiz start until the team implements current-round recovery. |
| Vote or answer delivery is unconfirmed | Wait for server events. A request timeout or disconnect can occur after the server processed the action. |

### Two-minute viva

Pre-run the focused loopback tests so Maven dependencies are ready. When the
team server is available, use the player window to show the same flow live.

```powershell
mvn "-Dtest=TcpQuizClientTest,PollSocketTest" test
mvn "-Dquizarena.uiTests=true" "-Dtest=PlayerScreenTest#quizAndPollingShareOneGameplaySocket" test
```

1. **0:00–0:25** Show the connection screen and explain the Java `Socket`,
   host, port, and the reuse of one connection for the joined session.
2. **0:25–0:55** Show `JsonFrameReader` and the framing tests. Explain how the
   `InputStream` assembles split UTF-8 frames, why the byte limit is enforced
   before parsing, and how the writer sends a complete frame to the
   `OutputStream`.
3. **0:55–1:25** Show the reversed-reply and disconnect tests. Explain how a
   request ID completes the right future, how pushes preserve order, and how
   timeouts and reconnects retire old work while JavaFX stays responsive.
4. **1:25–2:00** Show the concurrent poll test and the single-socket player
   flow. Explain the atomic one-vote-per-player check, server-owned totals,
   and how lobby, quiz, buzzer, and results are delivered to JavaFX.

### Readiness checklist

- [x] Verify Java 21 TCP framing, bounded queues, request correlation, event
  delivery, timeouts, disconnect, and reconnect with loopback peers.
- [x] Verify poll state, identity checks, concurrent duplicate detection, and
  final aggregate counts in local service and loopback tests.
- [x] Verify the JavaFX launcher and the player flow with test fixtures,
  including quiz, poll, leave, and reconnect.
- [ ] Validate the production NIO listener, session registry, roster and
  broadcast contracts, and late-join/reconnect snapshots with Member 1.
- [ ] Confirm the poll and quiz payload proposals and phase coordination with
  Members 1, 3, and 5.
- [ ] Verify host-authorized TLS operations and live MySQL quiz persistence
  with Member 5 using the team's server setup.
