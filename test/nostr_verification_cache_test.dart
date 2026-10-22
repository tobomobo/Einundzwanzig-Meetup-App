import 'package:einundzwanzig_meetup_app/services/relay_event_verifier.dart';
import 'package:einundzwanzig_meetup_app/services/relay_socket.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nostr/nostr.dart';

void main() {
  test(
    'relay JSON ordering and extensions share verification, not delivery',
    () async {
      final key = Keychain.generate();
      final event = Event.from(
        kind: 1,
        tags: [],
        content: 'concurrent relays',
        privkey: key.private,
      ).toJson();
      final otherRelay = <String, dynamic>{
        'relay_extension': {'score': 1},
        for (final field in event.keys.toList().reversed) field: event[field],
      };
      final first = RelaySocket.verifyEventInBackground(event);
      final second = RelaySocket.verifyEventInBackground(otherRelay);
      expect(
        identical(first, second),
        isTrue,
        reason: 'Concurrent copies should use one verification job.',
      );
      expect(await first, isTrue);
      expect(RelaySocket.verifiedEvent(otherRelay), same(otherRelay));
      expect(
        RelaySocket.verifiedEvent({...otherRelay, 'content': 'forged'}),
        isNull,
      );
    },
  );

  test(
    'verification cache retains hot events when cold events exceed the bound',
    () {
      final verifier = RelayEventVerifier();
      verifier.remember('hot', true);
      for (var i = 0; i < RelayEventVerifier.maxEntries - 1; i++) {
        verifier.remember('cold-$i', false);
      }
      expect(verifier.cached('hot'), isTrue);
      verifier.remember('new', false);
      expect(verifier.cached('hot'), isTrue);
      expect(verifier.cached('cold-0'), isNull);
    },
  );

  test(
    'ID, signature, types and tag ordering remain part of cache identity',
    () {
      final verifier = RelayEventVerifier();
      final raw = <String, dynamic>{
        'id': 'id',
        'pubkey': 'author',
        'created_at': 1,
        'kind': 1,
        'content': '',
        'sig': 'signature',
        'tags': [
          ['t', 'a'],
          ['t', 'b'],
        ],
      };
      final key = verifier.fingerprint(raw);
      for (final changed in [
        {...raw, 'id': 'other'},
        {...raw, 'sig': 'other'},
        {...raw, 'kind': '1'},
        {
          ...raw,
          'tags': [
            ['t', 'b'],
            ['t', 'a'],
          ],
        },
      ]) {
        expect(verifier.fingerprint(changed), isNot(key));
      }
    },
  );
}
