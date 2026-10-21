package space.einundzwanzig.meetup.core.common.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    private val redactor = SecretRedactor()
    private val r = SecretRedactor.REDACTED

    // Test key material only (NIP-19 / NIP-49 examples), never a real key.
    private val nsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
    private val ncryptsec = "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"
    private val pubkeyHex = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    private val privHex = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa"

    @Test
    fun `bech32 secrets are removed in any case`() {
        assertEquals("Import nsec1$r fehlgeschlagen", redactor.redact("Import $nsec fehlgeschlagen"))
        assertEquals("nostr:nsec1$r", redactor.redact("nostr:$nsec"))
        assertEquals("NSEC1$r", redactor.redact(nsec.uppercase()))
        assertEquals("Backup ncryptsec1$r ok", redactor.redact("Backup $ncryptsec ok"))
    }

    @Test
    fun `npub and public hex stay readable`() {
        val npub = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
        val text = "Frage Relays nach Super-Admin ($pubkeyHex) / $npub / Event $privHex"
        assertEquals("without registration a hex value is indistinguishable from an id", text, redactor.redact(text))
    }

    @Test
    fun `registered secrets are removed wherever they appear`() {
        redactor.registerSecret(privHex)
        assertEquals("key=$r!", redactor.redact("key=$privHex!"))
        assertEquals("a $r b", redactor.redact("a $privHex b"))
        assertEquals("upper case hex too", "x$r", redactor.redact("x" + privHex.uppercase()))
        assertEquals("$r$r", redactor.redact(privHex + privHex))
        assertEquals("other hex untouched", "Super-Admin $pubkeyHex", redactor.redact("Super-Admin $pubkeyHex"))
        redactor.unregisterSecret(privHex)
        assertEquals("a $privHex b", redactor.redact("a $privHex b"))
    }

    @Test
    fun `overlapping registered secrets merge into one marker`() {
        redactor.registerSecret("abcdefgh12")
        redactor.registerSecret("gh12345678")
        assertEquals("<$r>", redactor.redact("<abcdefgh12345678>"))
        redactor.clearRegisteredSecrets()
        assertEquals("<abcdefgh12345678>", redactor.redact("<abcdefgh12345678>"))
    }

    @Test
    fun `short registered secrets are ignored`() {
        redactor.registerSecret("1234")
        assertEquals("PIN 1234", redactor.redact("PIN 1234"))
    }

    @Test
    fun `bunker and nostrconnect secrets are removed`() {
        val bunker = "bunker://$pubkeyHex?relay=wss%3A%2F%2Frelay.nsec.app&secret=s3cr3tvalue"
        assertEquals("bunker://$pubkeyHex?relay=wss%3A%2F%2Frelay.nsec.app&secret=$r", redactor.redact(bunker))
        val connect = "nostrconnect://$pubkeyHex?secret=abc123xyz&relay=wss://r.example&perms=sign_event"
        assertEquals("nostrconnect://$pubkeyHex?secret=$r&relay=wss://r.example&perms=sign_event", redactor.redact(connect))
    }

    @Test
    fun `url tokens and http credentials are removed`() {
        assertEquals("GET https://a.example/x?token=$r&page=2", redactor.redact("GET https://a.example/x?token=abc.def&page=2"))
        assertEquals("Authorization: Bearer $r", redactor.redact("Authorization: Bearer 12|AbCdEfGhIjKlMnOp"))
        assertEquals("Authorization: Nostr $r", redactor.redact("Authorization: Nostr eyJpZCI6IjAwMDAwMDAwMDAwMDAwMDAwMCJ9"))
        assertEquals("plain prose stays", "Nostr Kalender geladen", redactor.redact("Nostr Kalender geladen"))
    }

    @Test
    fun `labelled values are removed in prose, key-value and JSON`() {
        assertEquals("privkey=$r", redactor.redact("privkey=$privHex"))
        assertEquals("""{"private_key":"$r"}""", redactor.redact("""{"private_key":"$privHex"}"""))
        assertEquals("Signer secret: $r", redactor.redact("Signer secret: hunter2hunter2"))
        assertEquals("seed = $r", redactor.redact("seed = abandonabandon"))
        assertEquals("Portal-Token: $r", redactor.redact("Portal-Token: 42|xyzxyzxyz"))
    }

    @Test
    fun `German diagnostics from the Flutter app stay untouched`() {
        val messages = listOf(
            "Token-Prüfung: Portal meldet 401 — Token serverseitig ungültig.",
            "Token gehört zu anderem Schlüssel — getrennt.",
            "Kein Portal-Token vorhanden — übersprungen.",
            "Anmeldung konnte nicht signiert werden — IllegalStateException: Kein Nostr-Schlüssel vorhanden.",
            "Admin-Liste von wss://relay.example: 3 von 47 Relay-Nachrichten nicht auswertbar",
            "Freigabe-Seite nicht geöffnet (Schema nicht http(s) oder kein Browser), Host: nsec.app",
            "───── APP-START ─────",
        )
        for (m in messages) assertEquals(m, m, redactor.redact(m))
    }

    @Test
    fun `redaction is idempotent`() {
        redactor.registerSecret(privHex)
        val once = redactor.redact("token=$privHex secret: $privHex $nsec ?secret=abcdefgh")
        assertEquals(once, redactor.redact(once))
        assertFalse(once.contains(privHex))
        assertTrue(once.contains(r))
    }

    @Test
    fun `logger stores only redacted text`() {
        val log = DiagnosticLogger(redactor = redactor)
        log.warn("Import", "nsec abgelehnt: $nsec")
        log.error("Nip46", "Verbindung", IllegalStateException("bunker://$pubkeyHex?secret=topsecret1"))
        val all = log.entries.value.joinToString("\n") { it.message }
        assertFalse(all.contains(nsec))
        assertFalse(all.contains("topsecret1"))
        assertTrue(log.exportText().contains(r))
    }
}
