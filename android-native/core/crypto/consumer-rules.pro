# secp256k1-kmp loads its JNI bindings reflectively (the AAR keeps fr.acinq.secp256k1.jni.**;
# the interface and its companion are kept here as well).
-keep class fr.acinq.secp256k1.** { *; }

# Only BouncyCastle's lightweight API is used. Parts of bcprov we never touch (LDAP cert stores)
# reference JDK classes that Android does not have.
-dontwarn javax.naming.**
