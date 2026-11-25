# Member 1 - Java NIO server, rooms and lobby

Concept: non-blocking multiplexing (`ServerSocketChannel`, `Selector`, `SocketChannel`, `ByteBuffer`).

| Class | Role |
|---|---|
| `NioQuizServer` | Selector loop on TCP 5000: accept, read, write (OP_WRITE only when needed), idle sweep, cleanup |
| `FrameDecoder` | Per-client ByteBuffer; flip -> scan for `\n` -> compact; 16 KiB limit; handles partial/multiple frames |
| `ClientSession` | Per-connection state, bounded outbound queue, in-order request execution |
| `MessageDispatcher` | Routes requests to handlers; host commands on 5000 -> `UNAUTHORIZED` |
| `RoomRegistry` | Rooms/players; implements M3's `PlayerDirectory`; `eventSink(room)` = M2/M3 `GameEventSink` |
| `RoomRequestHandler` | `PING`, `JOIN_ROOM`, `LEAVE_ROOM` |
| `metrics/NetworkEventBus` | Real ACCEPT/READ/WRITE/DISCONNECT events for M4's inspector |

## Payloads
- `JOIN_ROOM` request: envelope `roomCode`, payload `playerName` (max 24 chars, cleaned).
- `JOIN_ROOM_RESULT` OK: payload `playerId` (UUID), `playerName`. Errors: `BAD_REQUEST`, `ROOM_NOT_FOUND`, `ROOM_CLOSED` (also when full).
- `LOBBY_UPDATED` push: payload `players: [{playerId, playerName}]` (object list, as the existing client `LobbyRoster` expects; the PDF example shows plain strings).
- `LEAVE_ROOM_RESULT` OK, or `INVALID_PLAYER` if not in a room. `ROOM_CLOSED` push when the host closes the room.

## Plugging in (ServerMain.assemble)
- M2: `dispatcher.register(pollHandler.supportedTypes(), pollHandler)`; `new PollService(room, rooms, rooms.eventSink(room))`.
- M3: same with `QuizRequestHandler`.
- M5: call `rooms.createRoom()` / `rooms.closeRoom(code)` after TLS authorization.
- M4: `services.events().subscribe(...)`.

## Config (env or args)
`QUIZ_BIND_HOST`, `QUIZ_TCP_PORT`, `QUIZ_MAX_CONNECTIONS`, `QUIZ_MAX_PLAYERS`, `QUIZ_IDLE_TIMEOUT_SECONDS`, `QUIZ_DEV_ROOM`, `QUIZ_LOG_IO`.

## Integration Handoff - Pending Team Integration

The Java NIO gameplay server provides the shared TCP infrastructure,
client session management, message dispatching, and room management.

The following handler integrations must be coordinated during
the team integration phase:

- **Member 2 - Polling:** Register the polling request handler
  using `dispatcher.register(pollHandler.supportedTypes(), pollHandler)`
  in `ServerMain.assemble`. Connect the PollService to the shared
  room event sink.

- **Member 3 - Multiplayer Quiz:** Register the
  `QuizRequestHandler` using its supported message types in
  `ServerMain.assemble`. Ensure that quiz operations use the
  shared room and player management interfaces.

- **Cross-module coordination:** Members 1, 2, and 3 must verify
  handler registration, message types, request/response formats,
  and shared event delivery.

- **Integration testing:** Verify that polling and quiz requests
  are correctly dispatched through the NIO server and that
  responses and room events reach the connected clients.

These activities are part of the team integration phase and
must be verified before the complete networking system is
considered integrated.
