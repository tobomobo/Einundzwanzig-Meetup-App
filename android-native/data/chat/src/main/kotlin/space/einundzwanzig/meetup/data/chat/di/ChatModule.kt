package space.einundzwanzig.meetup.data.chat.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.data.chat.ChatReadMarkStore
import space.einundzwanzig.meetup.data.chat.ChatService
import space.einundzwanzig.meetup.data.chat.EventChatService
import space.einundzwanzig.meetup.data.chat.internal.ConnectivityNetworkStatus
import space.einundzwanzig.meetup.data.chat.internal.DefaultChatReadMarkStore
import space.einundzwanzig.meetup.data.chat.internal.DefaultChatService
import space.einundzwanzig.meetup.data.chat.internal.DefaultEventChatService
import space.einundzwanzig.meetup.data.chat.internal.NetworkStatus
import javax.inject.Singleton

/** Process-wide singletons of the chat domain (the room cache lives in [ChatService]). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ChatModule {

    @Binds
    @Singleton
    abstract fun chatService(impl: DefaultChatService): ChatService

    @Binds
    @Singleton
    abstract fun eventChatService(impl: DefaultEventChatService): EventChatService

    @Binds
    @Singleton
    abstract fun chatReadMarkStore(impl: DefaultChatReadMarkStore): ChatReadMarkStore

    @Binds
    @Singleton
    abstract fun networkStatus(impl: ConnectivityNetworkStatus): NetworkStatus
}
