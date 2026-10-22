package space.einundzwanzig.meetup.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.core.data.file.DirectoryFileStore
import space.einundzwanzig.meetup.core.data.file.FileStore
import space.einundzwanzig.meetup.core.data.internal.CorruptionPolicy
import space.einundzwanzig.meetup.core.data.internal.DataStoreFiles
import space.einundzwanzig.meetup.core.data.internal.createPreferencesDataStore
import space.einundzwanzig.meetup.core.data.prefs.DataStoreKeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.secure.AesGcmSecretCipher
import space.einundzwanzig.meetup.core.data.secure.AndroidKeystoreKeyProvider
import space.einundzwanzig.meetup.core.data.secure.DataStoreSecureStore
import space.einundzwanzig.meetup.core.data.secure.SecureStore
import space.einundzwanzig.meetup.core.data.migration.*
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton

/** The DataStore behind [KeyValueStore]; internal so nobody bypasses the store API. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class AppPrefsDataStore

/** The DataStore behind [SecureStore]; holds ciphertexts only. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class SecurePrefsDataStore

/**
 * Binds the persistence primitives as process-wide singletons:
 * [KeyValueStore] (`app_prefs`), [SecureStore] (`secure_prefs` + Keystore alias
 * `einundzwanzig_secure_store_v1`) and [FileStore] (rooted at `filesDir`).
 *
 * Nothing touches the disk or the Keystore at injection time; the first read does.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {

    @Provides
    @Singleton
    @AppPrefsDataStore
    fun appPrefsDataStore(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): DataStore<Preferences> = createPreferencesDataStore(
        file = context.preferencesDataStoreFile(DataStoreFiles.APP_PREFS),
        scope = CoroutineScope(ioDispatcher + SupervisorJob()),
        policy = CorruptionPolicy.RESET_KEEPING_COPY,
    )

    @Provides
    @Singleton
    @SecurePrefsDataStore
    fun securePrefsDataStore(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): DataStore<Preferences> = createPreferencesDataStore(
        file = context.preferencesDataStoreFile(DataStoreFiles.SECURE_PREFS),
        scope = CoroutineScope(ioDispatcher + SupervisorJob()),
        // Never "heal" lost secrets into an empty store (Flutter resetOnError = false).
        policy = CorruptionPolicy.FAIL_KEEPING_COPY,
    )

    @Provides
    @Singleton
    @Ungated
    fun ungatedKeyValueStore(@AppPrefsDataStore dataStore: DataStore<Preferences>): KeyValueStore =
        DataStoreKeyValueStore(dataStore)

    @Provides
    @Singleton
    @Ungated
    fun ungatedSecureStore(
        @SecurePrefsDataStore dataStore: DataStore<Preferences>,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): SecureStore = DataStoreSecureStore(
        dataStore = dataStore,
        cipher = AesGcmSecretCipher(AndroidKeystoreKeyProvider(AndroidKeystoreKeyProvider.KEY_ALIAS)),
        ioDispatcher = ioDispatcher,
    )

    @Provides
    @Singleton
    fun keyValueStore(@Ungated raw: KeyValueStore, gate: Provider<MigrationGate>): KeyValueStore =
        GatedKeyValueStore(raw, gate::get)

    @Provides
    @Singleton
    fun secureStore(
        @Ungated raw: SecureStore,
        gate: Provider<MigrationGate>,
        legacy: Provider<LegacySecretStatus>,
    ): SecureStore = GatedSecureStore(raw, gate::get, legacy::get)

    @Provides
    @Singleton
    fun fileStore(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): FileStore = DirectoryFileStore({ context.filesDir }, ioDispatcher)
}
