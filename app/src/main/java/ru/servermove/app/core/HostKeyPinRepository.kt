package ru.servermove.app.core

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64

class HostKeyPinRepository(expectedFingerprint: String) : HostKeyRepository {
    private val expected = normalize(expectedFingerprint)

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        return if (fingerprint(key) == expected) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "Pinned SHA-256 host key"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    companion object {
        private val FINGERPRINT_PATTERN = Regex("^SHA256:[A-Za-z0-9+/]{43}$")
        fun normalize(value: String): String {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "SHA-256 fingerprint SSH-хоста обязателен" }
            val normalized = if (trimmed.startsWith("SHA256:")) trimmed else "SHA256:$trimmed"
            require(FINGERPRINT_PATTERN.matches(normalized)) {
                "Некорректный SHA-256 fingerprint SSH-хоста"
            }
            return normalized
        }

        fun fingerprint(key: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
