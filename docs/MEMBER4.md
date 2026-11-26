# Member 4 - UDP discovery, presence, multicast and network inspector

Concept: UDP datagram networking (`DatagramSocket`, `DatagramPacket`), broadcast and
multicast (`MulticastSocket`, `NetworkInterface`). Blueprint features F06, F07 and the
inspector part of F08.

UDP is advisory in QuizArena. It never awards points, never carries answers or scores, and
never removes a player. The TCP session (Member 1) is the source of truth for membership.

## Status

| Part | Status |
|---|---|
| Shared UDP codec and configuration | Done, tested |
| Player LAN discovery (`DiscoveryClient` + Connection screen) | Done, tested |
| Player heartbeat (`HeartbeatSender`) | Done, tested |
| Player multicast listener and announcement banner | Done, tested |
| Server discovery responder, heartbeat monitor, multicast announcer | Pending: needs `origin/develop` (Member 1's `NetworkEventBus`, `RoomRegistry`) |
| `ServerMain` wiring and `--inspector` window | Pending: same dependency |

## Classes

| Package | Class | Role |
|---|---|---|
| `udp` | `UdpConfig` | Ports, group, interface, heartbeat timing; `defaults()` / `fromEnv()` |
| `udp` | `UdpMessage` | Flat UDP JSON body for `DISCOVER`, `DISCOVER_RESULT`, `HEARTBEAT`, `ANNOUNCEMENT` |
| `udp` | `DatagramCodec` | UTF-8 JSON, one message per datagram, max 1200 bytes; drops malformed input |
| `udp.discovery` | `DiscoveryClient` | Sends `DISCOVER` to 255.255.255.255, each interface's broadcast address and localhost; collects unicast replies |
| `udp.presence` | `HeartbeatSender` | Sends `HEARTBEAT` every 2 s on a daemon timer; failures are logged and ignored |
| `udp.multicast` | `MulticastListener` | `joinGroup(group, nif)` on every candidate interface, receive loop, room filter, `leaveGroup` on close |
| `udp.multicast` | `MulticastInterfaces` | `forSending`: configured name, else the default-route (LAN) adapter, else first IPv4 multicast adapter. `forReceiving`: configured name, else every active IPv4 multicast adapter. Membership is per interface, so listeners join all of them and receive whichever adapter the server uses |
| `client.player` | `LanPresence` | Starts heartbeat + multicast listener after `JOIN_ROOM` succeeds; stops them when the room is cleared |

### Changes to Member 2's player client
- `Connection.fxml` / `ConnectionController`: a "Find servers on LAN" card. Discovery runs on a
  background thread; selecting a result fills the host and port, and the normal Connect button
  is used. If nothing answers, the player enters the IP manually.
- `PlayerSessionController` / `PlayerShell.fxml`: remembers the connected host, calls
  `LanPresence.start(...)` after a confirmed join and `stop()` in `clearRoom()`, and shows the
  latest announcement in a header banner. `LanPresence` is disabled unless the session is bound
  to a real `TcpQuizClient`, so the existing UI tests send no UDP traffic.
- `player.css`: one `.announcement` style.

## Payloads (docs/PROTOCOL.md section 9)

```
{"type":"DISCOVER","version":1}
{"type":"DISCOVER_RESULT","version":1,"serverName":"QuizArena Server","tcpPort":5000,"tlsPort":8443}
{"type":"HEARTBEAT","version":1,"roomCode":"ABC123","playerId":"p-101"}
{"type":"ANNOUNCEMENT","version":1,"roomCode":"ABC123","message":"Round starts now"}
```

The discovery reply goes to the sender's address and port; the client uses the reply's source
address as the server host. Datagrams that are over 1200 bytes, not JSON, missing `type`, or not
`version` 1 are dropped. Unknown fields are ignored.

## Configuration

| Variable | Default |
|---|---|
| `QUIZ_DISCOVERY_PORT` | 5001 |
| `QUIZ_HEARTBEAT_PORT` | 5002 |
| `QUIZ_MULTICAST_GROUP` | 239.10.10.10 |
| `QUIZ_MULTICAST_PORT` | 5003 |
| `QUIZ_MULTICAST_INTERFACE` | auto (set e.g. `wlan0` / `eth3` if the wrong adapter is chosen) |
| `QUIZ_TLS_PORT` | 8443 (advertised only) |

Multicast uses TTL 1, so announcements stay on the local network.

## Remaining work (after `origin/develop` is merged)

1. Append UDP kinds to `metrics/NetworkEvent.Kind` (`UDP_RECEIVED`, `UDP_SENT`, `UDP_REJECTED`,
   `PRESENCE_WARNING`). Purely additive; tell Member 1.
2. `udp.discovery.DiscoveryResponder`: answer `DISCOVER` on 5001 by unicast.
3. `udp.presence.HeartbeatMonitor`: accept heartbeats only for players that `RoomRegistry.find`
   confirms; warn once after 6 s of silence and once on recovery; never call `leave`.
4. `udp.multicast.MulticastAnnouncer` and `AnnouncingEventSink` (wraps a room's
   `GameEventSink`; announces poll/question/buzzer/finish/room-closed text only).
5. `udp.LanServices`, started from `ServerMain.main()` after the TCP server, closed on shutdown;
   announce the dev room; parse `--inspector`.
6. Server-side JavaFX network inspector reading `NetworkEventBus` (`recent()`, `subscribe()`,
   counters). This runs on the host computer inside the server JVM. The blueprint's host client
   could later fetch the same data over TLS (`GET_NETWORK_EVENTS`) once Member 5 is ready; that
   protocol extension needs team review first.

Team wiring: when Members 1-3 create each room's Poll/Quiz services, wrap
`rooms.eventSink(room)` with `AnnouncingEventSink` so game phases are announced.

## Tests

```powershell
mvn clean test
mvn "-Dquizarena.uiTests=true" clean test
mvn "-Dtest=com.quizarena.udp.**.*Test" test
```

| Test | Covers |
|---|---|
| `DatagramCodecTest` | Protocol examples, round trip, malformed/oversized/wrong version, env config |
| `DiscoveryClientTest` | Loopback responder reply, junk and duplicate replies, no server (manual fallback) |
| `HeartbeatSenderTest` | Periodic heartbeats, silence after close, identity validation |
| `MulticastListenerTest` | Two listeners on one shared port join before sending; room filter; leave on close. Skipped where no multicast interface exists |

All socket tests bind ephemeral ports, so they do not clash with a running server.

## Troubleshooting

| Symptom | Check |
|---|---|
| "No servers found" | Broadcast may be blocked (university Wi-Fi). Allow UDP 5001 in Windows Firewall on the server, or enter the IP manually |
| No announcement banner | Allow UDP 5003; set `QUIZ_MULTICAST_INTERFACE` to the adapter on the shared network; multicast may be blocked on the network |
| Two entries for one server | Same-machine server answers on both loopback and LAN addresses; either works |

## Two-minute viva

1. **0:00-0:25** Click "Find servers on LAN"; show the `DISCOVER` broadcast and unicast reply.
2. **0:25-1:05** Show an announcement arriving on two player windows through multicast.
3. **1:05-1:40** Show `DatagramSocket.send/receive`, the broadcast addresses, and
   `MulticastSocket.joinGroup(group, nif)` with TTL 1.
4. **1:40-2:00** Stop a heartbeat: the server warns but the player stays in the lobby. Explain
   UDP unreliability and why TCP stays authoritative.
