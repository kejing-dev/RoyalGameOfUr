package io.embodit.royalur.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.royalur.domain.Language
import io.embodit.royalur.platform.Graph
import java.util.Locale

/** design-package theme.colorScheme: the British Museum box (shell cream, lapis, red jasper, bitumen black). */
object RoyalColors {
    val Primary = Color(0xFF27437A)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFD6E0F2)
    val OnPrimaryContainer = Color(0xFF0F1F3D)
    val Secondary = Color(0xFF8C6A3F)
    val SecondaryContainer = Color(0xFFEFDCC0)
    val OnSecondaryContainer = Color(0xFF3B2A12)
    val Tertiary = Color(0xFFB5412F)
    val OnTertiary = Color(0xFFFFFFFF)
    val TertiaryContainer = Color(0xFFF4D6D0)
    val OnTertiaryContainer = Color(0xFF3E100A)
    val Error = Color(0xFFC8423F)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1F23)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(0xFF3A3F44)
    /** Error text on glass; the brighter Error stays for 3D marks and button fills. */
    val ErrorText = Color(0xFF7A1A18)
    val Amber = Color(0xFFE0A040)

    // 3D / tags
    val Shell = Color(0xFFEFE4CC)
    val Bitumen = Color(0xFF1C1A17)
    val Leather = Color(0xFF4A3524)
    val Obsidian = Color(0xFF17161A)
    val Jasper = Color(0xFFB5412F)
    val TipWhite = Color(0xFFF5F0E6)
    val TagFace = Color(0xFFFBF6EC)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). The language setting localises resources below this
 * point through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun RoyalTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = RoyalColors.OnPrimary,
        labelPrimary = RoyalColors.Ink,
        labelSecondary = RoyalColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = RoyalColors.Error,
        alert = RoyalColors.Amber,
        passable = RoyalColors.Secondary,
        interaction = RoyalColors.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
