package com.nas.gallerymount.root

import org.junit.Assert.assertEquals
import org.junit.Test

class RootExecutorQuoteTest {
    @Test
    fun shellQuote_wrapsAndEscapes() {
        assertEquals("'simple'", RootExecutor.shellQuote("simple"))
        assertEquals("'a'\\''b'", RootExecutor.shellQuote("a'b"))
        assertEquals("'hello world'", RootExecutor.shellQuote("hello world"))
    }

    @Test
    fun buildShellScript_joinsQuotedArgs() {
        val script = RootExecutor.buildShellScript(
            arrayOf("/system/bin/ls", "-A", "/path/with space"),
        )
        assertEquals("'/system/bin/ls' '-A' '/path/with space'", script)
    }
}
