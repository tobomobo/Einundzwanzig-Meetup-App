import 'package:einundzwanzig_meetup_app/l10n/app_localizations.dart';
import 'package:einundzwanzig_meetup_app/models/badge.dart';
import 'package:einundzwanzig_meetup_app/screens/app_shell.dart';
import 'package:einundzwanzig_meetup_app/screens/badge_wallet.dart';
import 'package:einundzwanzig_meetup_app/screens/events_hub_screen.dart';
import 'package:einundzwanzig_meetup_app/screens/home_screen.dart';
import 'package:einundzwanzig_meetup_app/services/guide_service.dart';
import 'package:einundzwanzig_meetup_app/tours/home_tour.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets(
    'tabs initialize on demand, retain state and mute hidden tickers',
    (tester) async {
      SharedPreferences.setMockInitialValues({
        'nickname': 'Tester',
        'guide_completed_home': true,
        'guide_completed_wallet': true,
        'haptic_enabled': false,
      });
      FlutterSecureStorage.setMockInitialValues({});
      myBadges.clear();
      addTearDown(myBadges.clear);
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(360, 780);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      await tester.pumpWidget(
        ChangeNotifierProvider(
          create: (_) => GuideService(),
          child: const MaterialApp(
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            home: AppShell(),
          ),
        ),
      );
      for (var i = 0; i < 10; i++) {
        await tester.pump(const Duration(milliseconds: 200));
      }
      final home = find.byType(HomeScreen, skipOffstage: false);
      final wallet = find.byType(BadgeWalletScreen, skipOffstage: false);
      final events = find.byType(EventsHubScreen, skipOffstage: false);
      expect(home, findsOneWidget);
      expect(wallet, findsNothing);
      expect(events, findsNothing);
      expect(TickerMode.valuesOf(tester.element(home)).enabled, isTrue);

      await tester.tap(find.byKey(HomeTour.badgeTabKey));
      await tester.pump();
      expect(wallet, findsOneWidget);
      expect(events, findsNothing);
      final walletState = tester.state(wallet);
      expect(TickerMode.valuesOf(tester.element(home)).enabled, isFalse);
      expect(TickerMode.valuesOf(tester.element(wallet)).enabled, isTrue);

      await tester.tap(find.byKey(HomeTour.homeTabKey));
      await tester.pump();
      expect(tester.state(wallet), same(walletState));
      expect(TickerMode.valuesOf(tester.element(wallet)).enabled, isFalse);
      expect(TickerMode.valuesOf(tester.element(home)).enabled, isTrue);

      // Home and scan routes update the shared badge collection. Revisiting
      // the wallet must rebuild its contents while retaining its screen state.
      myBadges.add(
        MeetupBadge(
          id: 'new-badge',
          meetupName: 'Test meetup',
          date: DateTime(2026, 10, 22),
          iconPath: '',
        ),
      );
      await tester.tap(find.byKey(HomeTour.badgeTabKey));
      await tester.pump();
      expect(tester.state(wallet), same(walletState));
      expect(find.text('BADGE WALLET (1)'), findsOneWidget);

      await tester.tap(find.byKey(HomeTour.eventsTabKey));
      await tester.pump();
      expect(events, findsOneWidget);
      expect(TickerMode.valuesOf(tester.element(events)).enabled, isTrue);
      expect(tester.takeException(), isNull);

      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(const Duration(minutes: 1));
    },
  );
}
