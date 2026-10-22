// Run from the repository root with Flutter. Uses the real Flutter export/import
// flows; only OS file pickers, preferences and secure storage are replaced.
// All keys and passwords in this fixture are public synthetic test values.
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:encrypt/encrypt.dart' as enc;
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart'
    show FlutterSecureStorage;
import 'package:flutter_test/flutter_test.dart';
import 'package:nostr/nostr.dart' show Keychain, Nip19;
import 'package:share_plus/share_plus.dart' show XFile;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:einundzwanzig_meetup_app/l10n/app_localizations.dart';
import 'package:einundzwanzig_meetup_app/models/badge.dart';
import 'package:einundzwanzig_meetup_app/models/user.dart';
import 'package:einundzwanzig_meetup_app/services/backup_service.dart';
import 'package:einundzwanzig_meetup_app/services/pbkdf2/pbkdf2_dart.dart';
import 'package:einundzwanzig_meetup_app/services/secure_key_store.dart';

const password = 'synthetic-interop-Grüße-😀';
const privateHex =
    '0000000000000000000000000000000000000000000000000000000000000001';
final output = Directory(
  Platform.environment['E21_BACKUP_INTEROP_DIR'] ??
      'android-native/build/backup-interop',
);

class CapturingPicker extends FilePickerPlatform {
  Uint8List? exported;

  @override
  Future<Uri?> saveFile({
    required String fileName,
    required Uint8List bytes,
    required String mimeType,
    String? dialogTitle,
    String? initialDirectory,
    Function(FilePickerStatus)? onFileSaving,
    WindowsOptions windowsOptions = const WindowsOptions(),
    LinuxOptions linuxOptions = const LinuxOptions(),
    WebOptions webOptions = const WebOptions(),
  }) async {
    expect(fileName, endsWith('.21bkp'));
    exported = bytes;
    return Uri.file('${output.path}/flutter-v5.21bkp');
  }

  @override
  Future<PlatformFile?> pickFile({
    String? dialogTitle,
    String? initialDirectory,
    FileType type = FileType.any,
    List<String>? allowedExtensions,
    Function(FilePickerStatus)? onFileLoading,
    int compressionQuality = 0,
    AndroidOptions androidOptions = const AndroidOptions(),
    DarwinOptions darwinOptions = const DarwinOptions(),
    WindowsOptions windowsOptions = const WindowsOptions(),
    LinuxOptions linuxOptions = const LinuxOptions(),
    WebOptions webOptions = const WebOptions(),
  }) async => NativeBackupFile(
    File(
      Platform.environment['E21_BACKUP_NATIVE_FILE'] ??
          'android-native/spec/fixtures/backup-interop/kotlin-v5.21bkp',
    ),
  );
}

final class NativeBackupFile extends PlatformFile {
  NativeBackupFile(this.file);
  final File file;
  @override
  String get name => file.uri.pathSegments.last;
  @override
  Uri get uri => file.uri;
  @override
  XFile get xFile => XFile(file.path);
  @override
  int? lengthSync() => file.lengthSync();
  @override
  Future<int?> length() => file.length();
  @override
  Future<Uint8List> readAsBytes() => file.readAsBytes();
  @override
  Stream<Uint8List> readAsByteStream() =>
      file.openRead().map(Uint8List.fromList);
}

