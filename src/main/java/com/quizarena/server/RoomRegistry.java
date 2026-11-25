package com.quizarena.server;

import com.quizarena.protocol.MessageCodec;
import com.quizarena.protocol.ProtocolMessage;
import com.quizarena.quiz.GameEventSink;
import com.quizarena.quiz.PlayerDirectory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static com.quizarena.protocol.MessageTypes.LOBBY_UPDATED;
import static com.quizarena.protocol.MessageTypes.ROOM_CLOSED;

/**
 * Authoritative in-memory registry of live rooms and their connected players.
 *
 * <ul>
 *   <li>Members 2 and 3 read it through {@link PlayerDirectory} and publish room events through
 *       {@link #eventSink(String)} ({@link GameEventSink}). Both are non-blocking.</li>
 *   <li>Member 5's authenticated host coordinator calls {@link #createRoom()} and
 *       {@link #closeRoom(String)}. Those are trusted internal calls; nothing on the plaintext
 *       gameplay port can reach them.</li>
 *   <li>Only connected players are members. A disconnect removes the player, which is exactly
 *       what stops a lost connection from voting or answering.</li>
 * </ul>
 *
 * Locking: one registry lock guards all room state. Inside it we only mutate maps and enqueue
 * already-encoded frames on session queues (non-blocking). We never call into poll/quiz services
 * or touch sockets, so there is no lock cycle with those services.
 */
public final class RoomRegistry implements PlayerDirectory {

