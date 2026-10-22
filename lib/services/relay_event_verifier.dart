import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:nostr/nostr.dart';

/// Cache all verification fields, never just the attacker-controlled event ID.
/// Relay JSON key order and unsigned extensions do not change a Nostr event.
/// A changed content, tag, author, ID or signature must be checked again.
class RelayEventVerifier {
  static const maxEntries = 256;
  final _results = <String, bool>{};

  /// A stable snapshot of the NIP-01 fields, including the supplied ID and sig.
  /// Keep original types and tag order: coercing them could hide tampering.
  String encodeEvent(Map<String, dynamic> raw) => jsonEncode({
    for (final field in const [
      'id',
      'pubkey',
      'created_at',
      'kind',
      'tags',
      'content',
      'sig',
    ])
      if (raw.containsKey(field)) field: raw[field],
  });

  String fingerprint(Map<String, dynamic> raw) =>
      fingerprintEncoded(encodeEvent(raw));

  String fingerprintEncoded(String encoded) =>
      sha256.convert(utf8.encode(encoded)).toString();

  bool? cached(String key) {
    final known = _results.remove(key);
    if (known != null) _results[key] = known;
    return known;
  }

  void remember(String key, bool valid) {
    _results.remove(key);
    if (_results.length >= maxEntries) _results.remove(_results.keys.first);
    _results[key] = valid;
  }

  bool verify(Map<String, dynamic> raw) {
    final key = fingerprint(raw);
    final known = cached(key);
    if (known != null) return known;
    var valid = false;
    try {
      final event = Event(
        raw['id'] ?? '',
        raw['pubkey'] ?? '',
        raw['created_at'] ?? 0,
        raw['kind'] ?? 0,
        (raw['tags'] as List<dynamic>?)
                ?.map(
                  (t) => (t as List<dynamic>).map((e) => e.toString()).toList(),
                )
                .toList() ??
            [],
        raw['content'] ?? '',
        raw['sig'] ?? '',
        verify: false,
      );
      valid = event.isValid();
    } catch (_) {
      valid = false;
    }
    remember(key, valid);
    return valid;
  }
}
