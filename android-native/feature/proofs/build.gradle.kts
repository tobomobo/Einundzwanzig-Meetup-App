plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // PlatformProofRepository, PlatformCatalog, HumanityProofRepository (spec 06).
    implementation(project(":data:proofs"))
    // ReputationPublisher: forced republish after proof create/revoke and a found humanity proof.
    implementation(project(":data:reputation"))
    // BadgeRepository: the wallet passed to ReputationPublisher.publish (also api of :data:reputation).
    implementation(project(":data:badges"))
}
