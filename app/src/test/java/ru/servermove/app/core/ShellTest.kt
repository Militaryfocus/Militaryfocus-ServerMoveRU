package ru.servermove.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShellTest {
    @Test
    fun quoteEscapesSingleQuote() {
        assertEquals("'a'\\''b'", Shell.quote("a'b"))
    }

    @Test
    fun absolutePathRejectsRelativePath() {
        assertThrows(IllegalArgumentException::class.java) {
            Shell.requireAbsolutePath("var/www", "path")
        }
    }

    @Test
    fun databaseNameRejectsShellSyntax() {
        assertThrows(IllegalArgumentException::class.java) {
            Shell.requireDatabaseName("db;rm", "db")
        }
    }
}
