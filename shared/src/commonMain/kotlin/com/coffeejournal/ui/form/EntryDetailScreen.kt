package com.coffeejournal.ui.form

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.coffeejournal.domain.model.Entry
import com.coffeejournal.ui.map.CafePlaceRow
import com.coffeejournal.ui.nav.Route
import com.coffeejournal.ui.notify.SettingSwitch
import com.coffeejournal.ui.theme.AppType
import com.coffeejournal.ui.theme.Dimens
import com.coffeejournal.ui.theme.EmptyNote
import com.coffeejournal.ui.theme.GhostButton
import com.coffeejournal.ui.theme.HintText
import com.coffeejournal.ui.theme.Ink
import com.coffeejournal.ui.theme.PrimaryButton
import com.coffeejournal.ui.theme.ScreenTitleBar
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Route.EntryDetail — the web's expanded record card as a full screen. */
@Composable
fun EntryDetailScreen(nav: NavHostController, entryId: String) {
    val vm = koinViewModel<EntryDetailViewModel> { parametersOf(entryId) }
    val ui by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var recipeDialog by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { ev ->
            when (ev) {
                DetailEvent.Deleted -> nav.popBackStack()
                is DetailEvent.RecipeSaved -> flash = if (ev.asDefault) DefaultRecipeTexts.savedAsDefault(ev.name) else EntryDisplay.recipeSavedText(ev.name)
            }
        }
    }
    LaunchedEffect(flash) { if (flash != null) { delay(5_000); flash = null } }

    Column(Modifier.fillMaxSize().background(Ink.bg).statusBarsPadding()) {
        ScreenTitleBar(title = "기록", onBack = { nav.popBackStack() })
        val en = ui.entry
        when {
            ui.loading -> {}
            en == null -> EmptyNote("기록을 찾을 수 없어요.", Modifier.padding(Dimens.gutter))
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter).navigationBarsPadding()) {
                EntryDetailContent(en, ui.siblings, ui.isBest, vm::photoPath)
                if (en.isCafe) CafePlaceRow(nav, en.cafeName, Modifier.padding(top = 8.dp))
                PrimaryButton(
                    AgainTexts.button(en.category), modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                    onClick = { nav.navigate(Route.RecordForm(mode = EntryDisplay.formModeFor(en.category), againFrom = en.id)) },
                )
                HintText(AgainTexts.hint(en.category, withRecipe = en.isCafe && FormMapper.hasRecipe(en)), Modifier.padding(top = 6.dp))
                flash?.let { Text(it, style = AppType.small.copy(color = Ink.good), modifier = Modifier.padding(top = 12.dp)) }
                DetailActions(
                    en = en, isBest = ui.isBest, canBeBest = ui.bestKeys.isNotEmpty(),
                    onEdit = { nav.navigate(Route.RecordForm(mode = EntryDisplay.formModeFor(en.category), entryId = en.id)) },
                    onDelete = { confirmDelete = true },
                    onSaveRecipe = { recipeDialog = true },
                    onToggleBest = vm::toggleBest,
                )
                Spacer(Modifier.height(96.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, shape = RectangleShape, containerColor = Ink.bg,
            title = { Text("기록 삭제", style = AppType.title) },
            text = { Text(ui.entry?.let(EntryDisplay::deleteConfirmText) ?: "기록을 정말 삭제할까요?", style = AppType.body) },
            confirmButton = { PrimaryButton("삭제", small = true, onClick = { confirmDelete = false; vm.delete() }) },
            dismissButton = { GhostButton("취소", small = true, onClick = { confirmDelete = false }) },
        )
    }
    if (recipeDialog) ui.entry?.let { en ->
        RecipeNameDialog(
            defaultName = EntryDisplay.defaultRecipeName(en),
            onDismiss = { recipeDialog = false },
            onConfirm = { name, asDefault -> recipeDialog = false; vm.saveAsMyRecipe(name, asDefault) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailActions(
    en: Entry,
    isBest: Boolean,
    canBeBest: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSaveRecipe: () -> Unit,
    onToggleBest: () -> Unit,
) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (en.steps.isNotEmpty()) GhostButton("⭐ 내 레시피로 저장", small = true, onClick = onSaveRecipe)
        if (canBeBest) {
            GhostButton(if (isBest) "⭐ 베스트 레시피 해제" else "이 원두의 베스트 레시피로 지정", small = true, onClick = onToggleBest)
        }
        GhostButton("수정", small = true, onClick = onEdit)
        GhostButton("삭제", small = true, danger = true, onClick = onDelete)
    }
}

/** Web window.prompt for the recipe name. */
@Composable
private fun RecipeNameDialog(defaultName: String, onDismiss: () -> Unit, onConfirm: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var asDefault by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, shape = RectangleShape, containerColor = Ink.bg,
        title = { Text("내 레시피로 저장", style = AppType.title) },
        text = {
            Column {
                Text("이 레시피 이름을 정해주세요 (나중에 알아보기 쉽게):", style = AppType.small)
                Spacer(Modifier.height(8.dp))
                FormTextField(value = name, onValueChange = { name = it }, placeholder = defaultName)
                SettingSwitch(DefaultRecipeTexts.SAVE_AS_DEFAULT, DefaultRecipeTexts.SAVE_AS_DEFAULT_HINT, asDefault, { asDefault = it }, Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { PrimaryButton("저장", small = true, onClick = { onConfirm(name, asDefault) }) },
        dismissButton = { GhostButton("취소", small = true, onClick = onDismiss) },
    )
}
