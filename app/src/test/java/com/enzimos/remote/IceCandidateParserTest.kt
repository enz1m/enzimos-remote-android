package com.enzimos.remote

import com.enzimos.remote.webrtc.IceCandidateParser
import org.junit.Assert.*
import org.junit.Test

class IceCandidateParserTest {

    @Test
    fun `parse invalid missing typ`() {
        assertNull(IceCandidateParser.parse("1 1 UDP 100 1.2.3.4 5000 nottyp host"))
    }
}
