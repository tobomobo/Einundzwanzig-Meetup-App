package space.einundzwanzig.meetup.data.badgesync.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.data.badgesync.BadgeSync
import space.einundzwanzig.meetup.data.badgesync.internal.BadgeSyncStartupTask
import space.einundzwanzig.meetup.data.badgesync.internal.DefaultBadgeSync
import space.einundzwanzig.meetup.data.badgesync.internal.HumanityProofsWiper
import space.einundzwanzig.meetup.data.badgesync.internal.IdentityProofsWiper
import space.einundzwanzig.meetup.data.badgesync.internal.SigningSyncIdentity
import space.einundzwanzig.meetup.data.badgesync.internal.SyncIdentity
import javax.inject.Singleton

/** The badge-sync singleton, its internal ports and its startup task. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class BadgeSyncModule {

    @Binds
    @Singleton
    abstract fun badgeSync(impl: DefaultBadgeSync): BadgeSync

    @Binds
    abstract fun syncIdentity(impl: SigningSyncIdentity): SyncIdentity

    @Binds
    abstract fun proofsWiper(impl: HumanityProofsWiper): IdentityProofsWiper

    @Binds
    @IntoSet
    abstract fun startupTask(task: BadgeSyncStartupTask): AppStartupTask
}
