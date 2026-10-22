package space.einundzwanzig.meetup.feature.chat.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.feature.chat.ChatPeople
import space.einundzwanzig.meetup.feature.chat.DefaultChatPeople

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ChatFeatureModule {
    @Binds
    abstract fun chatPeople(impl: DefaultChatPeople): ChatPeople
}
