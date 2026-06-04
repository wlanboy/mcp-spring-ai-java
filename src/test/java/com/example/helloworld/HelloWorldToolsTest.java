package com.example.helloworld;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HelloWorldToolsTest {

    private HelloWorldTools tools;

    @BeforeEach
    void setUp() {
        tools = new HelloWorldTools();
    }

    // ── greet ──────────────────────────────────────────────────────────────

    @Test
    void greet_returnsGreetingWithName() {
        assertThat(tools.greet("World"))
                .isEqualTo("Hello, World! Welcome to the MCP Hello World Server.");
    }

    @Test
    void greet_stripsLeadingAndTrailingWhitespace() {
        assertThat(tools.greet("  Alice  "))
                .isEqualTo("Hello, Alice! Welcome to the MCP Hello World Server.");
    }

    @Test
    void greet_throwsOnNullName() {
        assertThatThrownBy(() -> tools.greet(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name must not be blank");
    }

    @Test
    void greet_throwsOnBlankName() {
        assertThatThrownBy(() -> tools.greet("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name must not be blank");
    }

    @Test
    void greet_throwsWhenNameExceeds100Characters() {
        String longName = "a".repeat(101);
        assertThatThrownBy(() -> tools.greet(longName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name must not exceed 100 characters");
    }

    @Test
    void greet_acceptsNameOfExactly100Characters() {
        String name = "a".repeat(100);
        assertThat(tools.greet(name)).contains(name);
    }

    // ── serverTime ─────────────────────────────────────────────────────────

    @Test
    void serverTime_returnsValidIso8601Instant() {
        assertThat(Instant.parse(tools.serverTime())).isNotNull();
    }

    @Test
    void serverTime_isCloseToNow() {
        Instant before = Instant.now();
        Instant result = Instant.parse(tools.serverTime());
        Instant after = Instant.now();
        assertThat(result).isBetween(before, after);
    }
}
