import 'dart:convert';

import 'package:gloss_editor/logic/stage_fit.dart';
import 'package:gloss_editor/logic/viewport_math.dart';
import 'package:gloss_editor/state/editor_store.dart';
import 'package:gloss_editor/state/workspace.dart';
import 'package:test/test.dart';

EditorStore _store() {
  final Map<String, String> values = <String, String>{};
  return EditorStore(
    workspace: Workspace(
      read: (String key) => values[key],
      write: (String key, String value) {
        values[key] = value;
        return true;
      },
    ),
    autosaveDelay: Duration.zero,
  );
}

void main() {
  group('StageFitMemory', () {
    test('fits the first document it frames', () {
      final StageFitMemory<int> memory = StageFitMemory<int>();
      expect(memory.next(docId: 'a', fitRequest: 0), StageCamera.fit);
      expect(memory.next(docId: 'a', fitRequest: 0), StageCamera.keep);
    });

    test('fits every document it switches to until one is set by hand', () {
      final StageFitMemory<int> memory = StageFitMemory<int>();
      memory.next(docId: 'small', fitRequest: 0);
      expect(memory.next(docId: 'big', fitRequest: 0), StageCamera.fit);
      expect(memory.next(docId: 'small', fitRequest: 0), StageCamera.fit);
    });

    test('refits on a resize until the camera is set by hand', () {
      final StageFitMemory<int> memory = StageFitMemory<int>();
      memory.next(docId: 'a', fitRequest: 0);
      expect(
        memory.next(docId: 'a', fitRequest: 0, resized: true),
        StageCamera.fit,
      );
      memory.moved(250);
      expect(
        memory.next(docId: 'a', fitRequest: 0, resized: true),
        StageCamera.keep,
      );
    });

    test('keeps a hand-set camera per document and restores it', () {
      final StageFitMemory<int> memory = StageFitMemory<int>();
      memory.next(docId: 'a', fitRequest: 0);
      memory.moved(300);
      expect(memory.next(docId: 'b', fitRequest: 0), StageCamera.fit);
      expect(memory.handView, isNull);
      expect(memory.next(docId: 'a', fitRequest: 0), StageCamera.restore);
      expect(memory.handView, 300);
    });

    test('an import forgets the hand-set camera and fits', () {
      final StageFitMemory<int> memory = StageFitMemory<int>();
      memory.next(docId: 'a', fitRequest: 0);
      memory.moved(300);
      expect(memory.next(docId: 'a', fitRequest: 1), StageCamera.fit);
      expect(memory.handView, isNull);
      expect(
        memory.next(docId: 'a', fitRequest: 1, resized: true),
        StageCamera.fit,
      );
    });

    test('fit requests count per document', () {
      final StageFitMemory<int> memory = StageFitMemory<int>();
      memory.next(docId: 'a', fitRequest: 0);
      memory.moved(300);
      expect(memory.next(docId: 'b', fitRequest: 3), StageCamera.fit);
      expect(memory.next(docId: 'a', fitRequest: 0), StageCamera.restore);
      expect(memory.handView, 300);
    });

    test('Reset view forgets the hand-set camera', () {
      final StageFitMemory<int> memory = StageFitMemory<int>();
      memory.next(docId: 'a', fitRequest: 0);
      memory.moved(300);
      memory.reset();
      expect(memory.handSet, isFalse);
      expect(
        memory.next(docId: 'a', fitRequest: 0, resized: true),
        StageCamera.fit,
      );
    });

    test('hand views live in the shared map, so a new stage restores them', () {
      final Map<String, int> cameras = <String, int>{};
      final StageFitMemory<int> first = StageFitMemory<int>(cameras: cameras);
      first.next(docId: 'a', fitRequest: 0);
      first.moved(300);
      final StageFitMemory<int> second = StageFitMemory<int>(cameras: cameras);
      expect(second.next(docId: 'a', fitRequest: 0), StageCamera.restore);
      expect(second.handView, 300);
    });

    test('no document keeps the camera', () {
      final StageFitMemory<int> memory = StageFitMemory<int>();
      expect(memory.next(docId: null, fitRequest: 0), StageCamera.keep);
      memory.moved(5);
      expect(memory.handSet, isFalse);
    });
  });

  group('EditorStore fit requests', () {
    test('an import asks the stages to frame the document again', () {
      final EditorStore store = _store();
      store.newDocument(name: 'shop');
      final String id = store.workspace.activeId!;
      store.canvasCameras[id] = const CanvasTransform(zoom: 3);
      expect(store.canvasFitRequest, 0);
      store.importJson(
        'shop.json',
        jsonEncode(<String, Object?>{'id': 'shop', 'components': <Object?>[]}),
      );
      expect(store.workspace.activeId, id);
      expect(store.canvasFitRequest, 1);
      expect(store.canvasCameras, isNot(contains(id)));
    });

    test('fit requests and cameras belong to one document', () {
      final EditorStore store = _store();
      store.newDocument(name: 'first');
      final String first = store.workspace.activeId!;
      store.canvasCameras[first] = const CanvasTransform(zoom: 2);
      store.newDocument(name: 'second');
      store.requestCanvasFit();
      expect(store.canvasFitRequest, 1);
      expect(store.canvasCameras[first]?.zoom, 2);
      store.openDocument(first);
      expect(store.canvasFitRequest, 0);
    });
  });

  group('stageContentBox', () {
    test('writes the content edges as fractions of the surface', () {
      expect(
        stageContentBox(
          left: 100,
          top: 50,
          right: 700,
          bottom: 550,
          width: 800,
          height: 600,
        ),
        '0.1250 0.0833 0.8750 0.9167',
      );
    });

    test('content past an edge reads below 0 or above 1', () {
      expect(
        stageContentBox(
          left: -400,
          top: -60,
          right: 1200,
          bottom: 600,
          width: 800,
          height: 600,
        ),
        '-0.5000 -0.1000 1.5000 1.0000',
      );
    });

    test('a surface with no area writes nothing', () {
      expect(
        stageContentBox(
          left: 0,
          top: 0,
          right: 1,
          bottom: 1,
          width: 0,
          height: 600,
        ),
        isNull,
      );
    });
  });
}
