package com.enzimos.remote

import org.junit.Assert.*
import org.junit.Test

class ErrorCatalogTest {

    @Test
    fun `auth failure is not recoverable`() {
        assertFalse(ErrorCatalog.isRecoverable("authentication failed"))
    }
}
