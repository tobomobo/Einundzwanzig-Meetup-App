plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // ChatService (NIP-29 rooms), EventChatService (NIP-22 threads), ChatRules (+ :core:nostr/:core:crypto as api).
    implementation(project(":data:chat"))
    // NostrProfileService.fetchDisplayName for sender names.
    implementation(project(":data:profile"))
    // SigningService.pubkeyHex for "own message" detection.
    implementation(project(":data:identity"))
    // ContextCompat.registerReceiver for the time tick receiver of ChatClock.
    implementation(libs.androidx.core.ktx)
}
