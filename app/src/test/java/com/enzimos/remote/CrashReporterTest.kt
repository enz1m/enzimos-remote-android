package com.enzimos.remote

import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

class CrashReporterTest {

    @Test
    fun `generate report truncated`() {
        val report = CrashReporter.generateReport(RuntimeException("test"))
        assertTrue(report.length <= CrashReporter.MAX_REPORT_LENGTH)
    }
}