Future<BuildContext> mount(WidgetTester tester) async {
  late BuildContext context;
  await tester.pumpWidget(
    MaterialApp(
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Builder(
          builder: (value) {
            context = value;
            return const SizedBox();
          },
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return context;
}

Future<void> enterPassword(
  WidgetTester tester, {
  required bool exporting,
}) async {
  // Import reads its file asynchronously before opening the dialog. Yield real
  // I/O as well as frames; pumpAndSettle alone only advances the fake UI clock.
  final waiting = Stopwatch()..start();
  while (find.byType(TextField).evaluate().isEmpty &&
      waiting.elapsed < const Duration(seconds: 5)) {
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 10)),
    );
    await tester.pump();
  }
  await tester.pumpAndSettle();
  expect(find.byType(TextField), findsNWidgets(exporting ? 2 : 1));
  await tester.enterText(find.byType(TextField).at(0), password);
  if (exporting) await tester.enterText(find.byType(TextField).at(1), password);
  await tester.pump();
  await tester.tap(find.byType(ElevatedButton));
  await tester.pump();
  // Export/import deliberately yields a frame for 50 ms before deriving the key.
  await tester.pump(const Duration(milliseconds: 100));
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late CapturingPicker picker;
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    FlutterSecureStorage.setMockInitialValues({});
    MeetupBadge.badgesUnreadable = false;
    MeetupBadge.onBadgesSaved = null;
    picker = CapturingPicker();
    FilePickerPlatform.instance = picker;
  });

  testWidgets(
    'real Flutter exporter produces the native interoperability fixture',
    (tester) async {
      final keys = Keychain(privateHex);
      final npub = Nip19.encodePubkey(keys.public) as String;
      await SecureKeyStore.saveKeys(
        nsec: Nip19.encodePrivkey(privateHex),
        npub: npub,
        privHex: privateHex,
      );
      await UserProfile(
        nickname: 'Interop Ada',
        fullName: 'Änne 😀',
        nostrNpub: npub,
        homeMeetupId: 'Aschaffenburg',
        favoriteMeetupIds: ['Aschaffenburg', 'Würzburg'],
        telegramHandle: 'synthetic_test',
        hasNostrKey: true,
        isNostrVerified: true,
      ).save();
      expect(
        await MeetupBadge.saveBadges([
          MeetupBadge(
            id: 'interop-meetup',
            meetupName: 'Aschaffenburg',
            meetupEventId: 'interop-2026-10-22',
            date: DateTime.utc(2026, 10, 22, 19),
            iconPath: 'assets/badge_icon.png',
            blockHeight: 918000,
            delivery: 'rolling_qr',
            lat: 49.97,
            lng: 9.15,
          ),
          MeetupBadge(
            id: 'interop-event',
            meetupName: 'Synthetic conference',
            meetupEventId: 'interop-conference',
            date: DateTime.utc(2026, 10, 23),
            iconPath: 'assets/badge_icon.png',
            isEvent: true,
            presenceVerified: false,
          ),
        ]),
        isTrue,
      );
      final context = await mount(tester);
      late Future<bool> pending;
      await tester.runAsync(() async {
        pending = BackupService.createBackup(context);
      });
      await enterPassword(tester, exporting: true);
      expect(
        await tester.runAsync(
          () => pending.timeout(const Duration(seconds: 30)),
        ),
        isTrue,
      );
      final bytes = picker.exported!;
      final parts = utf8.decode(bytes).split(':');
      expect(parts.first, 'enc_v2');
      final key = pbkdf2DartFromPassword(
        password: password,
        salt: base64Decode(parts[1]),
        iterations: 600000,
        keyBytes: 32,
      );
      final plain = enc.Encrypter(
        enc.AES(enc.Key(key), mode: enc.AESMode.gcm),
      ).decrypt64(parts[3], iv: enc.IV.fromBase64(parts[2]));
      final document = jsonDecode(plain) as Map<String, dynamic>;
      expect(document['version'], 5);
      expect(document['user']['nickname'], 'Interop Ada');
      expect(document['nostr']['priv_hex'], privateHex);
      expect(document['badges'], hasLength(2));
      await tester.runAsync(() async {
        await output.create(recursive: true);
        await File('${output.path}/flutter-v5.21bkp').writeAsBytes(bytes);
        await File('${output.path}/flutter-v5.json').writeAsString(plain);
        await File('${output.path}/provenance.json').writeAsString(
          const JsonEncoder.withIndent('  ').convert({
            'producer':
                'BackupService.createBackup via Flutter widget test; production source unchanged',
            'password': password,
            'privateKey': 'PUBLIC synthetic scalar 1; never fund or reuse',
            'backupServiceSha256': sha256
                .convert(
                  await File('lib/services/backup_service.dart').readAsBytes(),
                )
                .toString(),
            'flutterFileSha256': sha256.convert(bytes).toString(),
            'dartVersion': Platform.version,
          }),
        );
      });
    },
  );

  testWidgets(
    'real Flutter importer reads the native export and restores its key and wallet',
    (tester) async {
      final context = await mount(tester);
      late Future<bool> pending;
      await tester.runAsync(() async {
        pending = IOOverrides.runZoned(
          () => BackupService.restoreBackup(context),
          socketConnect:
              (host, port, {sourceAddress, sourcePort = 0, timeout}) =>
                  Future<Socket>.error(
                    const SocketException(
                      'Network disabled for synthetic backup interoperability',
                    ),
                  ),
          socketStartConnect: (host, port, {sourceAddress, sourcePort = 0}) =>
              Future<ConnectionTask<Socket>>.error(
                const SocketException(
                  'Network disabled for synthetic backup interoperability',
                ),
              ),
        );
      });
      await enterPassword(tester, exporting: false);
      expect(
        await tester.runAsync(
          () => pending.timeout(const Duration(seconds: 30)),
        ),
        isTrue,
      );
      final user = await UserProfile.load();
      expect(user.nickname, 'Interop Ada');
      expect(user.fullName, 'Änne 😀');
      expect(user.favoriteMeetupIds, ['Aschaffenburg', 'Würzburg']);
      expect(await SecureKeyStore.getPrivHex(), privateHex);
      final badges = await MeetupBadge.loadBadges();
      expect(badges.map((b) => b.id).toSet(), {
        'interop-meetup',
        'interop-event',
      });
      expect(
        badges.singleWhere((b) => b.id == 'interop-event').isEvent,
        isTrue,
      );
      expect(
        badges.singleWhere((b) => b.id == 'interop-event').presenceVerified,
        isFalse,
      );
    },
  );
}
