package space.einundzwanzig.meetup.feature.admin.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import space.einundzwanzig.meetup.feature.admin.AdminPanelRepository
import space.einundzwanzig.meetup.feature.admin.DefaultAdminPanelRepository

@Module
@InstallIn(ViewModelComponent::class)
internal abstract class AdminFeatureModule {
    @Binds
    abstract fun adminPanelRepository(impl: DefaultAdminPanelRepository): AdminPanelRepository
}
