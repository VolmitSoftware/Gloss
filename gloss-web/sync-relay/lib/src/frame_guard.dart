import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

/// Reads the frame headers of a client-to-server WebSocket byte stream and
/// refuses a message as soon as its announced length passes [maxMessageBytes],
/// before the payload arrives. A message split into continuation frames counts
/// as a whole. Control frames are skipped.
final class FrameSizeGuard {
  final int maxMessageBytes;
  final List<int> _header = <int>[];
  int _skip = 0;
  int _message = 0;
  bool _refused = false;

  FrameSizeGuard(this.maxMessageBytes);

  bool get refused => _refused;

  /// Takes the next bytes of the stream. Returns false once a message is over
  /// the limit; every later call returns false too.
  bool accept(List<int> bytes) {
    int index = 0;
    while (!_refused && index < bytes.length) {
      if (_skip > 0) {
        final int taken = _skip < bytes.length - index
            ? _skip
            : bytes.length - index;
        _skip -= taken;
        index += taken;
        continue;
      }
      _header.add(bytes[index++]);
      final int? length = _headerLength();
      if (length == null || _header.length < length) continue;
      _frame();
      _header.clear();
    }
    return !_refused;
  }

  /// The full header length once its first two bytes are in, else null.
  int? _headerLength() {
    if (_header.length < 2) return null;
    final int short = _header[1] & 0x7f;
    final int extended = short == 126 ? 2 : (short == 127 ? 8 : 0);
    final int mask = (_header[1] & 0x80) != 0 ? 4 : 0;
    return 2 + extended + mask;
  }

  void _frame() {
    final int opcode = _header[0] & 0x0f;
    final bool fin = (_header[0] & 0x80) != 0;
    final int short = _header[1] & 0x7f;
    int length = short;
    if (short == 126) {
      length = (_header[2] << 8) | _header[3];
    } else if (short == 127) {
      length = 0;
      for (int offset = 2; offset < 10; offset++) {
        if (length > maxMessageBytes) break;
        length = (length << 8) | _header[offset];
      }
    }
    if (opcode >= 0x8) {
      _skip = length;
      return;
    }
    _message = opcode == 0 ? _message + length : length;
    if (_message > maxMessageBytes) {
      _refused = true;
      return;
    }
    _skip = length;
    if (fin) _message = 0;
  }
}

/// A [Socket] whose incoming bytes pass a [FrameSizeGuard]. A message over the
/// limit destroys the socket instead of reaching the WebSocket's buffer.
final class GuardedSocket extends StreamView<Uint8List> implements Socket {
  final Socket _socket;

  GuardedSocket._(this._socket, Stream<Uint8List> guarded) : super(guarded);

  factory GuardedSocket(Socket socket, int maxMessageBytes) {
    final FrameSizeGuard guard = FrameSizeGuard(maxMessageBytes);
    final Stream<Uint8List> guarded = socket.transform(
      StreamTransformer<Uint8List, Uint8List>.fromHandlers(
        handleData: (Uint8List data, EventSink<Uint8List> sink) {
          if (guard.refused) return;
          if (guard.accept(data)) {
            sink.add(data);
            return;
          }
          socket.destroy();
          sink.close();
        },
        handleDone: (EventSink<Uint8List> sink) {
          if (!guard.refused) sink.close();
        },
      ),
    );
    return GuardedSocket._(socket, guarded);
  }

  @override
  Encoding get encoding => _socket.encoding;

  @override
  set encoding(Encoding value) => _socket.encoding = value;

  @override
  void add(List<int> data) => _socket.add(data);

  @override
  void addError(Object error, [StackTrace? stackTrace]) =>
      _socket.addError(error, stackTrace);

  @override
  Future<void> addStream(Stream<List<int>> stream) => _socket.addStream(stream);

  @override
  Future<void> close() => _socket.close();

  @override
  Future<void> get done => _socket.done;

  @override
  Future<void> flush() => _socket.flush();

  @override
  void write(Object? object) => _socket.write(object);

  @override
  void writeAll(Iterable<Object?> objects, [String separator = '']) =>
      _socket.writeAll(objects, separator);

  @override
  void writeCharCode(int charCode) => _socket.writeCharCode(charCode);

  @override
  void writeln([Object? object = '']) => _socket.writeln(object);

  @override
  void destroy() => _socket.destroy();

  @override
  bool setOption(SocketOption option, bool enabled) =>
      _socket.setOption(option, enabled);

  @override
  Uint8List getRawOption(RawSocketOption option) =>
      _socket.getRawOption(option);

  @override
  void setRawOption(RawSocketOption option) => _socket.setRawOption(option);

  @override
  int get port => _socket.port;

  @override
  int get remotePort => _socket.remotePort;

  @override
  InternetAddress get address => _socket.address;

  @override
  InternetAddress get remoteAddress => _socket.remoteAddress;
}
