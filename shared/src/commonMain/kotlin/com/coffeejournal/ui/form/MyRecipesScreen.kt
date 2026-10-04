package com.coffeejournal.ui.form

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavHostController
import com.coffeejournal.domain.model.MyRecipe
import com.coffeejournal.ui.theme.InputFilters
import com.coffeejournal.ui.theme.AppType
import com.coffeejournal.ui.theme.Dimens
import com.coffeejournal.ui.theme.GhostButton
import com.coffeejournal.ui.theme.HairlineCard
import com.coffeejournal.ui.theme.HintText
import com.coffeejournal.ui.theme.Ink
import com.coffeejournal.ui.theme.LeaveDialog
import com.coffeejournal.ui.theme.PrimaryButton
import com.coffeejournal.ui.theme.ScreenTitleBar
import com.coffeejournal.ui.theme.rememberLeaveGuard
import kotlinx.serialization.json.Json
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route.MyRecipes — saved recipes plus the "새 레시피 만들기" form (web 내 레시피 panel), and which one is the
 * 기본 레시피 ([DefaultRecipe]). Opened from the form's "+ 새 레시피 만들기" button ([newRecipe]) the new-recipe form
 * starts open with the name focused (web toggles it inline); from 설정's 기본 레시피 it starts closed.
 */
@Composable
fun MyRecipesScreen(nav: NavHostController, newRecipe: Boolean = true) {
    val vm = koinViewModel<MyRecipesViewModel>()
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val defaultId by vm.defaultId.collectAsStateWithLifecycle()
    val equipment by vm.equipment.collectAsStateWithLifecycle()
    var showForm by rememberSaveable { mutableStateOf(newRecipe) }
    var pendingDelete by remember { mutableStateOf<MyRecipe?>(null) }
    // the new recipe's fields live here, so leaving the screen can ask about them and hiding the form keeps them
    var draft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(MyRecipeDraft()) }
    val hasDraft = { draft != MyRecipeDraft() }
    val guard = rememberLeaveGuard(hasDraft, busy = false, leave = dropUnlessResumed { nav.popBackStack() })
    LeaveDialog(guard)
    // the form's own 취소 empties and closes it: the same question, about closing it
    val cancelGuard = rememberLeaveGuard(hasDraft, busy = false, systemBack = false, leave = { draft = MyRecipeDraft(); showForm = false })
    LeaveDialog(cancelGuard, text = "지금 닫으면 입력한 내용은 사라져요.", leaveLabel = "닫기")

    Column(Modifier.fillMaxSize().background(Ink.bg).statusBarsPadding()) {
        ScreenTitleBar(title = "내 레시피", onBack = guard::request)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
            Spacer(Modifier.height(14.dp))
            Text(
                if (recipes.isEmpty()) "아직 저장된 내 레시피가 없어요. 기록을 펼쳤을 때 \"⭐ 내 레시피로 저장\"을 누르거나, 아래에서 원두랑 상관없이 새로 만들어보세요."
                else "직접 저장한 나만의 레시피예요. 레시피랑 다르게 부었는데 오히려 맛있었던 추출을 기록해두거나, 원두랑 상관없이 미리 레시피를 만들어뒀다가 나중에 적용해보세요.",
                style = AppType.bodyMuted,
            )
            if (recipes.isNotEmpty()) HintText(DefaultRecipeTexts.ABOUT_LIST)
            Spacer(Modifier.height(12.dp))
            GhostButton("+ 새 레시피 만들기", small = true, onClick = { showForm = !showForm })
            if (showForm) {
                NewRecipeForm(
                    equipment, draft, update = { change -> draft = change(draft) },
                    onSave = { vm.create(draft).also { ok -> if (ok) { draft = MyRecipeDraft(); showForm = false } } },
                    onCancel = cancelGuard::request,
                )
            }
            Spacer(Modifier.height(16.dp))
            recipes.forEach { r ->
                val isDefault = r.id == defaultId
                RecipeCard(r, isDefault, onToggleDefault = { vm.setDefault(if (isDefault) null else r.id) }, onDelete = { pendingDelete = r })
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(96.dp))
        }
    }
    pendingDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null }, shape = RectangleShape, containerColor = Ink.bg,
            title = { Text("레시피 삭제", style = AppType.title) },
            text = { Text("\"${r.name}\" 레시피를 삭제할까요?", style = AppType.body) },
            confirmButton = { PrimaryButton("삭제", small = true, onClick = { vm.delete(r.id); pendingDelete = null }) },
            dismissButton = { GhostButton("취소", small = true, onClick = { pendingDelete = null }) },
        )
    }
}

