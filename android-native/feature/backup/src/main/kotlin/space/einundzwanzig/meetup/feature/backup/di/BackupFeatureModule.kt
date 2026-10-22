package space.einundzwanzig.meetup.feature.backup.di

import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.feature.backup.BackupCoordinator
import space.einundzwanzig.meetup.feature.backup.BackupDocuments
import space.einundzwanzig.meetup.feature.backup.BackupLauncher
import space.einundzwanzig.meetup.feature.backup.ContentResolverBackupDocuments

@Module
@InstallIn(SingletonComponent::class)
internal interface BackupFeatureModule {
    @Binds
    fun bindLauncher(impl: BackupCoordinator): BackupLauncher

    @Binds
    fun bindDocuments(impl: ContentResolverBackupDocuments): BackupDocuments
}

/** Access for composables outside a ViewModel ([space.einundzwanzig.meetup.feature.backup.rememberBackupLauncher]). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupEntryPoint {
    fun backupLauncher(): BackupLauncher
}
