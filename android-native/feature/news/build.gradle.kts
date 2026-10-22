plugins {
    id("einundzwanzig.android.feature")
}

dependencies {
    // NewsService, NewsReactionsService, NewsZapService, NewsArticles, ZapInvoiceLauncher.
    implementation(project(":data:news"))
    // HomeWidgetStore.markNewsSeen() when News opens with markAsRead (widget "NEU" marker).
    implementation(project(":data:calendar"))
}
