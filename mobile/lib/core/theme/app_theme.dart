import 'package:flutter/material.dart';

/// Brand colors (docs/design-audit.md, «accent ladder»): one rose at full strength only on small marks —
/// the like, the one primary action of a screen, the active tab, data such as the match percentage;
/// its pale tints carry areas (a selected chip, the active tab pill). Everything else is neutral.
/// Gold is kept for premium and legendary things only. No gradients, no shadows on things at rest.
abstract final class Brand {
  static const Color rose = Color(0xFFE11D48);
  static const Color roseLight = Color(0xFFFB7185);

  /// Ink of the rose: text on a rose tint (selected chips).
  static const Color roseInk = Color(0xFF9F1239);
  static const Color ink = Color(0xFF1C1719);
  static const Color like = rose;
  static const Color pass = Color(0xFF6B6266);
  static const Color gold = Color(0xFFD97706);
  static const Color verified = rose;
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
  // Neutral ground with white cards one tone step above it (no border on top); rose only on marks.
  final scheme = seeded.copyWith(
    primary: dark ? Brand.roseLight : Brand.rose,
    onPrimary: dark ? const Color(0xFF3B0614) : Colors.white,
    primaryContainer: dark ? const Color(0xFF3A1621) : const Color(0xFFFDE7EB),
    onPrimaryContainer: dark ? const Color(0xFFFFD9E0) : Brand.roseInk,
    secondary: dark ? Brand.roseLight : Brand.rose,
    onSecondary: Colors.white,
    surface: dark ? const Color(0xFF0F0E0E) : const Color(0xFFF7F5F5),
    onSurface: dark ? const Color(0xFFF4F1F2) : Brand.ink,
    onSurfaceVariant: dark ? const Color(0xFFB5ADB0) : const Color(0xFF5F575A),
    surfaceContainerLowest: dark ? const Color(0xFF0F0E0E) : Colors.white,
    surfaceContainerLow: dark ? const Color(0xFF1B1919) : Colors.white,
    surfaceContainer: dark ? const Color(0xFF222020) : const Color(0xFFF1EEEF),
    surfaceContainerHigh: dark ? const Color(0xFF2A2728) : const Color(0xFFEDE9EA),
    surfaceContainerHighest: dark ? const Color(0xFF332F31) : const Color(0xFFE5E0E2),
    outline: dark ? const Color(0xFF5A5355) : const Color(0xFFCFC8CB),
    outlineVariant: dark ? const Color(0xFF2E2B2C) : const Color(0xFFE6E1E3),
  );

  final base = ThemeData(
      colorScheme: scheme, useMaterial3: true, brightness: brightness, fontFamily: 'Onest');
  // Scale 12/14/16/20/24/28; 600 for headings, 700 is left to the name on a profile card.
  final text = base.textTheme.copyWith(
    headlineMedium: base.textTheme.headlineMedium
        ?.copyWith(fontSize: 28, fontWeight: FontWeight.w600, letterSpacing: -0.6, height: 1.15),
    headlineSmall: base.textTheme.headlineSmall
        ?.copyWith(fontSize: 24, fontWeight: FontWeight.w600, letterSpacing: -0.4, height: 1.2),
    titleLarge: base.textTheme.titleLarge?.copyWith(fontSize: 20, fontWeight: FontWeight.w600, letterSpacing: -0.2),
    titleMedium: base.textTheme.titleMedium?.copyWith(fontSize: 16, fontWeight: FontWeight.w600, letterSpacing: 0),
    titleSmall: base.textTheme.titleSmall?.copyWith(fontSize: 14, fontWeight: FontWeight.w600, letterSpacing: 0),
    labelLarge: base.textTheme.labelLarge?.copyWith(fontSize: 14, fontWeight: FontWeight.w600, letterSpacing: 0),
    labelMedium: base.textTheme.labelMedium?.copyWith(fontSize: 12, fontWeight: FontWeight.w500, letterSpacing: 0),
    labelSmall: base.textTheme.labelSmall?.copyWith(fontSize: 12, fontWeight: FontWeight.w500, letterSpacing: 0),
    bodyLarge: base.textTheme.bodyLarge?.copyWith(fontSize: 16, height: 1.5, letterSpacing: 0),
    bodyMedium: base.textTheme.bodyMedium?.copyWith(fontSize: 14, height: 1.45, letterSpacing: 0),
    bodySmall: base.textTheme.bodySmall?.copyWith(fontSize: 12, height: 1.4, letterSpacing: 0),
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
      titleTextStyle: text.headlineSmall?.copyWith(color: scheme.onSurface),
    ),
    navigationBarTheme: NavigationBarThemeData(
      height: 68,
      backgroundColor: scheme.surfaceContainerLowest,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      indicatorColor: scheme.primaryContainer,
      labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
      iconTheme: WidgetStateProperty.resolveWith((states) => IconThemeData(
            color: states.contains(WidgetState.selected) ? scheme.primary : scheme.onSurfaceVariant,
            size: 26,
          )),
      labelTextStyle: WidgetStateProperty.resolveWith((states) => text.labelSmall?.copyWith(
            fontWeight: states.contains(WidgetState.selected) ? FontWeight.w600 : FontWeight.w500,
            color: states.contains(WidgetState.selected) ? scheme.onSurface : scheme.onSurfaceVariant,
          )),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: ButtonStyle(
        // Disabled stays readable (about 4.5:1), not grey on grey; null leaves other states to the button.
        backgroundColor: WidgetStateProperty.resolveWith(
            (s) => s.contains(WidgetState.disabled) ? scheme.surfaceContainerHigh : null),
        foregroundColor: WidgetStateProperty.resolveWith(
            (s) => s.contains(WidgetState.disabled) ? scheme.onSurfaceVariant : null),
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
        side: WidgetStatePropertyAll(BorderSide(color: scheme.outline)),
        foregroundColor: WidgetStatePropertyAll(scheme.onSurface),
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
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Radii.lg)),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: scheme.surfaceContainer,
      selectedColor: scheme.primaryContainer,
      side: BorderSide.none,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(100)),
      labelStyle: text.labelLarge?.copyWith(
        fontWeight: FontWeight.w600,
        color: WidgetStateColor.resolveWith((states) =>
            states.contains(WidgetState.selected) ? scheme.onPrimaryContainer : scheme.onSurface),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      showCheckmark: false,
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: scheme.surfaceContainer,
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
    // Switches are neutral: the brand colour is not a status.
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith((s) =>
          s.contains(WidgetState.selected) ? scheme.surface : scheme.outline),
      trackColor: WidgetStateProperty.resolveWith((s) =>
          s.contains(WidgetState.selected) ? scheme.onSurface : scheme.surfaceContainerHigh),
      trackOutlineColor: WidgetStateProperty.resolveWith((s) =>
          s.contains(WidgetState.selected) ? scheme.onSurface : scheme.outline),
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: ButtonStyle(
        minimumSize: const WidgetStatePropertyAll(Size(48, 48)),
        shape: stadium,
        side: WidgetStatePropertyAll(BorderSide(color: scheme.outline)),
      ),
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(color: scheme.primary),
    iconTheme: IconThemeData(color: scheme.onSurfaceVariant),
    listTileTheme: ListTileThemeData(
      iconColor: scheme.onSurfaceVariant,
      contentPadding: const EdgeInsets.symmetric(horizontal: Gaps.md),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Radii.md)),
      titleTextStyle: text.titleMedium?.copyWith(color: scheme.onSurface),
      subtitleTextStyle: text.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
    ),
    badgeTheme: BadgeThemeData(backgroundColor: scheme.primary, textColor: scheme.onPrimary),
  );
}
