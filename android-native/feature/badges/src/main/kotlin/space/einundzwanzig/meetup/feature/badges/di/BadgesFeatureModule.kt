package space.einundzwanzig.meetup.feature.badges.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.feature.badges.nfc.AndroidNfcStatusSource
import space.einundzwanzig.meetup.feature.badges.nfc.NfcStatusSource
import space.einundzwanzig.meetup.feature.badges.scanner.ScannerLabels
import javax.inject.Inject
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@Module
@InstallIn(SingletonComponent::class)
internal abstract class BadgesFeatureModule {
    @Binds
    abstract fun nfcStatusSource(impl: AndroidNfcStatusSource): NfcStatusSource

    @Binds
    abstract fun scannerLabels(impl: ResourceScannerLabels): ScannerLabels
}

/** Reads the label from the current configuration on every call (locale switches apply). */
internal class ResourceScannerLabels @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ScannerLabels {
    override fun unknownMeetup(): String = context.getString(L10nR.string.verifyUnknownMeetup)
}
