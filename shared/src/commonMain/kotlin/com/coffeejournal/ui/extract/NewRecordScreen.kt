package com.coffeejournal.ui.extract

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.coffeejournal.data.repo.EntryRepository
import com.coffeejournal.data.repo.MyRecipeRepository
import com.coffeejournal.domain.model.CafePlace
import com.coffeejournal.domain.model.Category
import com.coffeejournal.domain.model.Entry
import com.coffeejournal.domain.model.MiscType
import com.coffeejournal.domain.model.MyRecipe
import com.coffeejournal.domain.reference.EquipmentTypes
import com.coffeejournal.domain.rules.BeanNames
import com.coffeejournal.domain.rules.Dates
import com.coffeejournal.ui.form.DefaultRecipe
import com.coffeejournal.ui.form.DefaultRecipeStore
import com.coffeejournal.ui.form.DefaultRecipeTexts
import com.coffeejournal.ui.nav.FormMode
import com.coffeejournal.ui.nav.Route
import com.coffeejournal.ui.theme.AppType
import com.coffeejournal.ui.theme.Chip
import com.coffeejournal.ui.theme.Dimens
import com.coffeejournal.ui.theme.Hairline
import com.coffeejournal.ui.theme.Ink
import com.coffeejournal.ui.theme.MinTouchTarget
import com.coffeejournal.ui.theme.ScreenTitleBar
import com.coffeejournal.ui.theme.SectionLabel
import com.coffeejournal.ui.theme.deriveOffMain
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.viewmodel.koinViewModel

/** The chooser's copy (also read by the tests). */
object NewRecordTexts {
    const val TITLE = "새 기록"
    const val COFFEE = "마신 커피"
    const val BREW = "직접 내린 커피"
    const val BREW_HINT = "집에서 내린 드립·에스프레소 등, 레시피와 맛"
    const val CAFE = "카페에서 마신 커피"
    const val CAFE_HINT = "카페 이름, 한 잔 가격과 맛"
    const val CUPPING = "커핑"
    const val CUPPING_HINT = "퍼블릭 · 홈커핑 · 수업, 여러 원두를 한 번에"
    const val AGAIN = "같은 커피 다시"
    const val AGAIN_HINT = "최근 기록 그대로, 오늘 날짜로"
    const val BEAN = "원두"
    const val PANTRY = "원두 보관함에 원두 추가"
    const val PANTRY_HINT = "산 원두 봉투: 용량, 로스팅일, 남은 양"
    const val BLEND = "블렌드"
    const val BLEND_HINT = "로스터리·카페 블렌드의 구성"
    const val BEAN_INFO = "원두 정보"
    const val FARM = "농장"
    const val SELECTION = "생두 수입사"
    const val STUDY = "공부"
    const val BOOK = "책"
    const val VIDEO = "영상"
    const val CLASS = "클래스"
    const val EQUIPMENT = "장비"
    const val PLACES = "장소"
    const val CAFE_PLACE = "카페"
    const val ROASTERY = "로스터리"
}

/** What the chooser lists besides the fixed choices. */
object NewRecordLogic {
    /**
     * The coffees recorded last, newest first — brewed, had at a café or cupped — each kind, place and bean once (a café
     * as [CafePlace.key], a bean as [BeanNames.coreBeanName] match them): what "같은 커피 다시" offers to record again.
     */
    fun recentCoffees(entries: List<Entry>, limit: Int = 4): List<Entry> =
        entries.asSequence()
            .filter { title(it).isNotBlank() }
            .sortedByDescending { it.createdAt }
            .distinctBy { listOf(it.category.ifBlank { Category.BEAN }, CafePlace.key(place(it)), BeanNames.coreBeanName(title(it))).joinToString("|") }
            .take(limit)
            .toList()

    /** What a coffee to record again is called: its bean, or a cupping's name (else its beans). */
    fun title(entry: Entry): String = when {
        entry.isCupping -> entry.name.ifBlank { entry.cuppingBeans.map { it.name }.filter { it.isNotBlank() }.joinToString(", ") }
        entry.name.isBlank() -> ""
        else -> BeanNames.displayName(entry.name)
    }

    private fun place(entry: Entry): String = when {
        entry.isCafe -> entry.cafeName
        entry.isCupping -> entry.cuppingPlace
        else -> ""
    }

