// Run from the project root:
// dart run tool/benchmark_relay_verification.dart
// For AOT timings: dart compile exe tool/benchmark_relay_verification.dart -o /tmp/relay-benchmark
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:nostr/nostr.dart';

import 'package:einundzwanzig_meetup_app/services/relay_event_verifier.dart';
import 'package:einundzwanzig_meetup_app/services/relay_verification_worker_io.dart'
    as worker;

Future<void> main() async {
  final key = Keychain.generate();
  final events = List.generate(
    100,
    (i) => Event.from(
      kind: 1,
      tags: [
        ['t', 'benchmark'],
      ],
      content: 'Relay message $i',
      privkey: key.private,
    ).toJson(),
  );
  final encoded = events.map(jsonEncode).toList();

  Future<void> measure(String label, Future<void> Function() action) async {
    final clock = Stopwatch()..start();
    var lastTick = 0;
    var maxGap = 0;
    var ticks = 0;
    void sample() {
      final now = clock.elapsedMicroseconds;
      final gap = now - lastTick;
      if (gap > maxGap) maxGap = gap;
      lastTick = now;
      ticks++;
    }

    final timer = Timer.periodic(
      const Duration(milliseconds: 1),
      (_) => sample(),
    );
    await action();
    sample();
    timer.cancel();
    stdout.writeln(
      '$label: ${clock.elapsedMilliseconds} ms total, '
      '${(maxGap / 1000).toStringAsFixed(2)} ms max main-isolate scheduling gap, '
      '$ticks samples',
    );
  }

  await measure('Synchronous (100 unique events)', () async {
    final verifier = RelayEventVerifier();
    for (final event in events) {
      if (!verifier.verify(event)) {
        throw StateError('Invalid fixture');
      }
    }
  });
  await measure('Worker (same 100 unique events, including startup)', () async {
    for (final event in encoded) {
      if (!await worker.verifyInBackground(event)) {
        throw StateError('Invalid fixture');
      }
    }
  });
  await measure('Worker (duplicates from another relay)', () async {
    for (final event in encoded) {
      if (!await worker.verifyInBackground(event)) {
        throw StateError('Invalid fixture');
      }
    }
  });
}
