package com.quizarena.quiz;

import java.util.concurrent.atomic.AtomicReference;

/** Reset and tryWin are called under the room lock; CAS explicitly demonstrates one winner. */
public final class BuzzerService {
    private final AtomicReference<String> winner = new AtomicReference<>();

    public void reset() { winner.set(null); }
    public boolean tryWin(String playerId) { return winner.compareAndSet(null, playerId); }
    public String winner() { return winner.get(); }
}