    public static final int MAX_NAME_LENGTH = 24;
    static final int CODE_LENGTH = 6;
    // No 0/O/1/I/L so a code read aloud or typed from a screen is hard to get wrong.
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z0-9]{4,12}");
    private static final int MAX_REMEMBERED_CLOSED_ROOMS = 64;

    private static final System.Logger LOG = System.getLogger(RoomRegistry.class.getName());

    /** Result of a successful join, handed to the {@code admitted} callback. */
    public record Joined(String roomCode, Player player) {}

    private record Member(Player player, ClientSession session) {}

    private static final class Room {
        final String code;
        // Insertion order = join order, which is the order shown in the lobby.
        final Map<String, Member> members = new LinkedHashMap<>();

        Room(String code) {
            this.code = code;
        }
    }

    private final Object lock = new Object();
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Set<String> closedCodes = new LinkedHashSet<>();
    private final SecureRandom random = new SecureRandom();
    private final int maxPlayersPerRoom;
    private final int maxOpenRooms;

    /** MVP default: one open room, 32 players. */
    public RoomRegistry() {
        this(32, 1);
    }

    public RoomRegistry(int maxPlayersPerRoom, int maxOpenRooms) {
        if (maxPlayersPerRoom <= 0 || maxOpenRooms <= 0) {
            throw new IllegalArgumentException("Room limits must be positive");
        }
        this.maxPlayersPerRoom = maxPlayersPerRoom;
        this.maxOpenRooms = maxOpenRooms;
    }

    // ---------------------------------------------------------------- room lifecycle (trusted callers)

    /** Creates a room with a random, unpredictable code. Intended for Member 5's HostCoordinator. */
    public String createRoom() {
        synchronized (lock) {
            ensureCapacity();
            String code;
            do {
                code = randomCode();
            } while (rooms.containsKey(code) || closedCodes.contains(code));
            rooms.put(code, new Room(code));
            return code;
        }
    }

    /** Creates a room with a chosen code. Used for manual testing ({@code QUIZ_DEV_ROOM}) and tests. */
    public String createRoom(String requestedCode) {
        String code = normalizeCode(requestedCode);
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new RoomException("BAD_REQUEST", "Room code must be 4-12 letters or digits");
        }
        synchronized (lock) {
            ensureCapacity();
            if (rooms.containsKey(code) || closedCodes.contains(code)) {
                throw new RoomException("BAD_REQUEST", "Room code is already in use");
            }
            rooms.put(code, new Room(code));
            return code;
        }
    }

    /**
     * Closes the room: every member receives ROOM_CLOSED and is detached from it (their TCP
     * connection stays open so they can join another room). Returns false if there was no such open room.
     */
    public boolean closeRoom(String roomCode) {
        String code = normalizeCode(roomCode);
        synchronized (lock) {
            Room room = code == null ? null : rooms.remove(code);
            if (room == null) {
                return false;
            }
            rememberClosed(code);
            byte[] frame = encode(new ProtocolMessage(ROOM_CLOSED, null, code, null, null, Map.of()));
            for (Member member : new ArrayList<>(room.members.values())) {
                if (frame != null) {
                    member.session().sendFrame(frame);
                }
                member.session().clearRoom();
            }
            room.members.clear();
            return true;
        }
    }

    public boolean isOpen(String roomCode) {
        String code = normalizeCode(roomCode);
        synchronized (lock) {
            return code != null && rooms.containsKey(code);
        }
    }

    // ---------------------------------------------------------------- membership (gameplay port)

    /**
     * Admits a guest to the room, binds the new identity to {@code session}, then invokes
     * {@code admitted} (used to send JOIN_ROOM_RESULT) and finally broadcasts LOBBY_UPDATED.
     * Everything happens under the registry lock, so concurrent joins produce lobby updates
     * in the same order as the membership changes. The callback must not block.
     */
    public Joined join(String roomCode, String requestedName, ClientSession session, Consumer<Joined> admitted) {
        String name = sanitizeName(requestedName);
        String code = normalizeCode(roomCode);
        if (code == null) {
            throw new RoomException("BAD_REQUEST", "roomCode is required");
        }
        synchronized (lock) {
            Room room = rooms.get(code);
            if (room == null) {
                throw closedCodes.contains(code)
                        ? new RoomException("ROOM_CLOSED", "The room has been closed")
                        : new RoomException("ROOM_NOT_FOUND", "No room with that code");
            }
            if (session.isClosed()) {
                throw new RoomException("INVALID_PLAYER", "Connection is closed");
            }
            if (session.roomCode() != null) {
                throw new RoomException("BAD_REQUEST", "This connection already joined a room; leave first");
            }
            if (room.members.size() >= maxPlayersPerRoom) {
                throw new RoomException("ROOM_CLOSED", "The room is full");
            }

            // Fresh unpredictable UUID per join. Also fits the CHAR(36) player_key column.
            Player player = new Player(UUID.randomUUID().toString(), name);
            room.members.put(player.id(), new Member(player, session));
            session.bindToRoom(code, player.id(), player.name());

            Joined joined = new Joined(code, player);
            admitted.accept(joined);
            broadcastLobby(room);
            return joined;
        }
    }

    /**
     * Removes the session's player from its room (LEAVE_ROOM or disconnect) and tells the others.
     * Safe to call for a session that never joined. Returns the removed player, if any.
     */
    public Optional<Player> leave(ClientSession session) {
        synchronized (lock) {
            String code = session.roomCode();
            String playerId = session.playerId();
            session.clearRoom();
            Room room = code == null ? null : rooms.get(code);
            if (room == null || playerId == null) {
                return Optional.empty();
            }
            Member removed = room.members.remove(playerId);
            if (removed == null) {
                return Optional.empty();
            }
            broadcastLobby(room);
            return Optional.of(removed.player());
        }
    }

    // ---------------------------------------------------------------- delivery (non-blocking)

    /** Queues {@code event} for every current member of the room, in call order. */
    public void broadcast(String roomCode, ProtocolMessage event) {
        byte[] frame = encode(event);
        if (frame == null) {
            return;
        }
        String code = normalizeCode(roomCode);
        synchronized (lock) {
            Room room = code == null ? null : rooms.get(code);
            if (room == null) {
                return;
            }
            for (Member member : new ArrayList<>(room.members.values())) {
                member.session().sendFrame(frame);
            }
        }
    }

    /** Queues {@code event} for one player of the room. Returns false if that player is not connected. */
    public boolean sendToPlayer(String roomCode, String playerId, ProtocolMessage event) {
        byte[] frame = encode(event);
        String code = normalizeCode(roomCode);
        if (frame == null || code == null || playerId == null) {
            return false;
        }
        synchronized (lock) {
            Room room = rooms.get(code);
            Member member = room == null ? null : room.members.get(playerId);
            return member != null && member.session().sendFrame(frame);
        }
    }

    /**
     * Adapter for {@code PollService}, {@code QuizService} and friends: broadcast to one room.
     * Enqueues only; socket writes happen later on the selector thread.
     */
    public GameEventSink eventSink(String roomCode) {
        return event -> broadcast(roomCode, event);
    }

    // ---------------------------------------------------------------- PlayerDirectory (M2 / M3)

    @Override
    public Optional<Player> find(String roomCode, String playerId) {
        String code = normalizeCode(roomCode);
        if (code == null || playerId == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            Room room = rooms.get(code);
            Member member = room == null ? null : room.members.get(playerId);
            return member == null ? Optional.empty() : Optional.of(member.player());
        }
    }

    @Override
    public List<Player> players(String roomCode) {
        String code = normalizeCode(roomCode);
        synchronized (lock) {
            Room room = code == null ? null : rooms.get(code);
            if (room == null) {
                return List.of();
            }
            List<Player> players = new ArrayList<>(room.members.size());
            for (Member member : room.members.values()) {
                players.add(member.player());
            }
            return List.copyOf(players);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Upper-cases and trims a room code. Null for null/blank input. */
    static String normalizeCode(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.strip();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    /**
     * Cleans a guest-supplied display name: drops control and invisible format characters,
     * collapses whitespace runs and trims. Rejects empty or over-long results instead of silently
     * truncating, so the player sees an error and picks another name.
     */
    static String sanitizeName(String raw) {
        if (raw == null) {
            throw new RoomException("BAD_REQUEST", "playerName is required");
        }
        StringBuilder cleaned = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = cleaned.length() > 0;
                continue;
            }
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT
                    || Character.getType(cp) == Character.UNASSIGNED
                    || Character.getType(cp) == Character.SURROGATE) {
                continue;
            }
            if (pendingSpace) {
                cleaned.append(' ');
                pendingSpace = false;
            }
            cleaned.appendCodePoint(cp);
        }
        String name = cleaned.toString();
        if (name.isEmpty()) {
            throw new RoomException("BAD_REQUEST", "playerName cannot be empty");
        }
        if (name.codePointCount(0, name.length()) > MAX_NAME_LENGTH) {
            throw new RoomException("BAD_REQUEST", "playerName is limited to " + MAX_NAME_LENGTH + " characters");
        }
        return name;
    }

    private void ensureCapacity() {
        if (rooms.size() >= maxOpenRooms) {
            throw new RoomException("BAD_REQUEST", "The maximum number of open rooms has been reached");
        }
    }

    private void rememberClosed(String code) {
        closedCodes.add(code);
        if (closedCodes.size() > MAX_REMEMBERED_CLOSED_ROOMS) {
            closedCodes.remove(closedCodes.iterator().next());
        }
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /** Must be called with the registry lock held. */
    private void broadcastLobby(Room room) {
        List<Map<String, Object>> roster = new ArrayList<>(room.members.size());
        for (Member member : room.members.values()) {
            roster.add(Map.of("playerId", member.player().id(), "playerName", member.player().name()));
        }
        byte[] frame = encode(new ProtocolMessage(LOBBY_UPDATED, null, room.code, null, null,
                Map.of("players", roster)));
        if (frame == null) {
            return;
        }
        for (Member member : new ArrayList<>(room.members.values())) {
            member.session().sendFrame(frame);
        }
    }

    private static byte[] encode(ProtocolMessage message) {
        try {
            return MessageCodec.encodeLine(message);
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not encode " + message.type(), failure);
            return null;
        }
    }
}
