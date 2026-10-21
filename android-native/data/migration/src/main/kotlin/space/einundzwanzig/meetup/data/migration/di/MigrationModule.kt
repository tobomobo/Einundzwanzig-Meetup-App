package space.einundzwanzig.meetup.data.migration.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import space.einundzwanzig.meetup.data.migration.FlutterMigration
import space.einundzwanzig.meetup.data.migration.MigrationCompletedHook
import space.einundzwanzig.meetup.data.migration.MigrationGate
import space.einundzwanzig.meetup.data.migration.internal.AppLoggerLegacyImporter
import space.einundzwanzig.meetup.data.migration.internal.DefaultFlutterMigration
import space.einundzwanzig.meetup.data.migration.internal.LegacyLogImporter
import space.einundzwanzig.meetup.data.migration.legacy.AndroidLegacyKeystore
import space.einundzwanzig.meetup.data.migration.legacy.LegacyKeystore
import javax.inject.Singleton

/** Binds [FlutterMigration] and [MigrationGate] (one singleton) and the optional completion hooks. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MigrationModule {

    @Binds
    abstract fun flutterMigration(impl: DefaultFlutterMigration): FlutterMigration

    @Binds
    abstract fun migrationGate(impl: DefaultFlutterMigration): MigrationGate

    /** Lets the set be empty when no module contributes a hook. */
    @Multibinds
    abstract fun completionHooks(): Set<MigrationCompletedHook>

    companion object {
        @Provides
        @Singleton
        fun legacyKeystore(@ApplicationContext context: Context): LegacyKeystore = AndroidLegacyKeystore(context.packageName)

        @Provides
        fun legacyLogImporter(): LegacyLogImporter = AppLoggerLegacyImporter
    }
}
