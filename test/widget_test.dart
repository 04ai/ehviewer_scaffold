import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:ehviewer_scaffold/main.dart';

void main() {
  testWidgets('App boots and renders the gallery list page', (tester) async {
    SharedPreferences.setMockInitialValues({});
    await tester.pumpWidget(const ProviderScope(child: MyApp()));
    await tester.pump();

    expect(find.byType(GalleryListPage), findsOneWidget);

    // Tear the tree down so the app's periodic timers are cancelled.
    await tester.pumpWidget(const SizedBox());
  });
}
