package space.einundzwanzig.meetup.feature.wallet.crest

import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.core.ui.crest.MeetupCrestSource

/**
 * Binds [WalletCrestSource] as the app's [MeetupCrestSource]. The app shell injects it and provides
 * `LocalMeetupCrestSource` once, so every feature (home, events, ...) gets real crests.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class WalletCrestModule {
    @Binds
    abstract fun bindMeetupCrestSource(source: WalletCrestSource): MeetupCrestSource
}

/** Access to the bound [MeetupCrestSource] outside of injection (routes, app shell). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WalletCrestEntryPoint {
    fun meetupCrestSource(): MeetupCrestSource
}
