package dev.incusspawn.vm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IncusRemoteSetupTest {

    @Test
    void isConfiguredReturnsFalseWhenNoConfigExists() {
        assertFalse(IncusRemoteSetup.isConfigured());
    }
}
