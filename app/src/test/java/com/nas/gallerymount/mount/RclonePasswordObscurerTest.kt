package com.nas.gallerymount.mount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RclonePasswordObscurerTest {
    private val obscurer = RclonePasswordObscurer()

    @Test
    fun obscure_roundTrip() {
        val plain = "my-smb-secret-密碼"
        val obscured = obscurer.obscure(plain)
        assertTrue(obscured.isNotEmpty())
        assertFalse(obscured.contains(' '))
        assertFalse(obscured.contains('/')) // Raw URL 用 _ 取代 /
        assertEquals(plain, RclonePasswordObscurer.revealForTest(obscured))
    }

    @Test
    fun obscure_empty() {
        assertEquals("", obscurer.obscure(""))
    }

    @Test
    fun obscure_notPlaintext() {
        val plain = "hello"
        val obscured = obscurer.obscure(plain)
        assertFalse(obscured.contains(plain))
    }
}
