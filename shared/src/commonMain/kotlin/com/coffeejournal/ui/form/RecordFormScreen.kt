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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavHostController
import com.coffeejournal.ui.ai.NoteHelperResult
import com.coffeejournal.ui.ai.NoteMode
import com.coffeejournal.ui.form.sections.BagPhotoSection
import com.coffeejournal.ui.form.sections.BasicSection
import com.coffeejournal.ui.form.sections.BeanIdentitySection
import com.coffeejournal.ui.form.sections.BeanInfoSection
import com.coffeejournal.ui.form.sections.CuppingSection
import com.coffeejournal.ui.form.sections.RecipeLauncherSection
import com.coffeejournal.ui.form.sections.RecipeSection
import com.coffeejournal.ui.form.sections.TastingSection
import com.coffeejournal.ui.form.timer.BrewTimerResult
import com.coffeejournal.ui.map.CafeMapViewModel
import com.coffeejournal.ui.map.MapPickTarget
import com.coffeejournal.ui.nav.Route
import com.coffeejournal.ui.theme.AppType
import com.coffeejournal.ui.theme.BlockBackWhile
import com.coffeejournal.ui.theme.Dimens
import com.coffeejournal.ui.theme.GhostButton
import com.coffeejournal.ui.theme.Hairline
import com.coffeejournal.ui.theme.Ink
import com.coffeejournal.ui.theme.LeaveGuard
import com.coffeejournal.ui.theme.LeaveTexts
import com.coffeejournal.ui.theme.PrimaryButton
import com.coffeejournal.ui.theme.ScreenTitleBar
import com.coffeejournal.ui.theme.rememberLeaveGuard
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Route.RecordForm — the record input form in 원두 / 카페 / 커핑 mode, new or editing. */
@Composable
fun RecordFormScreen(
    nav: NavHostController,
    mode: String,
    entryId: String?,
    cuppingType: String?,
    results: SavedStateHandle? = null,
    againFrom: String? = null,
) {
    val vm = koinViewModel<RecordFormViewModel> { parametersOf(FormArgs(mode, entryId, cuppingType, againFrom)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val restoredDraft by vm.restoredDraft.collectAsStateWithLifecycle()
    val nameFocus = remember { FocusRequester() }
    val blendFocus = remember { FocusRequester() }
    val cuppingFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        vm.events.collect { ev ->
            when (ev) {
                is FormEvent.Saved -> {
                    // Editing came from the detail screen, which observes the record; just return to it.
                    nav.popBackStack()
                    if (!ev.wasEdit) nav.navigate(Route.EntryDetail(ev.entryId))
                }
                FormEvent.NotFound -> nav.popBackStack()
            }
        }
    }
    // the brew timer hands its rows back through this destination's saved state (also after a process death)
    if (results != null) LaunchedEffect(results) {
        results.getStateFlow<String?>(BrewTimerResult.KEY, null).collect { json ->
            if (json != null) {
                results.remove<String>(BrewTimerResult.KEY)
                BrewTimerResult.decode(json)?.let(vm::applyTimerSteps)
            }
        }
    }
    // the AI note helper hands back the candidate notes the user picked (mode B)
    if (results != null) LaunchedEffect(results) {
        results.getStateFlow<String?>(NoteHelperResult.KEY, null).collect { json ->
            if (json != null) {
                results.remove<String>(NoteHelperResult.KEY)
                NoteHelperResult.decode(json)?.let(vm::addActualNotes)
            }
        }
    }
    LaunchedEffect(state.error) {
        val field = state.error?.field ?: return@LaunchedEffect
        val requester = when (field) {
            FormField.NAME -> nameFocus
            FormField.BLEND_ROWS -> blendFocus
            FormField.CUPPING_BEAN_NAME -> cuppingFocus
        }
        runCatching { requester.requestFocus() }
    }

    // system back waits while "저장 중..." is shown, so a new record still opens its detail screen when the save finishes
    BlockBackWhile(state.saving)
    // The title-bar back, 취소 and system back wait too (the save itself also survives leaving, see save()); with
    // input the form did not open with they ask first. Any way out keeps the input as the draft right away.
    val guard = rememberLeaveGuard(vm::hasChanges, busy = state.saving, leave = { vm.keepDraft(); nav.popBackStack() })
    DraftLeaveDialog(guard, isEdit = state.isEdit, photosLost = state.bagPhotos.any { it.pending != null }, onDiscard = vm::discardDraft)
    Column(Modifier.fillMaxSize().background(Ink.bg).statusBarsPadding()) {
        ScreenTitleBar(title = if (state.isEdit) "기록 수정" else "새 기록", onBack = guard::request)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter)) {
            if (loaded) {
                restoredDraft?.let { DraftBanner(it, onStartOver = vm::startOver, onClose = vm::closeDraftNotice) }
                if (state.againFrom.isNotEmpty()) {
                    AgainBanner(AgainTexts.banner(state.againFrom, state.category, state.isCafe && state.cafeRecipeUsed), onClose = { vm.update { it.copy(againFrom = "") } })
                }
                RecordFormBody(state, suggestions, vm, nav, nameFocus, blendFocus, cuppingFocus)
            }
            Spacer(Modifier.height(96.dp))
        }
        FormActions(state, onSave = vm::save, onCancel = guard::request)
    }
}

