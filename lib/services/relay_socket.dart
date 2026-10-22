// ============================================
// RELAY SOCKET — plattformübergreifende WebSocket-Verbindung zu Nostr-Relays
// ============================================
// Ersetzt `dart:io` WebSocket, das im Browser NICHT funktioniert: dort wirft
// WebSocket.connect() intern `Unsupported operation: Platform._version`.
// Folge war, dass in der Web-Version JEDE Relay-Verbindung fehlschlug —
// Bürgschaften, Organisator-Registry, Profilbilder, Reputations-Publishing,
// Nostr-Kalender und Zap-Prüfung waren damit ohne Funktion.
//
// web_socket_channel (Dart-Team, tools.dart.dev) bringt Implementierungen für
// dart:io UND Browser mit und wählt sie automatisch passend zur Plattform.
//
// Die API ist absichtlich deckungsgleich mit der bisher genutzten Teilmenge
// von dart:io WebSocket (connect / listen / add / close), damit die
// Aufrufstellen unverändert bleiben und der Umbau nachvollziehbar ist.
// ============================================

import 'dart:async';
import 'dart:convert';

import 'package:web_socket_channel/web_socket_channel.dart';

import 'app_logger.dart';
import 'relay_event_verifier.dart';
import 'relay_verification_worker_web.dart'
    if (dart.library.io) 'relay_verification_worker_io.dart'
    as worker;

class RelaySocket {
  final WebSocketChannel _channel;

  /// Obergrenze für eine Relay-Nachricht. Darüber wird der Frame verworfen,
  /// statt ihn zu parsen (Security Audit M4).
  static const int maxMessageChars = 512 * 1024;

  RelaySocket._(this._channel);

  static bool _withinLimit(dynamic data) {
    final tooBig =
        (data is String && data.length > maxMessageChars) ||
        (data is List<int> && data.length > maxMessageChars);
    if (!tooBig) return true;
    AppLogger.debug('RelaySocket', 'Frame über $maxMessageChars verworfen');
    return false;
  }

  /// Baut die Verbindung auf und wartet, bis sie steht.
  ///
  /// Wichtig für die Gleichwertigkeit zu dart:io: Dort wirft
  /// `WebSocket.connect()` bei einem Verbindungsfehler. WebSocketChannel
  /// verbindet dagegen verzögert, weshalb hier auf `ready` gewartet wird —
  /// nur so schlagen Fehler wie bisher an der Aufrufstelle auf, und ein
  /// `.timeout(...)` der Aufrufer greift weiterhin auf den Verbindungsaufbau.
  ///
  /// Scheitert `ready`, wird der Channel geschlossen — sonst bliebe bei
  /// WebSocketChannel eine halb offene Verbindung liegen (besser als die
  /// reine dart:io-Parität, wo Aufrufer-`.timeout()` den Socket ebenfalls
  /// nicht freigibt).
  static Future<RelaySocket> connect(String url) async {
    final channel = WebSocketChannel.connect(Uri.parse(url));
    try {
      await channel.ready;
      return RelaySocket._(channel);
    } catch (_) {
      // close() bewusst NICHT awaiten: nach fehlgeschlagenem Handshake kann
      // sink.close() auf manchen Plattformen haengen und connect() blockieren.
      try {
        channel.sink.close();
      } catch (_) {}
      rethrow;
    }
  }

  /// Eingehende Nachrichten. Entspricht `ws.listen(...)`.
  StreamSubscription listen(
    void Function(dynamic data) onData, {
    Function? onError,
    void Function()? onDone,
    bool? cancelOnError,
  }) => stream.listen(
    (data) {
      onData(data);
    },
    onError: onError,
    onDone: onDone,
    cancelOnError: cancelOnError,
  );

  /// Für Aufrufstellen, die den Socket direkt als Stream verwenden
  /// (`await for (final data in ws.stream.timeout(...))`).
  Stream get stream => _channel.stream.where(_withinLimit).asyncMap(_prepare);

  /// Warm the exact-payload validation cache outside Android's UI isolate.
  /// asyncMap preserves ordering: EOSE cannot overtake an unverified EVENT.
  /// Callers still apply their own subscription filters and rejection logging.
  static Future<dynamic> _prepare(dynamic data) async {
    if (data is String) {
      dynamic message;
      try {
        message = jsonDecode(data);
      } catch (_) {
        return data; // Preserve malformed-message handling at the call site.
      }
      if (message is List &&
          message.length >= 3 &&
          message[0] == 'EVENT' &&
          message[2] is Map<String, dynamic>) {
        await verifyEventInBackground(message[2] as Map<String, dynamic>);
      }
    }
    return data;
  }

  static final _verifier = RelayEventVerifier();
  static final _verifying = <String, Future<bool>>{};