    /** "카페 · FELT 청계천 · 2026.09.18" under a coffee to record again. */
    fun againLine(entry: Entry): String {
        val kind = when {
            entry.isCafe -> "카페"
            entry.isCupping -> "커핑"
            else -> "직접 내림"
        }
        return listOf(kind, place(entry).trim(), Dates.ymdPadded(entry.createdAt)).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** The form mode a record of [entry]'s kind opens in. */
    fun modeFor(entry: Entry): String = when {
        entry.isCafe -> FormMode.CAFE
        entry.isCupping -> FormMode.CUPPING
        else -> FormMode.EXTRACT
    }
}

class NewRecordViewModel(entries: EntryRepository, recipes: MyRecipeRepository, defaultRecipe: DefaultRecipeStore) : ViewModel() {
    /** Null until the records are read. */
    val recent: StateFlow<List<Entry>?> = entries.observeAll()
        .deriveOffMain { NewRecordLogic.recentCoffees(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The 기본 레시피 a new brew starts with ([DefaultRecipe]), for 직접 내린 커피's line; null when none. */
    val defaultRecipe: StateFlow<MyRecipe?> = combine(defaultRecipe.observeId(), recipes.observeAll()) { id, list -> DefaultRecipe.resolve(id, list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * Route.NewRecord — "+ 새 기록 추가" on the home tab (and the widget's "+ 새 기록"): every kind of record the app keeps,
 * each opening its own form. The chooser leaves the back stack as the form opens, so back and saving return home.
 */
@Composable
fun NewRecordScreen(nav: NavHostController) {
    val vm = koinViewModel<NewRecordViewModel>()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val defaultRecipe by vm.defaultRecipe.collectAsStateWithLifecycle()
    val open = { route: Route -> nav.navigate(route) { popUpTo<Route.NewRecord> { inclusive = true } } }
    Column(Modifier.fillMaxSize().background(Ink.bg).statusBarsPadding()) {
        ScreenTitleBar(title = NewRecordTexts.TITLE, onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter).navigationBarsPadding()) {
            SectionLabel(NewRecordTexts.COFFEE)
            ChoiceRow(NewRecordTexts.BREW, NewRecordTexts.BREW_HINT, note = defaultRecipe?.let { DefaultRecipeTexts.chooserHint(it.name) }) {
                open(Route.RecordForm(mode = FormMode.EXTRACT))
            }
            Hairline()
            ChoiceRow(NewRecordTexts.CAFE, NewRecordTexts.CAFE_HINT) { open(Route.RecordForm(mode = FormMode.CAFE)) }
            Hairline()
            ChoiceRow(NewRecordTexts.CUPPING, NewRecordTexts.CUPPING_HINT) { open(Route.RecordForm(mode = FormMode.CUPPING)) }

            val again = recent.orEmpty()
            if (again.isNotEmpty()) {
                SectionLabel(NewRecordTexts.AGAIN, hint = NewRecordTexts.AGAIN_HINT)
                again.forEachIndexed { i, en ->
                    if (i > 0) Hairline()
                    ChoiceRow(NewRecordLogic.title(en), NewRecordLogic.againLine(en)) {
                        open(Route.RecordForm(mode = NewRecordLogic.modeFor(en), againFrom = en.id))
                    }
                }
            }

            SectionLabel(NewRecordTexts.BEAN)
            ChoiceRow(NewRecordTexts.PANTRY, NewRecordTexts.PANTRY_HINT) { open(Route.PantryEditor()) }
            Hairline()
            ChoiceRow(NewRecordTexts.BLEND, NewRecordTexts.BLEND_HINT) { open(Route.BlendForm()) }
            Hairline()
            ChipRow(NewRecordTexts.BEAN_INFO) {
                Chip(NewRecordTexts.FARM, toggle = false, onClick = { open(Route.FlatItemForm(type = MiscType.FARM)) })
                Chip(NewRecordTexts.SELECTION, toggle = false, onClick = { open(Route.FlatItemForm(type = MiscType.SELECTION)) })
            }

            SectionLabel(NewRecordTexts.STUDY)
            ChipRow(null) {
                Chip(NewRecordTexts.BOOK, toggle = false, onClick = { open(Route.BookForm()) })
                Chip(NewRecordTexts.VIDEO, toggle = false, onClick = { open(Route.VideoForm()) })
                Chip(NewRecordTexts.CLASS, toggle = false, onClick = { open(Route.ClassForm()) })
            }

            SectionLabel(NewRecordTexts.EQUIPMENT)
            ChipRow(null) {
                EquipmentTypes.order.forEach { type ->
                    Chip(EquipmentTypes.labels[type]?.name ?: type, toggle = false, onClick = { open(Route.MiscForm(type = type)) })
                }
            }

            SectionLabel(NewRecordTexts.PLACES)
            ChipRow(null) {
                Chip(NewRecordTexts.CAFE_PLACE, toggle = false, onClick = { open(Route.CafeAdd) })
                Chip(NewRecordTexts.ROASTERY, toggle = false, onClick = { open(Route.FlatItemForm(type = MiscType.SOURCE)) })
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

/** One choice: its name, what it records, and an arrow; the whole row is the button. */
@Composable
private fun ChoiceRow(title: String, hint: String, note: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.cardTitle)
            Text(hint, style = AppType.small, modifier = Modifier.padding(top = 2.dp))
            note?.let { Text("⭐ $it", style = AppType.small.copy(color = Ink.accent), modifier = Modifier.padding(top = 2.dp)) }
        }
        Text("→", style = AppType.body.copy(color = Ink.textMuted), modifier = Modifier.padding(start = 8.dp))
    }
}

/** Smaller choices as chips, after a [label] when there is one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(label: String?, chips: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        label?.let { Text(it, style = AppType.fieldLabel, modifier = Modifier.padding(bottom = 6.dp)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}
