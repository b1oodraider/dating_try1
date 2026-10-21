import 'package:flutter/material.dart';

/// Brand colors: warm rose + coral (picked with the ui-ux-pro-max design system for a dating app).
abstract final class Brand {
  static const Color rose = Color(0xFFE11D48);
  static const Color roseLight = Color(0xFFFB7185);
  static const Color coral = Color(0xFFFF7A59);
  static const Color like = rose;
  static const Color pass = Color(0xFF64748B);
  static const Color superlike = Color(0xFF3B82F6);
  static const Color gold = Color(0xFFF59E0B);
  static const Color verified = Color(0xFF0EA5E9);

  /// Primary call to action and the match screen.
  static const LinearGradient gradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [rose, coral],
  );
}

/// Spacing scale (4/8 dp rhythm) and corner radii used across screens.
abstract final class Gaps {
  static const double xs = 4;
  static const double sm = 8;
  static const double md = 16;
  static const double lg = 24;
  static const double xl = 32;
}

abstract final class Radii {
  static const double sm = 12;
  static const double md = 16;
  static const double lg = 24;
  static const double card = 28;
}

ThemeData buildLightTheme() => _build(Brightness.light);

ThemeData buildDarkTheme() => _build(Brightness.dark);

ThemeData _build(Brightness brightness) {
  final dark = brightness == Brightness.dark;
  final seeded = ColorScheme.fromSeed(seedColor: Brand.rose, brightness: brightness);
  final scheme = seeded.copyWith(
    primary: dark ? Brand.roseLight : Brand.rose,
    onPrimary: dark ? const Color(0xFF3B0614) : Colors.white,
    secondary: Brand.coral,
    onSecondary: Colors.white,
    surface: dark ? const Color(0xFF141012) : const Color(0xFFFFFBFB),
    onSurface: dark ? const Color(0xFFF5EEF0) : const Color(0xFF1F1418),
    onSurfaceVariant: dark ? const Color(0xFFC8BCC0) : const Color(0xFF5E5056),
    surfaceContainerLowest: dark ? const Color(0xFF0E0B0C) : Colors.white,
    surfaceContainerLow: dark ? const Color(0xFF1C1719) : const Color(0xFFFFF5F6),
    surfaceContainer: dark ? const Color(0xFF221C1F) : const Color(0xFFFCEEF0),
    surfaceContainerHigh: dark ? const Color(0xFF2B2427) : const Color(0xFFF8E6E9),
    surfaceContainerHighest: dark ? const Color(0xFF352D30) : const Color(0xFFF2DDE1),
    outlineVariant: dark ? const Color(0xFF3F3539) : const Color(0xFFF0D9DE),
  );

  final base = ThemeData(colorScheme: scheme, useMaterial3: true, brightness: brightness);
  final text = base.textTheme.copyWith(
    headlineMedium: base.textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w800, letterSpacing: -0.5),
    headlineSmall: base.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800, letterSpacing: -0.3),
    titleLarge: base.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700, letterSpacing: -0.2),
    titleMedium: base.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
    titleSmall: base.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
    labelLarge: base.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w700, letterSpacing: 0.1),
    bodyLarge: base.textTheme.bodyLarge?.copyWith(height: 1.45),
    bodyMedium: base.textTheme.bodyMedium?.copyWith(height: 1.45),
  );
  final stadium = WidgetStatePropertyAll<OutlinedBorder>(
      RoundedRectangleBorder(borderRadius: BorderRadius.circular(100)));
  const buttonSize = WidgetStatePropertyAll<Size>(Size(64, 52));
  const buttonPadding = WidgetStatePropertyAll<EdgeInsetsGeometry>(
      EdgeInsets.symmetric(horizontal: Gaps.lg));

  return base.copyWith(
    textTheme: text,
    scaffoldBackgroundColor: scheme.surface,
    appBarTheme: AppBarTheme(
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleTextStyle: text.headlineSmall?.copyWith(color: scheme.onSurface, fontSize: 24),
    ),
    navigationBarTheme: NavigationBarThemeData(
      height: 68,
      backgroundColor: scheme.surfaceContainerLowest,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      indicatorColor: scheme.primary.withValues(alpha: dark ? 0.22 : 0.12),
      labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
      iconTheme: WidgetStateProperty.resolveWith((states) => IconThemeData(
            color: states.contains(WidgetState.selected) ? scheme.primary : scheme.onSurfaceVariant,
            size: 26,
          )),
      labelTextStyle: WidgetStateProperty.resolveWith((states) => text.labelSmall?.copyWith(
            fontWeight: states.contains(WidgetState.selected) ? FontWeight.w800 : FontWeight.w600,
            color: states.contains(WidgetState.selected) ? scheme.primary : scheme.onSurfaceVariant,
          )),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: ButtonStyle(
        shape: stadium,
        minimumSize: buttonSize,
        padding: buttonPadding,
        textStyle: WidgetStatePropertyAll(text.labelLarge?.copyWith(fontSize: 16)),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: ButtonStyle(
        shape: stadium,
        minimumSize: buttonSize,
        padding: buttonPadding,
        side: WidgetStatePropertyAll(BorderSide(color: scheme.outlineVariant, width: 1.5)),
        textStyle: WidgetStatePropertyAll(text.labelLarge?.copyWith(fontSize: 16)),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: ButtonStyle(
        shape: stadium,
        minimumSize: const WidgetStatePropertyAll(Size(48, 48)),
        textStyle: WidgetStatePropertyAll(text.labelLarge),
      ),
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      color: scheme.surfaceContainerLow,
      surfaceTintColor: Colors.transparent,
      margin: const EdgeInsets.only(bottom: 12),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(Radii.lg),
        side: BorderSide(color: scheme.outlineVariant.withValues(alpha: 0.6)),
      ),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: scheme.surfaceContainerHigh,
      selectedColor: scheme.primary,
      side: BorderSide.none,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(100)),
      labelStyle: text.labelLarge?.copyWith(
        fontWeight: FontWeight.w600,
        color: WidgetStateColor.resolveWith((states) =>
            states.contains(WidgetState.selected) ? scheme.onPrimary : scheme.onSurface),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      showCheckmark: false,
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: scheme.surfaceContainerHigh,
      contentPadding: const EdgeInsets.symmetric(horizontal: Gaps.md, vertical: 16),
      // Underline borders keep the floating label inside the filled field; the line itself is hidden
      // until focus or error.
      border: UnderlineInputBorder(
        borderRadius: BorderRadius.circular(Radii.md),
        borderSide: BorderSide.none,
      ),
      enabledBorder: UnderlineInputBorder(
        borderRadius: BorderRadius.circular(Radii.md),
        borderSide: BorderSide.none,
      ),
      focusedBorder: UnderlineInputBorder(
        borderRadius: BorderRadius.circular(Radii.md),
        borderSide: BorderSide(color: scheme.primary, width: 2),
      ),
      errorBorder: UnderlineInputBorder(
        borderRadius: BorderRadius.circular(Radii.md),
        borderSide: BorderSide(color: scheme.error, width: 2),
      ),
    ),
    listTileTheme: ListTileThemeData(
      contentPadding: const EdgeInsets.symmetric(horizontal: Gaps.md),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Radii.md)),
      titleTextStyle: text.titleMedium?.copyWith(color: scheme.onSurface),
      subtitleTextStyle: text.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
    ),
    dividerTheme: DividerThemeData(color: scheme.outlineVariant, thickness: 1, space: 1),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Radii.sm)),
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
      showDragHandle: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(Radii.card)),
      ),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Radii.card)),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith((s) =>
          s.contains(WidgetState.selected) ? Colors.white : null),
      trackColor: WidgetStateProperty.resolveWith((s) =>
          s.contains(WidgetState.selected) ? scheme.primary : null),
      trackOutlineColor: WidgetStateProperty.resolveWith((s) =>
          s.contains(WidgetState.selected) ? scheme.primary : null),
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: ButtonStyle(
        minimumSize: const WidgetStatePropertyAll(Size(48, 48)),
        shape: stadium,
        side: WidgetStatePropertyAll(BorderSide(color: scheme.outlineVariant, width: 1.5)),
      ),
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(color: scheme.primary),
    badgeTheme: BadgeThemeData(backgroundColor: scheme.primary, textColor: scheme.onPrimary),
  );
}
