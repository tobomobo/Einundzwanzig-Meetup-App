import 'dart:async';
import 'dart:convert';
import 'dart:isolate';

import 'relay_event_verifier.dart';

final _worker = _VerificationWorker();

Future<bool> verifyInBackground(String encoded) => _worker.verify(encoded);

/// One reusable worker for all relays, rather than spawning an isolate for
/// every event. Each socket applies backpressure and preserves EVENT/EOSE order.
class _VerificationWorker {
  Future<SendPort>? _starting;
  Isolate? _isolate;
  ReceivePort? _replies;
  Timer? _idle;
  int _nextId = 0;
  final _pending = <int, Completer<bool>>{};

  Future<SendPort> _start() async {
    final ready = Completer<SendPort>();
    final replies = ReceivePort();
    _replies = replies;
    replies.listen((message) {
      if (message is SendPort) {
        ready.complete(message);
      } else if (message is List &&
          message.length == 2 &&
          message[0] is int &&
          message[1] is bool) {
        _pending.remove(message[0])?.complete(message[1] as bool);
      } else {
        // A worker crash must fail the stream, never fall back to unchecked
        // events or leave a caller waiting indefinitely.
        final error = StateError('Relay verification worker stopped');
        if (!ready.isCompleted) ready.completeError(error);
        _stop(error);
      }
    });
    try {
      _isolate = await Isolate.spawn(
        _run,
        replies.sendPort,
        onError: replies.sendPort,
        onExit: replies.sendPort,
        debugName: 'nostr-verification',
      );
    } catch (error, stack) {
      if (!ready.isCompleted) ready.completeError(error, stack);
      _stop(error);
    }
    return ready.future;
  }

  Future<bool> verify(String encoded) async {
    _idle?.cancel();
    final port = await (_starting ??= _start());
    final id = _nextId++;
    final result = Completer<bool>();
    _pending[id] = result;
    port.send([id, encoded]);
    try {
      return await result.future;
    } finally {
      if (_pending.isEmpty) {
        _idle = Timer(const Duration(seconds: 30), _stop);
      }
    }
  }

  void _stop([Object? error]) {
    _idle?.cancel();
    _isolate?.kill(priority: Isolate.immediate);
    _isolate = null;
    _replies?.close();
    _replies = null;
    _starting = null;
    for (final result in _pending.values) {
      result.completeError(error ?? StateError('Relay verification stopped'));
    }
    _pending.clear();
  }
}

void _run(SendPort replies) {
  final requests = ReceivePort();
  final verifier = RelayEventVerifier();
  replies.send(requests.sendPort);
  requests.listen((dynamic message) {
    final request = message as List;
    final raw = jsonDecode(request[1] as String) as Map<String, dynamic>;
    replies.send([request[0], verifier.verify(raw)]);
  });
}
