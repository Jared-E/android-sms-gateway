package me.capcom.smsgateway.modules.messages.mms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class MmsPduComposerTest {

    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }

    @Test
    fun composeMSendReqTest() {
        val pdu = MmsPduComposer.compose(
            transactionId = "tx1",
            recipients = listOf("+15551234567"),
            subject = "Hi",
            text = "Hello",
            parts = listOf(
                MmsPduComposer.Part(
                    contentType = "image/png",
                    name = "a.png",
                    data = byteArrayOf(0x01, 0x02, 0x03),
                )
            ),
            deliveryReport = true,
        )

        val expected = (
            // --- headers ---
            "8C 80 " +                                              // X-Mms-Message-Type: m-send-req
                "98 74 78 31 00 " +                                 // X-Mms-Transaction-Id: "tx1"
                "8D 92 " +                                          // X-Mms-MMS-Version: 1.2
                "89 01 81 " +                                       // From: insert-address-token
                "97 2B 31 35 35 35 31 32 33 34 35 36 37 " +         // X-Mms-To: "+15551234567/TYPE=PLMN"
                "2F 54 59 50 45 3D 50 4C 4D 4E 00 " +
                "96 48 69 00 " +                                    // X-Mms-Subject: "Hi"
                "8A 80 " +                                          // X-Mms-Message-Class: Personal
                "86 80 " +                                          // X-Mms-Delivery-Report: Yes
                "84 A3 " +                                          // Content-Type: multipart.mixed
                // --- body ---
                "02 " +                                             // number of parts
                // part 1: text/plain; charset=utf-8, "Hello"
                "0E 05 " +                                          // headers len, data len
                "0D 74 65 78 74 2F 70 6C 61 69 6E 00 81 EA " +      // content-type value + charset param
                "48 65 6C 6C 6F " +                                 // "Hello"
                // part 2: image/png; name=a.png, [01 02 03]
                "19 03 " +                                          // headers len, data len
                "11 69 6D 61 67 65 2F 70 6E 67 00 97 61 2E 70 6E 67 00 " + // content-type + name param
                "8E 61 2E 70 6E 67 00 " +                           // Content-Location: a.png
                "01 02 03"                                          // data
            )

        assertEquals(expected, pdu.toHex())
    }

    @Test
    fun composeWithoutSubjectOrTextTest() {
        val pdu = MmsPduComposer.compose(
            transactionId = "t",
            recipients = listOf("+100"),
            subject = null,
            text = null,
            parts = listOf(
                MmsPduComposer.Part("image/jpeg", null, byteArrayOf(0x0A)),
            ),
            deliveryReport = false,
        )

        val hex = pdu.toHex()
        // m-send-req, version, insert-address-token, no subject header (96), delivery-report No (86 81),
        // multipart.mixed with a single part.
        assertTrue(hex.startsWith("8C 80 98 74 00 8D 92 89 01 81"))
        assertTrue(hex.contains("86 81"))      // delivery report disabled
        assertTrue(!hex.contains(" 96 "))      // no subject header
        assertTrue(hex.contains("84 A3 01"))   // content-type then a single body part
    }
}
