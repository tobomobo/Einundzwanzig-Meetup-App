import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:einundzwanzig_meetup_app/services/calendar_event_service.dart';
import 'package:einundzwanzig_meetup_app/services/relay_config.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nostr/nostr.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _Relay {
  final HttpServer server;
  final sockets = <WebSocket>[];
  List<Map<String, dynamic>> events = [];
  Future<void> Function()? beforeReply;
  int requests = 0;

  _Relay(this.server) {
    server.transform(WebSocketTransformer()).listen((socket) {
      sockets.add(socket);
      socket.listen((data) async {
        final request = jsonDecode(data as String) as List;
        if (request[0] != 'REQ') return;
        requests++;
        await beforeReply?.call();
        // EOSE belongs to a subscription, not the entire connection.
        socket.add(jsonEncode(['EOSE', 'different-subscription']));
        for (final event in events) {
          socket.add(jsonEncode(['EVENT', request[1], event]));
        }
        socket.add(jsonEncode(['EOSE', request[1]]));
      });
    });
  }

  String get url => 'ws://127.0.0.1:${server.port}';
  static Future<_Relay> open() async =>
      _Relay(await HttpServer.bind(InternetAddress.loopbackIPv4, 0));

  Future<void> close() async {
    for (final socket in sockets) {
      await socket.close();
    }
    await server.close(force: true);
  }
}

void main() {
  final key = Keychain.generate();
  final now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
  late List<_Relay> relays;

  Map<String, dynamic> event(
    int version, {
    String status = '',
    String content = '',
  }) => Event.from(
    kind: kTimeEventKind,
    createdAt: now + version,
    tags: [
      ['d', 'same-address'],
      ['title', 'Meetup'],
      ['start', '${now + 86400}'],
      ['client', 'einundzwanzig-meetup-app'],
      if (status.isNotEmpty) ['status', status],
    ],
    content: content,
    privkey: key.private,
  ).toJson();

  setUp(() async {
    relays = [await _Relay.open(), await _Relay.open()];
    SharedPreferences.setMockInitialValues({
      'disabled_default_relays': RelayConfig.defaultRelays,
      'custom_relays': relays.map((r) => r.url).toList(),
    });
  });
  tearDown(() async {
    for (final relay in relays) {
      await relay.close();
    }
  });

  test(
    'a cancellation supersedes old active versions across relays and within one relay',
    () async {
      relays[0].events = [event(-2), event(-1, status: 'cancelled')];
      relays[1].events = [event(-3)];
      expect(await CalendarEventService.fetchEvents(worldwide: true), isEmpty);
      // Reversing which relay owns the cancellation must not change the result.
      final first = relays[0].events;
      relays[0].events = relays[1].events;
      relays[1].events = first;
      expect(await CalendarEventService.fetchEvents(worldwide: true), isEmpty);
      expect(
        relays.every((r) => r.requests == 2),
        isTrue,
        reason: 'A later refresh must query again, not return an old result.',
      );
    },
  );

  test('a newer active revision supersedes an older cancellation', () async {
    relays[0].events = [event(-1)];
    relays[1].events = [event(-2, status: 'cancelled')];
    final found = await CalendarEventService.fetchEvents(worldwide: true);
    expect(found.single.id, relays[0].events.single['id']);
    expect(found.single.createdAt, now - 1);
  });

  test(
    'a forged newer cancellation cannot suppress a signed active event',
    () async {
      final forged = event(-1, status: 'cancelled')..['content'] = 'tampered';
      relays[0].events = [event(-2)];
      relays[1].events = [forged];
      final found = await CalendarEventService.fetchEvents(worldwide: true);
      expect(found.single.id, relays[0].events.single['id']);
    },
  );

  test(
    'equal timestamps select the lowest event ID regardless of delivery order',
    () async {
      final versions = [event(-1, content: 'a'), event(-1, content: 'b')]
        ..sort((a, b) => (a['id'] as String).compareTo(b['id'] as String));
      relays[0].events = [versions.last, versions.first];
      relays[1].events = [versions.last];
      expect(
        (await CalendarEventService.fetchEvents(worldwide: true)).single.id,
        versions.first['id'],
      );
    },
  );

  test(
    'parallel relay queries share concurrent requests without merging different limits',
    () async {
      final bothStarted = Completer<void>();
      var started = 0;
      for (final relay in relays) {
        relay.events = [event(-1)];
        relay.beforeReply = () async {
          if (++started == 2) bothStarted.complete();
          await bothStarted.future;
        };
      }
      final results = await Future.wait([
        CalendarEventService.fetchEvents(worldwide: true),
        CalendarEventService.fetchEvents(worldwide: true),
      ]).timeout(const Duration(seconds: 5));
      expect(results.every((r) => r.length == 1), isTrue);
      results.first.clear();
      expect(
        results.last,
        hasLength(1),
        reason: 'Callers own separate result lists.',
      );
      expect(relays.every((r) => r.requests == 1), isTrue);
      for (final relay in relays) {
        relay.beforeReply = null;
      }
      await Future.wait([
        CalendarEventService.fetchEvents(worldwide: true, limit: 10),
        CalendarEventService.fetchEvents(worldwide: true, limit: 20),
      ]);
      expect(relays.every((r) => r.requests == 3), isTrue);
    },
  );
}
