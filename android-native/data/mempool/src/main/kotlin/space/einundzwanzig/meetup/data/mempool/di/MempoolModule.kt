package space.einundzwanzig.meetup.data.mempool.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.data.mempool.MempoolConfig
import space.einundzwanzig.meetup.data.mempool.MempoolService
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyRepository
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyRepositoryImpl
import space.einundzwanzig.meetup.data.mempool.internal.CurrencyLoadStartupTask
import space.einundzwanzig.meetup.data.mempool.internal.MempoolConfigImpl
import space.einundzwanzig.meetup.data.mempool.internal.MempoolServiceImpl
import space.einundzwanzig.meetup.data.mempool.widget.BitcoinWidgetBridge
import space.einundzwanzig.meetup.data.mempool.widget.BitcoinWidgetBridgeImpl
import space.einundzwanzig.meetup.data.mempool.widget.BitcoinWidgetSink

/** Binds the mempool domain as process-wide singletons. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MempoolModule {

    @Binds
    abstract fun mempoolConfig(impl: MempoolConfigImpl): MempoolConfig

    @Binds
    abstract fun mempoolService(impl: MempoolServiceImpl): MempoolService

    @Binds
    abstract fun currencyRepository(impl: CurrencyRepositoryImpl): CurrencyRepository

    @Binds
    abstract fun bitcoinWidgetBridge(impl: BitcoinWidgetBridgeImpl): BitcoinWidgetBridge

    /** The widget domain contributes sinks; none is fine. */
    @Multibinds
    abstract fun bitcoinWidgetSinks(): Set<BitcoinWidgetSink>

    @Binds
    @IntoSet
    abstract fun currencyLoad(task: CurrencyLoadStartupTask): AppStartupTask
}
