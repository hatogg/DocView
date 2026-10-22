package ua.x44to.docview.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import ua.x44to.docview.core.Format
import ua.x44to.docview.core.Group
import ua.x44to.docview.data.Settings

/** Запасна схема (без Material You): глибокий синій, «документний». */
private val LightFallback = lightColorScheme(
    primary = Color(0xFF3A5BA9), onPrimary = Color.White,
    primaryContainer = Color(0xFFDAE2FF), onPrimaryContainer = Color(0xFF001847),
    secondary = Color(0xFF575E71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBE2F9), onSecondaryContainer = Color(0xFF141B2C),
    tertiary = Color(0xFF715573), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBD7FC), onTertiaryContainer = Color(0xFF29132D),
    error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAF8FF), onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFAF8FF), onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE1E2EC), onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF757780), outlineVariant = Color(0xFFC5C6D0),
    inverseSurface = Color(0xFF2F3036), inverseOnSurface = Color(0xFFF1F0F7), inversePrimary = Color(0xFFB1C5FF),
    surfaceDim = Color(0xFFDAD9E0), surfaceBright = Color(0xFFFAF8FF),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F3FA), surfaceContainer = Color(0xFFEEEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF), surfaceContainerHighest = Color(0xFFE3E2E9),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFB1C5FF), onPrimary = Color(0xFF002C71),
    primaryContainer = Color(0xFF1F438F), onPrimaryContainer = Color(0xFFDAE2FF),
    secondary = Color(0xFFBFC6DC), onSecondary = Color(0xFF293042),
    secondaryContainer = Color(0xFF3F4759), onSecondaryContainer = Color(0xFFDBE2F9),
    tertiary = Color(0xFFDEBCDF), onTertiary = Color(0xFF402843),
    tertiaryContainer = Color(0xFF583E5B), onTertiaryContainer = Color(0xFFFBD7FC),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121318), onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF121318), onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF44464F), onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F9099), outlineVariant = Color(0xFF44464F),
    inverseSurface = Color(0xFFE3E2E9), inverseOnSurface = Color(0xFF2F3036), inversePrimary = Color(0xFF3A5BA9),
    surfaceDim = Color(0xFF121318), surfaceBright = Color(0xFF38393F),
    surfaceContainerLowest = Color(0xFF0D0E13), surfaceContainerLow = Color(0xFF1A1B21), surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF292A2F), surfaceContainerHighest = Color(0xFF34343A),
)

@Composable
fun isDark(s: Settings): Boolean = when (s.theme) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun DocTheme(s: Settings, content: @Composable () -> Unit) {
    val dark = isDark(s)
    val ctx = LocalContext.current
    val scheme = when {
        s.dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkFallback
        else -> LightFallback
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Фірмові кольори форматів (як у звичних офісних програм). */
fun formatColor(f: Format): Color = when (f.group) {
    Group.PDF -> Color(0xFFE53935)
    Group.WORD -> Color(0xFF2B579A)
    Group.EXCEL -> Color(0xFF217346)
    Group.SLIDES -> Color(0xFFD24726)
    Group.BOOK -> Color(0xFF7E57C2)
    Group.TEXT -> Color(0xFF607D8B)
    Group.IMAGE -> Color(0xFF00897B)
    Group.ARCHIVE -> Color(0xFF8D6E63)
    Group.OTHER -> Color(0xFF757575)
}

/** Тема читання: колір тла й тексту для документів і книжок. */
class Paper(val bg: Color, val fg: Color, val dim: Color, val link: Color, val dark: Boolean)

fun paper(reading: Int, appDark: Boolean): Paper = when (if (reading == 0) (if (appDark) 3 else 1) else reading) {
    2 -> Paper(Color(0xFFF6EEDC), Color(0xFF3B3024), Color(0xFF7A6A55), Color(0xFF8A4B12), false)
    3 -> Paper(Color(0xFF16171B), Color(0xFFD8D8DC), Color(0xFF8E8F96), Color(0xFF8AB4F8), true)
    else -> Paper(Color.White, Color(0xFF1F1F1F), Color(0xFF6B6B6B), Color(0xFF1A5FB4), false)
}