@Composable
private fun RecipeCard(r: MyRecipe, isDefault: Boolean, onToggleDefault: () -> Unit, onDelete: () -> Unit) {
    HairlineCard(Modifier.testTag("recipe-card")) {
        Row(Modifier.fillMaxWidth()) {
            Text(r.name, style = AppType.cardTitle, modifier = Modifier.weight(1f))
            DefaultRecipe.badge(r, isDefault).takeIf { it.isNotEmpty() }?.let { Text(it, style = AppType.monoSmall) }
        }
        Text(DefaultRecipe.spec(r), style = AppType.monoValue, modifier = Modifier.padding(top = 4.dp))
        if (r.beanName.isNotBlank()) Text("원두: ${r.beanName}", style = AppType.bodyMuted, modifier = Modifier.padding(top = 4.dp))
        Text(if (r.steps.isEmpty()) "단계 없음" else "단계 ${r.steps.size}개", style = AppType.faint, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(if (isDefault) DefaultRecipeTexts.UNSET else DefaultRecipeTexts.SET, small = true, onClick = onToggleDefault)
            GhostButton("삭제", small = true, danger = true, onClick = onDelete)
        }
    }
}

/** Keeps the typed draft through configuration changes and process death. */
private val DraftSaver: Saver<MyRecipeDraft, String> = Saver(
    save = { Json.encodeToString(MyRecipeDraft.serializer(), it) },
    restore = { runCatching { Json.decodeFromString(MyRecipeDraft.serializer(), it) }.getOrNull() },
)

/** Web #new-my-recipe-form: name is required, everything else optional. [d] is the typed recipe, kept by the screen. */
@Composable
private fun NewRecipeForm(
    equipment: RecipeEquipment,
    d: MyRecipeDraft,
    update: ((MyRecipeDraft) -> MyRecipeDraft) -> Unit,
    onSave: () -> Boolean,
    onCancel: () -> Unit,
) {
    var nameError by rememberSaveable { mutableStateOf<String?>(null) }
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        FormTextField(
            d.name, { update { r -> r.copy(name = it) }; nameError = null }, label = "레시피 이름", placeholder = "예: 밝은 산미용 3단 푸어",
            error = nameError, focusRequester = nameFocus, modifier = Modifier.padding(bottom = 10.dp),
        )
        TwoUp(
            { m -> AutocompleteField(d.dripper, { update { r -> r.copy(dripper = it) } }, equipment.drippers, m, label = "드리퍼") },
            { m -> AutocompleteField(d.filter, { update { r -> r.copy(filter = it) } }, equipment.filters, m, label = "필터") },
        )
        TwoUp(
            { m -> FormTextField(d.grind, { update { r -> r.copy(grind = it) } }, m, label = "분쇄도") },
            { m -> FormTextField(d.dose, { update { r -> r.copy(dose = it) } }, m, label = "원두량 (g)", keyboardType = KeyboardType.Decimal, inputFilter = InputFilters::decimal) },
        )
        TwoUp(
            { m -> FormTextField(d.water, { update { r -> r.copy(water = it) } }, m, label = "물량 (g)", keyboardType = KeyboardType.Decimal, inputFilter = InputFilters::decimal) },
            { m -> FormTextField(d.temp, { update { r -> r.copy(temp = it) } }, m, label = "물 온도 (°C)", keyboardType = KeyboardType.Decimal, inputFilter = InputFilters::decimal) },
        )
        TwoUp({ m -> FormTextField(d.time, { update { r -> r.copy(time = it) } }, m, label = "총 추출시간", placeholder = "2:10") })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("저장", onClick = { if (!onSave()) nameError = "레시피 이름을 입력해 주세요." }, modifier = Modifier.weight(1f))
            GhostButton("취소", onClick = onCancel)
        }
    }
}
