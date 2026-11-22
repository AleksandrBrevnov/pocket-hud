package com.aleksandr.pockethud

/**
 * Decodes the non-identifying bind flag from the D3200 BLE advertisement.
 *
 * Android ScanRecord.getManufacturerSpecificData() strips the two-byte company
 * identifier from the combined advertisement structure. Per the Soundcore
 * protocol, combined byte 6 is flags: Android's payload byte 4.
 *
 * The "soundc" manufacturer marker may be absent and is NOT required to
 * decode flags. The caller must first verify the D3200 service UUID.
 */
internal object D3200Advertisement {
    fun bindingState(manufacturerPayload: ByteArray?): String {
        if (manufacturerPayload == null || manufacturerPayload.size < 5) return "unknown"
        val flags = manufacturerPayload[4].toInt() and 0xff
        return if ((flags and 0x80) != 0) "bound_bit_set" else "bound_bit_clear"
    }
}