@Composable
private fun RecordFormBody(
    state: FormState,
    suggestions: FormSuggestions,
    vm: RecordFormViewModel,
    nav: NavHostController,
    nameFocus: FocusRequester,
    blendFocus: FocusRequester,
    cuppingFocus: FocusRequester,
) {
    val update: ((FormState) -> FormState) -> Unit = vm::update
    val foldMap by vm.folds.collectAsStateWithLifecycle()
    val folds = Folds({ FormFold.isFolded(foldMap, state.category, it) }, vm::toggleFold)
    FoldAllRow(FormFold.parts(state.category).map(folds.isFolded), onFoldAll = vm::foldAll)
    if (state.isBrew) {
        val defaultId by vm.defaultRecipeId.collectAsStateWithLifecycle()
        // a new brew still holding the default recipe it opened with says so
        val startedWith = vm.startedWithDefault(state, defaultId)?.let(DefaultRecipeTexts::startedWith)
        RecipeLauncherSection(
            open = state.openLauncher,
            myRecipes = suggestions.myRecipes,
            onToggle = { l -> update { it.copy(openLauncher = if (it.openLauncher == l) null else l) } },
            onChampion = vm::applyChampion,
            onCafe = vm::applyCafeRecipe,
            onMine = vm::applyMyRecipe,
            onDeleteMine = vm::deleteMyRecipe,
            onOpenMyRecipes = { nav.navigate(Route.MyRecipes()) },
            defaultRecipeId = defaultId,
            onSetDefault = vm::setDefaultRecipe,
            startedWith = startedWith,
        )
    }
    BasicSection(state, update)
    if (state.isCupping) {
        CuppingSection(state, suggestions, cuppingFocus, update, folds)
        return
    }
    // a café record: the cafés there are (added by hand too) for 카페 이름, whose "위치 지정" saves the position at once
    val cafes = if (state.isCafe) koinViewModel<CafeMapViewModel>().spots.collectAsStateWithLifecycle().value.orEmpty() else emptyList()
    BeanIdentitySection(
        state, suggestions, nameFocus, blendFocus, vm::onNameTyped, vm::onNameBlur, update, cafes = cafes,
        onPickCafePlace = dropUnlessResumed { nav.navigate(Route.MapPicker(target = MapPickTarget.CAFE, name = state.cafeName.trim())) },
    )
    FoldSection(
        "원두 상세 정보", folds.isFolded(FormFold.ORIGIN), FormFold.originLine(state), { folds.toggle(FormFold.ORIGIN) }, tag = FormFold.ORIGIN,
    ) { BeanInfoSection(state, suggestions, update) }
    // Web hideBagPhotoSection: a repeat brew of a known bean has no bag photos of its own (photos it already has stay).
    val bagPhotosHidden = state.repeatBean && state.bagPhotos.none { it.hasImage }
    if (!state.isCafe && !bagPhotosHidden) {
        FoldSection(
            "원두 봉투 사진", folds.isFolded(FormFold.BAG_PHOTOS), FormFold.bagPhotosLine(state), { folds.toggle(FormFold.BAG_PHOTOS) },
            hint = "(선택, 최대 2장 — 첫 번째가 대표 사진)", tag = FormFold.BAG_PHOTOS,
        ) { BagPhotoSection(state.bagPhotos, vm::photoModel, vm::setPhoto, vm::removePhoto) }
    }
    // a double tap opens one timer, not two
    RecipeSection(state, suggestions, update, onOpenTimer = dropUnlessResumed { nav.navigate(vm.timerRoute()) }, folds = folds)
    // the dialog closes on the first tap of 묻기, so one question opens one answer screen
    TastingSection(state, update, onAskAi = { query ->
        nav.navigate(Route.NoteHelper(mode = NoteMode.DESCRIBE.key, query = query, returnToForm = true))
    }, folds = folds)
}

