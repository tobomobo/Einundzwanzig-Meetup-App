package space.einundzwanzig.meetup.data.backup.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.data.backup.BackupExporter
import space.einundzwanzig.meetup.data.backup.BackupSession
import space.einundzwanzig.meetup.data.backup.RestoreEngine
import space.einundzwanzig.meetup.data.backup.internal.BackupAdmins
import space.einundzwanzig.meetup.data.backup.internal.BackupIdentity
import space.einundzwanzig.meetup.data.backup.internal.BackupProofs
import space.einundzwanzig.meetup.data.backup.internal.DefaultBackupExporter
import space.einundzwanzig.meetup.data.backup.internal.DefaultBackupSession
import space.einundzwanzig.meetup.data.backup.internal.DefaultRestoreEngine
import space.einundzwanzig.meetup.data.backup.internal.RegistryBackupAdmins
import space.einundzwanzig.meetup.data.backup.internal.RepositoryBackupProofs
import space.einundzwanzig.meetup.data.backup.internal.SigningBackupIdentity
import javax.inject.Singleton

/** Public backup services and their internal ports onto the other domains. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class BackupModule {

    @Binds
    @Singleton
    abstract fun exporter(impl: DefaultBackupExporter): BackupExporter

    @Binds
    @Singleton
    abstract fun restoreEngine(impl: DefaultRestoreEngine): RestoreEngine

    @Binds
    @Singleton
    abstract fun session(impl: DefaultBackupSession): BackupSession

    @Binds
    abstract fun identity(impl: SigningBackupIdentity): BackupIdentity

    @Binds
    abstract fun admins(impl: RegistryBackupAdmins): BackupAdmins

    @Binds
    abstract fun proofs(impl: RepositoryBackupProofs): BackupProofs
}