  static Future<bool> verifyEventInBackground(Map<String, dynamic> raw) {
    // Snapshot before awaiting worker startup: callers may mutate their map.
    final encoded = _verifier.encodeEvent(raw);
    final key = _verifier.fingerprintEncoded(encoded);
    final known = _verifier.cached(key);
    if (known != null) return Future.value(known);
    // The same event often arrives from several relays at once. Share the
    // verification job, but still deliver every subscription's own message.
    return _verifying[key] ??= _verifyAndRemember(key, encoded);
  }

  static Future<bool> _verifyAndRemember(String key, String encoded) async {
    try {
      final valid = await worker.verifyInBackground(encoded);
      _verifier.remember(key, valid);
      return valid;
    } finally {
      _verifying.remove(key);
    }
  }

  /// Nachricht senden. Entspricht `ws.add(...)`.
  void add(String data) => _channel.sink.add(data);

  /// Verbindung schließen. Entspricht `ws.close()`.
  Future<void> close() => _channel.sink.close();

  // ===========================================================
  // ZENTRALE SIGNATURPRÜFUNG (Security Audit H2/M1)
  // ===========================================================
  //
  // Jedes Event, das aus einer ["EVENT", subId, {...}]-Nachricht in die
  // App gelangt, läuft hier durch: ID nachrechnen, Schnorr-Signatur
  // prüfen. Das belegt, dass dieses Event so signiert wurde. Es belegt
  // nicht, dass es die Antwort auf die Anfrage ist — das prüft der
  // Aufrufer mit [answersFilter] und demselben Filter, den er gesendet hat.
  //
  // Verworfene Events werden gezählt und sparsam protokolliert — nie
  // geworfen, damit ein kaputtes Relay die Verarbeitung nicht abbricht.

  static int _rejectedCount = 0;

  /// Anzahl der seit App-Start wegen ungültiger Signatur verworfenen Events.
  static int get rejectedEventCount => _rejectedCount;

  /// Liefert das Event-Objekt zurück, wenn ID und Signatur stimmen —
  /// sonst null. [raw] ist typischerweise `msg[2]` einer EVENT-Nachricht.
  static Map<String, dynamic>? verifiedEvent(
    dynamic raw, {
    String tag = 'RelaySocket',
  }) {
    if (raw is! Map<String, dynamic>) return null;
    bool ok = false;
    try {
      ok = _verifier.verify(raw);
    } catch (_) {
      ok = false;
    }
    if (ok) return raw;
    _rejectedCount++;
    // Nur jedes 1., 10., 100., … verworfene Event loggen — kein Log-Sturm.
    if (_rejectedCount == 1 || _rejectedCount % 10 == 0) {
      AppLogger.debug(
        tag,
        'Event mit ungültiger Signatur verworfen (kind ${raw['kind']}, gesamt: $_rejectedCount)',
      );
    }
    return null;
  }

  /// Ob [event] die Felder erfüllt, nach denen die Anfrage gefragt hat.
  ///
  /// [filter] ist dieselbe Map, die im REQ steht. Geprüft werden nur die
  /// Felder, die diese App setzt: `kinds`, `authors`, `since`, `until` und
  /// Tag-Filter (`#d`, `#A`, `#a`, …). `limit` ist keine Zugehörigkeit.
  static bool answersFilter(
    Map<String, dynamic> event,
    Map<String, dynamic> filter,
  ) {
    final kinds = filter['kinds'];
    if (kinds is List && !kinds.contains(event['kind'])) return false;

    final authors = filter['authors'];
    if (authors is List && !authors.contains(event['pubkey'])) return false;

    final created = event['created_at'];
    final since = filter['since'];
    final until = filter['until'];
    if (since != null || until != null) {
      if (created is! int) return false;
      if (since is int && created < since) return false;
      if (until is int && created > until) return false;
    }

    final tags = event['tags'];
    for (final entry in filter.entries) {
      final key = entry.key;
      if (!key.startsWith('#') || key.length < 2) continue;
      final wanted = entry.value;
      if (wanted is! List || wanted.isEmpty) return false;
      if (tags is! List) return false;
      final name = key.substring(1);
      var hit = false;
      for (final tag in tags) {
        if (tag is List &&
            tag.length >= 2 &&
            tag[0] == name &&
            wanted.contains(tag[1])) {
          hit = true;
          break;
        }
      }
      if (!hit) return false;
    }
    return true;
  }

  /// created_at eines ersetzbaren Events, das als „das neueste“ gelten darf.
  ///
  /// Ein Zeitstempel mehr als zehn Minuten in der Zukunft zählt nicht:
  /// sonst gewinnt ein Relay mit einem künstlich hohen Wert für immer.
  /// Die Spanne lässt Uhren zu, die etwas vorgehen.
  static int? replaceableCreatedAt(Map<String, dynamic> event) {
    final at = event['created_at'];
    if (at is! int || at < 0) return null;
    final now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    if (at > now + 600) return null;
    return at;
  }
}
