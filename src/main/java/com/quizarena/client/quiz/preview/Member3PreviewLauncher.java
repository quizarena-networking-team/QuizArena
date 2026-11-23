package com.quizarena.client.quiz.preview;

import javafx.application.Application;

/** Wrapper permits running JavaFX from the dependency classpath without changing the shared launcher. */
public final class Member3PreviewLauncher {
    private Member3PreviewLauncher() {}
    public static void main(String[] args) { Application.launch(Member3PreviewApp.class, args); }
}