/** 모두 접기 · 모두 펼치기 over the form's folding parts ([folded] as they are now); each is shown while it would change something. */
@Composable
private fun FoldAllRow(folded: List<Boolean>, onFoldAll: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (folded.any { !it }) TextLink(FormFold.FOLD_ALL, Ink.textMuted, { onFoldAll(true) }, Modifier.testTag("fold-all"))
        if (folded.any { it }) TextLink(FormFold.UNFOLD_ALL, Ink.textMuted, { onFoldAll(false) }, Modifier.testTag("unfold-all"))
    }
}

/** Sticky bottom bar (web .form-actions). */
@Composable
private fun FormActions(state: FormState, onSave: () -> Unit, onCancel: () -> Unit) {
    // safeDrawing bottom = max(navigation bar, keyboard): the bar stays above the IME without double padding.
    Column(Modifier.fillMaxWidth().background(Ink.bg).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Hairline(color = Ink.text, thickness = Dimens.rule)
        state.error?.takeIf { it.field == null }?.let { ErrorText(it.message, Modifier.padding(horizontal = Dimens.gutter)) }
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                text = when { state.saving -> "저장 중..."; state.isEdit -> "수정 저장"; else -> "저장" },
                onClick = onSave, enabled = !state.saving, modifier = Modifier.weight(1f),
            )
            GhostButton("취소", onClick = onCancel, enabled = !state.saving)
        }
    }
}

/** Copy of the record form's draft banner and leave question, shared with the tests. */
object RecordDraftTexts {
    const val RESTORED = "작성하던 내용을 불러왔어요."
    const val PHOTOS_AGAIN = "고른 봉투 사진은 임시 저장되지 않아요. 다시 골라 주세요."
    const val START_OVER = "새로 쓰기"
    const val LEAVE_TITLE = "작성 중인 내용이 있어요"
    const val LEAVE_NEW = "나가도 지금까지 쓴 내용은 임시 저장돼요. 다음에 새 기록을 열면 이어서 쓸 수 있어요."
    const val LEAVE_EDIT = "나가도 고친 내용은 임시 저장돼요. 다음에 이 기록을 수정할 때 이어서 쓸 수 있어요."
    const val LEAVE_PHOTOS = "새로 고른 봉투 사진은 임시 저장되지 않아 다시 골라야 해요."
    const val LEAVE = "나가기"
    const val DISCARD = "지우고 나가기"
}

/** The form opened with the draft left last time: 새로 쓰기 deletes it and starts over, 닫기 only hides this. */
@Composable
private fun DraftBanner(draft: RestoredDraft, onStartOver: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).background(Ink.surfaceRaised).padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
            .testTag("draft-banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text("✓ " + RecordDraftTexts.RESTORED, style = AppType.small.copy(color = Ink.text))
            if (draft.droppedPhotos > 0) Text(RecordDraftTexts.PHOTOS_AGAIN, style = AppType.small, modifier = Modifier.padding(top = 2.dp))
        }
        TextLink(RecordDraftTexts.START_OVER, Ink.text, onStartOver)
        TextLink("닫기", Ink.textMuted, onClose)
    }
}

/** "같은 커피 다시 기록": where the form was filled from, closed with 닫기. */
@Composable
private fun AgainBanner(text: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).background(Ink.surfaceRaised).padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
            .testTag("again-banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = AppType.small.copy(color = Ink.text), modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        TextLink("닫기", Ink.textMuted, onClose)
    }
}

/**
 * The record form keeps its input as a draft, so leaving loses nothing: [계속 쓰기] stays, [나가기] leaves with the
 * draft kept, [지우고 나가기] deletes it first. A picked bag photo is the exception, and the text says so.
 */
@Composable
private fun DraftLeaveDialog(guard: LeaveGuard, isEdit: Boolean, photosLost: Boolean, onDiscard: () -> Unit) {
    if (!guard.asking) return
    val body = (if (isEdit) RecordDraftTexts.LEAVE_EDIT else RecordDraftTexts.LEAVE_NEW) + if (photosLost) "\n" + RecordDraftTexts.LEAVE_PHOTOS else ""
    AlertDialog(
        onDismissRequest = guard::stay,
        shape = RectangleShape, containerColor = Ink.bg,
        modifier = Modifier.testTag("leave-dialog"),
        title = { Text(RecordDraftTexts.LEAVE_TITLE, style = AppType.title) },
        text = { Text(body, style = AppType.body) },
        confirmButton = { PrimaryButton(LeaveTexts.STAY, small = true, onClick = guard::stay) },
        dismissButton = {
            GhostButton(RecordDraftTexts.LEAVE, small = true, onClick = guard::leave)
            GhostButton(RecordDraftTexts.DISCARD, small = true, danger = true, onClick = { onDiscard(); guard.leave() })
        },
    )
}
