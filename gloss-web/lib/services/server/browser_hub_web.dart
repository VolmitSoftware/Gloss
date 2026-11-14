library;

import 'package:web/web.dart' as web;

import 'browser_storage.dart';
import 'browser_transports.dart';
import 'page_origin_web.dart';
import 'server_hub.dart';
import 'server_profiles.dart';

ServerHub createBrowserHub({
  Future<void> Function(String serverId)? onForget,
}) => ServerHub(
  profiles: ServerProfileStore(
    local: BrowserKeyValueStore.local(),
    session: BrowserKeyValueStore.session(),
  ),
  open: openBrowserTransport,
  pageOrigin: currentPageOrigin,
  onForget: onForget,
);

String browserUserAgent() => web.window.navigator.userAgent;

String browserOrigin() => web.window.location.origin;

void listenBrowserWake(void Function() wake) => onBrowserWake(wake);
