package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.function.Consumer;

/** Incremental decoder for one top-level JSON string, never a raw JSON renderer. */
final class UserMessageJsonDecoder {
    private enum State { ROOT, KEY_OR_END, KEY, COLON, VALUE, STRING, SKIP, AFTER_VALUE, DONE }
    private final ObjectMapper json;
    private final Consumer<String> text;
    private final StringBuilder key = new StringBuilder();
    private final StringBuilder skipped = new StringBuilder();
    private State state = State.ROOT;
    private boolean target;
    private boolean seen;
    private boolean escaped;
    private int unicodeDigits;
    private int unicodeValue;
    private char highSurrogate;
    private int textLength;
    private int depth;
    private boolean skipString;
    private boolean skipEscaped;

    UserMessageJsonDecoder(ObjectMapper json, Consumer<String> text) {
        this.json = json;
        this.text = text;
    }

    void accept(String delta) {
        StringBuilder decoded = new StringBuilder();
        for (int index = 0; index < delta.length(); index++) {
            char value = delta.charAt(index);
            switch (state) {
                case ROOT -> {
                    if (space(value)) continue;
                    require(value == '{');
                    state = State.KEY_OR_END;
                }
                case KEY_OR_END -> {
                    if (space(value)) continue;
                    if (value == '}') state = State.DONE;
                    else {
                        require(value == '"');
                        key.setLength(0);
                        state = State.KEY;
                    }
                }
                case KEY, STRING -> {
                    if (unicodeDigits > 0) {
                        int digit = Character.digit(value, 16);
                        require(digit >= 0);
                        unicodeValue = (unicodeValue << 4) | digit;
                        if (--unicodeDigits == 0) append((char) unicodeValue, decoded);
                    } else if (escaped) {
                        escaped = false;
                        switch (value) {
                            case '"', '\\', '/' -> append(value, decoded);
                            case 'b' -> append('\b', decoded);
                            case 'f' -> append('\f', decoded);
                            case 'n' -> append('\n', decoded);
                            case 'r' -> append('\r', decoded);
                            case 't' -> append('\t', decoded);
                            case 'u' -> { unicodeDigits = 4; unicodeValue = 0; }
                            default -> throw invalid();
                        }
                    } else if (value == '\\') escaped = true;
                    else if (value == '"') {
                        require(highSurrogate == 0);
                        if (state == State.KEY) {
                            target = "userMessage".contentEquals(key);
                            state = State.COLON;
                        } else state = State.AFTER_VALUE;
                    } else {
                        require(value >= 0x20);
                        append(value, decoded);
                    }
                }
                case COLON -> {
                    if (space(value)) continue;
                    require(value == ':');
                    state = State.VALUE;
                }
                case VALUE -> {
                    if (space(value)) continue;
                    if (target) {
                        require(!seen && value == '"');
                        seen = true;
                        state = State.STRING;
                    } else {
                        skipped.setLength(0);
                        depth = 0;
                        skipString = false;
                        skipEscaped = false;
                        state = State.SKIP;
                        index--;
                    }
                }
                case SKIP -> {
                    if (!skipString && depth == 0 && (value == ',' || value == '}')) {
                        validateSkipped();
                        state = State.AFTER_VALUE;
                        index--;
                    } else {
                        require(skipped.length() < 256 * 1024);
                        skipped.append(value);
                        if (skipString) {
                            if (skipEscaped) skipEscaped = false;
                            else if (value == '\\') skipEscaped = true;
                            else if (value == '"') skipString = false;
                        } else if (value == '"') skipString = true;
                        else if (value == '{' || value == '[') depth++;
                        else if (value == '}' || value == ']') { require(depth > 0); depth--; }
                    }
                }
                case AFTER_VALUE -> {
                    if (space(value)) continue;
                    if (value == ',') state = State.KEY_OR_END;
                    else { require(value == '}'); state = State.DONE; }
                }
                case DONE -> require(space(value));
            }
        }
        if (!decoded.isEmpty()) text.accept(decoded.toString());
    }

    private void append(char value, StringBuilder decoded) {
        if (state == State.KEY) {
            require(key.length() < 128);
            key.append(value);
            return;
        }
        if (highSurrogate != 0) {
            require(Character.isLowSurrogate(value));
            require(textLength + 2 <= 12_000);
            decoded.append(highSurrogate).append(value);
            textLength += 2;
            highSurrogate = 0;
        } else if (Character.isHighSurrogate(value)) highSurrogate = value;
        else {
            require(!Character.isLowSurrogate(value) && textLength < 12_000);
            decoded.append(value);
            textLength++;
        }
    }

    private void validateSkipped() {
        try {
            require(json.readTree(skipped.toString()) != null);
        } catch (IOException failure) {
            throw invalid();
        }
    }

    private static boolean space(char value) {
        return value == ' ' || value == '\n' || value == '\r' || value == '\t';
    }

    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid provisional reply JSON");
    }
}
