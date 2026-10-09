package de.omarfourati.redefluss.auth

import org.mindrot.jbcrypt.BCrypt

object Passwords {
    fun hash(pw: String): String = BCrypt.hashpw(pw, BCrypt.gensalt(12))
    fun verify(pw: String, hash: String): Boolean = try { BCrypt.checkpw(pw, hash) } catch (e: IllegalArgumentException) { false }

    /** bcrypt silently ignores everything after 72 bytes – reject instead of truncating. */
    fun problem(pw: String): String? = when {
        pw.length < 12 -> "Das Passwort braucht mindestens 12 Zeichen."
        pw.toByteArray(Charsets.UTF_8).size > 72 -> "Das Passwort ist zu lang (höchstens 72 Bytes)."
        else -> null
    }
}
