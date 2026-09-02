package com.javaclaw.desktop.golden;

enum GoldenViewport {
    MINIMUM("minimum", 880, 620),
    STANDARD("standard", 1_040, 720);

    private final String id;
    private final int width;
    private final int height;

    GoldenViewport(String id, int width, int height) {
        this.id = id;
        this.width = width;
        this.height = height;
    }

    String id() {
        return id;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }
}
