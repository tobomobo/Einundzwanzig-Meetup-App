package space.einundzwanzig.meetup.testing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.crypto.*
import java.util.concurrent.TimeUnit

/** Real JNI, BouncyCastle and bundled ML Kit execution; genuine R8 coverage uses preview. */
@RunWith(AndroidJUnit4::class)
class ReleaseRuntimeDeviceTest {
    @Test fun nativeSchnorrSignsAndRejectsChangedMessages() {
        val pair = KeyPair.fromPrivateKeyHex("0".repeat(63) + "3")
        val message = Sha256.digest("device-runtime-test".toByteArray())
        val sig = pair.sign(message)
        assertTrue(Schnorr.verify(sig, message, Keys.xOnlyPublicKey(pair.privateKey())))
        message[0] = (message[0].toInt() xor 1).toByte()
        assertFalse(Schnorr.verify(sig, message, Keys.xOnlyPublicKey(pair.privateKey())))
    }

    @Test fun nip44RoundTripsThroughAndroidJni() {
        val alice = KeyPair.fromPrivateKeyHex("0".repeat(63) + "3")
        val bob = KeyPair.fromPrivateKeyHex("0".repeat(63) + "5")
        val a = Nip44.conversationKey(alice.privateKey().toHex(), bob.publicKeyHex)
        val b = Nip44.conversationKey(bob.privateKey().toHex(), alice.publicKeyHex)
        assertArrayEquals(a, b)
        val text = "Ende zu Ende · Wien ₿"
        assertEquals(text, Nip44.decrypt(Nip44.encrypt(text, a), b))
    }

    @Test fun nip49DefaultMemoryCostDecryptsOnDeviceAndRejectsWrongPassword() {
        val privateHex = "0".repeat(63) + "3"
        val encrypted = Nip49.encrypt(privateHex, "device-only-test-password")
        // Never expose private material through assertion diagnostics.
        assertTrue(Nip49.decrypt(encrypted, "device-only-test-password") == privateHex)
        assertThrows(Nip49Exception::class.java) { Nip49.decrypt(encrypted, "wrong-password") }
    }

    @Test fun bundledMlKitReadsQrWithoutGooglePlayModelDownload() {
        val payload = "21meetup-device-e2e:offline-qr"
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 512, 512)
        val pixels = IntArray(512 * 512) { i -> if (matrix[i % 512, i / 512]) Color.BLACK else Color.WHITE }
        val bitmap = Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888)
        try {
            // Invoke the exact QR-only factory used by CameraXSession, including its production
            // options and ML Kit component discovery. Only this internal test boundary is kept.
            val scanner = Class.forName("space.einundzwanzig.meetup.core.camera.QrCameraBackendKt")
                .getDeclaredMethod("newQrBarcodeScanner").invoke(null) as BarcodeScanner
            try {
                val results = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)
                assertEquals(listOf(Barcode.FORMAT_QR_CODE), results.map { it.format })
                assertEquals(listOf(payload), results.mapNotNull { it.rawValue })
            } finally {
                scanner.close()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
