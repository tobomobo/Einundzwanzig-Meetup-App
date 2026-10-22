import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:einundzwanzig_meetup_app/services/relay_socket.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nostr/nostr.dart';

void main() {
  final key = Keychain.generate();

  Map<String, dynamic> signed(String content) => Event.from(
    kind: 1,
    tags: [
      ['t', 'performance-test'],
    ],
    content: content,
    privkey: key.private,
  ).toJson();

  test(
    'background verification snapshots input and rejects altered fields',
    () async {
      final original = signed('snapshot');
      final mutable = jsonDecode(jsonEncode(original)) as Map<String, dynamic>;
      final checking = RelaySocket.verifyEventInBackground(mutable);
      mutable['content'] = 'changed while worker starts';
      expect(await checking, isTrue);
      expect(RelaySocket.verifiedEvent(original), isNotNull);
      expect(RelaySocket.verifiedEvent(mutable), isNull);

      for (final change in <String, dynamic>{
        'id': 'ab' * 32,
        'pubkey': Keychain.generate().public,
        'sig': '00' * 64,
        'kind': 2,
        'created_at': 0,
        'tags': [
          ['t', 'forged'],
        ],
      }.entries) {
        final changed = {...original, change.key: change.value};
        expect(
          await RelaySocket.verifyEventInBackground(changed),
          isFalse,
          reason: change.key,
        );
        expect(RelaySocket.verifiedEvent(changed), isNull, reason: change.key);
      }
    },
  );

  test(
    'concurrent relays keep EVENT before EOSE and retain rejection counting',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final serverSockets = <WebSocket>[];
      server.transform(WebSocketTransformer()).listen((socket) {
        serverSockets.add(socket);
        socket.listen((request) {
          final event = signed('socket-${serverSockets.indexOf(socket)}');
          socket.add(jsonEncode(['EVENT', 'q', event]));
          socket.add(
            jsonEncode([
              'EVENT',
              'q',
              {...event, 'content': 'forged'},
            ]),
          );
          socket.add(jsonEncode(['EOSE', 'q']));
        });
      });
      final clients = <RelaySocket>[];
      try {
        await Future.wait(
          List.generate(3, (_) async {
            final client = await RelaySocket.connect(
              'ws://127.0.0.1:${server.port}',
            );
            clients.add(client);
            final types = <String>[];
            final done = Completer<void>();
            final subscription = client.listen((data) {
              final message = jsonDecode(data as String) as List;
              types.add(message[0] as String);
              if (message[0] == 'EVENT') {
                final before = RelaySocket.rejectedEventCount;
                final raw = RelaySocket.verifiedEvent(message[2]);
                if (types.length == 1) {
                  expect(raw, isNotNull);
                } else {
                  expect(raw, isNull);
                  expect(RelaySocket.rejectedEventCount, before + 1);
                }
              } else if (message[0] == 'EOSE') {
                done.complete();
              }
            }, onError: (Object e, StackTrace s) => done.completeError(e, s));
            client.add(
              jsonEncode([
                'REQ',
                'q',
                {
                  'kinds': [1],
                },
              ]),
            );
            await done.future.timeout(const Duration(seconds: 10));
            expect(types, ['EVENT', 'EVENT', 'EOSE']);
            await subscription.cancel();
          }),
        );
      } finally {
        for (final client in clients) {
          await client.close();
        }
        for (final socket in serverSockets) {
          await socket.close();
        }
        await server.close(force: true);
      }
    },
  );

  test(
    'stream getter also verifies events and passes control frames unchanged',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      WebSocket? socket;
      server.transform(WebSocketTransformer()).listen((connected) {
        socket = connected;
        connected.listen((_) {
          connected.add('not JSON');
          connected.add(jsonEncode(['AUTH', 'challenge']));
          connected.add(jsonEncode(['EVENT', 'q', signed('stream getter')]));
          connected.add(jsonEncode(['EOSE', 'q']));
        });
      });
      final client = await RelaySocket.connect('ws://127.0.0.1:${server.port}');
      try {
        final frames = client.stream.take(4).toList();
        client.add('start');
        final received = await frames.timeout(const Duration(seconds: 10));
        expect(received[0], 'not JSON');
        expect(jsonDecode(received[1] as String), ['AUTH', 'challenge']);
        expect(
          RelaySocket.verifiedEvent(
            (jsonDecode(received[2] as String) as List)[2],
          ),
          isNotNull,
        );
        expect(jsonDecode(received[3] as String), ['EOSE', 'q']);
      } finally {
        await client.close();
        await socket?.close();
        await server.close(force: true);
      }
    },
  );

  test(
    'duplicates reach each subscription and live events continue after EOSE',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      WebSocket? socket;
      final stored = signed('shared stored event');
      final live = signed('new event after EOSE');
      server.transform(WebSocketTransformer()).listen((connected) {
        socket = connected;
        var requests = 0;
        connected.listen((_) {
          if (++requests != 2) return;
          for (final subscription in ['a', 'b']) {
            connected.add(jsonEncode(['EVENT', subscription, stored]));
            connected.add(jsonEncode(['EOSE', subscription]));
          }
          connected.add(jsonEncode(['EVENT', 'a', live]));
        });
      });
      final client = await RelaySocket.connect('ws://127.0.0.1:${server.port}');
      try {
        final frames = client.stream.take(5).toList();
        for (final subscription in ['a', 'b']) {
          client.add(
            jsonEncode([
              'REQ',
              subscription,
              {
                'kinds': [1],
              },
            ]),
          );
        }
        final received = (await frames.timeout(
          const Duration(seconds: 10),
        )).map((data) => jsonDecode(data as String) as List).toList();
        expect(received.map((frame) => frame.take(2).toList()), [
          ['EVENT', 'a'],
          ['EOSE', 'a'],
          ['EVENT', 'b'],
          ['EOSE', 'b'],
          ['EVENT', 'a'],
        ]);
        expect((received[0][2] as Map)['id'], stored['id']);
        expect((received[2][2] as Map)['id'], stored['id']);
        expect((received[4][2] as Map)['id'], live['id']);
      } finally {
        await client.close();
        await socket?.close();
        await server.close(force: true);
      }
    },
  );
}
