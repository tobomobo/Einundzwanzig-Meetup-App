package space.einundzwanzig.meetup.data.news.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.data.news.NewsReactionsService
import space.einundzwanzig.meetup.data.news.NewsService
import space.einundzwanzig.meetup.data.news.NewsZapService
import space.einundzwanzig.meetup.data.news.internal.DefaultNewsReactionsService
import space.einundzwanzig.meetup.data.news.internal.DefaultNewsService
import space.einundzwanzig.meetup.data.news.internal.DefaultNewsZapService
import javax.inject.Singleton

/** Process-wide singletons of the news domain (the news RAM cache lives in [NewsService]). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class NewsModule {

    @Binds
    @Singleton
    abstract fun newsService(impl: DefaultNewsService): NewsService

    @Binds
    @Singleton
    abstract fun newsReactionsService(impl: DefaultNewsReactionsService): NewsReactionsService

    @Binds
    @Singleton
    abstract fun newsZapService(impl: DefaultNewsZapService): NewsZapService
}
