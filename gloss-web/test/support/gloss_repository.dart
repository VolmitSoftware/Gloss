library;

import 'dart:io';

final String glossRepositoryPath =
    Platform.environment['GLOSS_REPOSITORY'] ?? '..';

String glossRepositoryFilePath(String relativePath) =>
    '$glossRepositoryPath/$relativePath';

String readGlossText(File file) =>
    file.readAsStringSync().replaceAll('\r\n', '\n');
