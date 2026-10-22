package space.einundzwanzig.meetup.testing;

import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore;
import space.einundzwanzig.meetup.core.data.secure.SecureStore;
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository;
import space.einundzwanzig.meetup.data.identity.SigningService;
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore;
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository;
import space.einundzwanzig.meetup.data.migration.FlutterMigration;
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofRepository;

/** Only debug and device-test variants include this interface; absent from production releases. */
@EntryPoint
@InstallIn(SingletonComponent.class)
public interface DeviceParityEntryPoint {
    KeyValueStore preferences();
    SecureStore secureStore();
    SecureKeyStore keys();
    UserProfileRepository profiles();
    SigningService signing();
    BadgeRepository badges();
    FlutterMigration migration();
    PlatformProofRepository platformProofs();
}
