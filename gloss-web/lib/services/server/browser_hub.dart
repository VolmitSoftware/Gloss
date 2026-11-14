/// The hub wired to this browser: localStorage and sessionStorage for the
/// saved servers and tokens, fetch, WebSocket and the relay socket for the
/// paths.
library;

export 'browser_hub_stub.dart'
    if (dart.library.js_interop) 'browser_hub_web.dart';
