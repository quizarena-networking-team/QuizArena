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
