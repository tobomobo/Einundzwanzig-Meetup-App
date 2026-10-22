import 'dart:convert';

import 'relay_event_verifier.dart';

final _verifier = RelayEventVerifier();

// Web has no Dart isolates. Keep the same validation and bounded cache.
Future<bool> verifyInBackground(String encoded) async =>
    _verifier.verify(jsonDecode(encoded) as Map<String, dynamic>);
