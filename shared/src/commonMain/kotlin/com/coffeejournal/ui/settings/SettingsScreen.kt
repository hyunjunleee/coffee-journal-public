package com.coffeejournal.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.coffeejournal.ui.ai.AiSettingsSection
import com.coffeejournal.ui.ai.AiTexts
import com.coffeejournal.ui.form.DefaultRecipeSettingsSection
import com.coffeejournal.ui.form.DefaultRecipeTexts
import com.coffeejournal.ui.form.TextLink
import com.coffeejournal.ui.map.search.PlaceSearchSettingsSection
import com.coffeejournal.ui.map.search.PlaceSearchTexts
import com.coffeejournal.ui.nav.Feature
import com.coffeejournal.ui.nav.Route
import com.coffeejournal.ui.notify.ReminderSettingsSection
import com.coffeejournal.ui.theme.AppType
import com.coffeejournal.ui.theme.BodyFont
import com.coffeejournal.ui.theme.Dimens
import com.coffeejournal.ui.theme.Display
import com.coffeejournal.ui.theme.DisplaySettings
import com.coffeejournal.ui.theme.FieldLabel
import com.coffeejournal.ui.theme.HairlineCard
import com.coffeejournal.ui.theme.HintText
import com.coffeejournal.ui.theme.Ink
import com.coffeejournal.ui.theme.Motion
import com.coffeejournal.ui.theme.NumberFont
import com.coffeejournal.ui.theme.ScreenTitleBar
import com.coffeejournal.ui.theme.SectionLabel
import com.coffeejournal.ui.theme.Seg
import com.coffeejournal.ui.theme.TextSize
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * 설정 (not on the web): display choices, reminders, the AI helper, the place search's optional key and sources, behind
 * the small gear in each tab's header.
 */
object SettingsFeature : Feature {
    override val module = module {
        single { DisplayPrefs(get()) }
        viewModelOf(::DisplaySettingsViewModel)
    }

    override fun NavGraphBuilder.routes(nav: NavHostController) {
        composable<Route.Settings> { SettingsScreen(nav) }
    }
}

/** Applies a display change at once ([Display.current]) and saves it; App re-reads the saved value on the next start. */
class DisplaySettingsViewModel(private val prefs: DisplayPrefs) : ViewModel() {
    fun update(change: (DisplaySettings) -> DisplaySettings) {
        val next = change(Display.current)
        if (next == Display.current) return
        Display.current = next
        viewModelScope.launch { prefs.save(next) }
    }
}

/** The screen's copy (also read by the tests). */
object SettingsTexts {
    const val FONT_HINT = "휴대폰에 있는 글꼴로 보여요. 명조 글꼴이 없는 휴대폰에서는 고딕으로 보여요."
    const val SIZE_HINT = "휴대폰의 글자 크기 설정에 한 번 더 곱해져요. 휴대폰 설정처럼 큰 글자는 조금 덜 커져요."
    val MOTION_HINT = "화면이 열리고 닫힐 때 겹쳐 흐려지는 시간이에요. " +
        listOf(Motion.FAST, Motion.NORMAL, Motion.SLOW).joinToString(" · ") { "${it.label} ${it.millis / 1000.0}초" }
    const val PREVIEW_TITLE = "에티오피아 예가체프 워카 첼베사"
    const val PREVIEW_NUMBERS = "15g · 240g · 1:16 · 92°C"
    const val PREVIEW_BODY = "자스민과 복숭아 향이 선명하고, 식을수록 단맛이 올라와요."
    const val SOURCES = "출처 · 오픈소스 라이선스 →"
}

@Composable
fun SettingsScreen(nav: NavHostController) {
    val vm = koinViewModel<DisplaySettingsViewModel>()
    val display = Display.current
    Column(Modifier.fillMaxSize()) {
        ScreenTitleBar("설정", onBack = { nav.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .testTag("settings")
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.gutter)
                .padding(bottom = 96.dp),
        ) {
            SectionLabel("화면")
            Choice("글꼴", BodyFont.entries, display.bodyFont, { it.label }, SettingsTexts.FONT_HINT) { f -> vm.update { it.copy(bodyFont = f) } }
            Choice("제목·숫자 글꼴", NumberFont.entries, display.numberFont, { it.label }) { f -> vm.update { it.copy(numberFont = f) } }
            Choice("글자 크기", TextSize.entries, display.textSize, { it.label }, SettingsTexts.SIZE_HINT) { s -> vm.update { it.copy(textSize = s) } }
            Choice("화면 전환", Motion.entries, display.motion, { it.label }, SettingsTexts.MOTION_HINT) { m -> vm.update { it.copy(motion = m) } }
            Preview()

            SectionLabel(DefaultRecipeTexts.SECTION)
            DefaultRecipeSettingsSection(nav)

            SectionLabel("알림")
            ReminderSettingsSection()

            SectionLabel(AiTexts.SECTION)
            AiSettingsSection()

            SectionLabel(PlaceSearchTexts.SECTION)
            PlaceSearchSettingsSection()

            SectionLabel("정보")
            TextLink(SettingsTexts.SOURCES, Ink.text, { nav.navigate(Route.About) })
        }
    }
}

@Composable
private fun <T> Choice(label: String, options: List<T>, value: T, name: (T) -> String, hint: String? = null, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        FieldLabel(label)
        Seg(
            options = options.map(name),
            value = name(value),
            onChange = { picked -> options.firstOrNull { name(it) == picked }?.let(onPick) },
            allowClear = false,
        )
        if (hint != null) HintText(hint)
    }
}

/** A few lines in the chosen typefaces and size, like a record card. */
@Composable
private fun Preview() {
    FieldLabel("미리보기")
    HairlineCard(Modifier.testTag("settings-preview")) {
        Text(SettingsTexts.PREVIEW_TITLE, style = AppType.cardTitle)
        Text(SettingsTexts.PREVIEW_NUMBERS, style = AppType.monoValue, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(6.dp))
        Text(SettingsTexts.PREVIEW_BODY, style = AppType.body)
    }
}
