package com.opentermx.mcp.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlobMatcherTest {

    @Test
    fun `glob null permite todo`() {
        assertTrue(GlobMatcher.matches(null, "lab-foo"))
        assertTrue(GlobMatcher.matches("", "lab-foo"))
    }

    @Test
    fun `wildcard simple`() {
        assertTrue(GlobMatcher.matches("lab-*", "lab-foo"))
        assertTrue(GlobMatcher.matches("lab-*", "lab-"))
        assertFalse(GlobMatcher.matches("lab-*", "prod-foo"))
    }

    @Test
    fun `caracter exacto con ?`() {
        assertTrue(GlobMatcher.matches("test-?", "test-A"))
        assertFalse(GlobMatcher.matches("test-?", "test-AB"))
        assertFalse(GlobMatcher.matches("test-?", "test-"))
    }

    @Test
    fun `alternativas con coma`() {
        assertTrue(GlobMatcher.matches("lab-*,test-?", "lab-foo"))
        assertTrue(GlobMatcher.matches("lab-*,test-?", "test-A"))
        assertFalse(GlobMatcher.matches("lab-*,test-?", "prod-x"))
    }

    @Test
    fun `match exacto sin wildcards`() {
        assertTrue(GlobMatcher.matches("session-cisco", "session-cisco"))
        assertFalse(GlobMatcher.matches("session-cisco", "session-mikrotik"))
    }

    @Test
    fun `caracteres regex especiales en sessionId quedan literales`() {
        // No deberían ser interpretados como regex.
        assertTrue(GlobMatcher.matches("ses.id-1", "ses.id-1"))
        assertFalse(GlobMatcher.matches("ses.id-1", "sesXid-1"))
    }
}