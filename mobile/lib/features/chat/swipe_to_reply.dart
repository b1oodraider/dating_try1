import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Swipe a message sideways (either way, as in Telegram or Instagram) to answer it.
class SwipeToReply extends StatefulWidget {
  const SwipeToReply({super.key, required this.child, required this.onReply});

  final Widget child;
  final VoidCallback onReply;

  @override
  State<SwipeToReply> createState() => _SwipeToReplyState();
}

class _SwipeToReplyState extends State<SwipeToReply> with SingleTickerProviderStateMixin {
  static const double _trigger = 64;
  static const double _max = 90;

  late final AnimationController _back =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 180))
        ..addListener(() => setState(() => _dx = _from * (1 - Curves.easeOut.transform(_back.value))));
  double _dx = 0;
  double _from = 0;
  bool _armed = false;

  @override
  void dispose() {
    _back.dispose();
    super.dispose();
  }

  void _update(DragUpdateDetails d) {
    _back.stop();
    setState(() => _dx = (_dx + d.delta.dx).clamp(-_max, _max));
    final armed = _dx.abs() >= _trigger;
    if (armed && !_armed) HapticFeedback.selectionClick();
    _armed = armed;
  }

  void _end(DragEndDetails _) {
    if (_armed) widget.onReply();
    _armed = false;
    _from = _dx;
    _back.forward(from: 0);
  }

  @override
  Widget build(BuildContext context) {
    final progress = (_dx.abs() / _trigger).clamp(0.0, 1.0);
    return GestureDetector(
      behavior: HitTestBehavior.translucent,
      onHorizontalDragUpdate: _update,
      onHorizontalDragEnd: _end,
      child: Stack(
        alignment: Alignment.center,
        children: [
          if (_dx != 0)
            Positioned(
              left: _dx > 0 ? 4 : null,
              right: _dx < 0 ? 4 : null,
              child: Opacity(
                opacity: progress,
                child: Transform.scale(
                  scale: 0.6 + 0.4 * progress,
                  child: const Icon(Icons.reply_rounded, semanticLabel: 'Ответить'),
                ),
              ),
            ),
          Transform.translate(offset: Offset(_dx, 0), child: widget.child),
        ],
      ),
    );
  }
}
