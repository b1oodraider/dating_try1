import 'package:dating_mobile/features/chat/swipe_to_reply.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  Future<int> swipe(WidgetTester tester, double dx) async {
    var replies = 0;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Center(child: SwipeToReply(onReply: () => replies++, child: const Text('Привет'))),
      ),
    ));
    await tester.drag(find.text('Привет'), Offset(dx, 0));
    await tester.pumpAndSettle();
    return replies;
  }

  testWidgets('a long swipe either way answers the message, a short one does not', (tester) async {
    expect(await swipe(tester, -120), 1);
    expect(await swipe(tester, 120), 1);
    expect(await swipe(tester, 30), 0);
  });

  testWidgets('the message slides back after the swipe', (tester) async {
    await swipe(tester, -120);
    expect(tester.getCenter(find.text('Привет')).dx, tester.getCenter(find.byType(Scaffold)).dx);
  });
}
