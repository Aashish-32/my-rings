package com.aashish.myrings.data

import java.time.LocalDate

/**
 * A plain-text backup of a ring's auth key. The key is the only way back into a paired ring (otherwise it needs another
 * factory reset), and the app keeps it in private storage that an uninstall wipes, so the person can save a copy.
 * The format is meant to be readable by a human and pasteable back into the "Add a ring" box.
 */
object KeyBackup {
    private val keyLine = Regex("""(?im)^\s*key\s*[:=]\s*([0-9a-f]{32})\s*$""")
    private val anyKey = Regex("""(?i)(?<![0-9a-f])([0-9a-f]{32})(?![0-9a-f])""")

    fun build(ringName: String, serial: String, keyHex: String, today: LocalDate = LocalDate.now()): String = buildString {
        appendLine("My Rings key backup")
        appendLine("Ring: $ringName")
        appendLine("Serial: $serial")
        appendLine("Key: ${keyHex.lowercase()}")
        appendLine("Saved: $today")
        appendLine()
        appendLine("Keep this private: anyone with this key can talk to the ring.")
        appendLine("To restore it, choose \"Ring is already set up\" when adding the ring and paste this text.")
    }

    fun fileName(serial: String) = "myrings-key-$serial.txt"

    /** The 32-hex-character key inside a backup (or a bare key), or null if there isn't exactly one candidate. */
    fun extract(text: String): String? {
        keyLine.find(text)?.let { return it.groupValues[1].lowercase() }
        val all = anyKey.findAll(text).map { it.groupValues[1].lowercase() }.distinct().toList()
        return all.singleOrNull()
    }

    /** The serial number written in a backup, if present. */
    fun serialOf(text: String): String? = Regex("""(?im)^\s*serial\s*[:=]\s*([0-9a-z]+)\s*$""").find(text)?.groupValues?.get(1)
}
