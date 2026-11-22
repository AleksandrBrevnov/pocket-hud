package com.aleksandr.pockethud

import org.junit.Assert.assertEquals
import org.junit.Test

class D3200AdvertisementTest {
    @Test fun decodesAndroidSixByteManufacturerPayload() {
        // 6 bytes is the device's actual manufacturer payload on Android 16.
        // Combined advertisement: 2-byte company ID + 6-byte payload.
        assertEquals("bound_bit_set",
            D3200Advertisement.bindingState(byteArrayOf(1, 2, 3, 4, 0x80.toByte(), 5)))
        assertEquals("bound_bit_clear",
            D3200Advertisement.bindingState(byteArrayOf(1, 2, 3, 4, 0x10, 5)))
    }

    @Test fun recognizesFlagWithOptionalLongerManufacturerMarker() {
        assertEquals("bound_bit_set", D3200Advertisement.bindingState(
            byteArrayOf(0, 0, 0, 0, 0x91.toByte(), 0, 's'.code.toByte(),
                'o'.code.toByte(), 'u'.code.toByte(), 'n'.code.toByte(), 'd'.code.toByte(), 'c'.code.toByte())
        ))
    }

    @Test fun refusesToGuessOnMissingFlag() {
        assertEquals("unknown", D3200Advertisement.bindingState(null))
        assertEquals("unknown", D3200Advertisement.bindingState(byteArrayOf()))
        assertEquals("unknown", D3200Advertisement.bindingState(byteArrayOf(1, 2, 3, 4)))
    }
}
