package com.coffeejournal.ui.form

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coffeejournal.data.photo.PhotoStore
import com.coffeejournal.data.repo.EntryRepository
import com.coffeejournal.data.repo.MiscRepository
import com.coffeejournal.data.repo.MyRecipeRepository
import com.coffeejournal.data.repo.PantryRepository
import com.coffeejournal.data.repo.SaveEntryPipeline
import com.coffeejournal.domain.model.BeanMode
import com.coffeejournal.domain.model.Entry
import com.coffeejournal.domain.model.MiscItem
import com.coffeejournal.domain.model.MiscStatus
import com.coffeejournal.domain.model.MiscType
import com.coffeejournal.domain.model.MyRecipe
import com.coffeejournal.domain.model.PackageType
import com.coffeejournal.domain.model.PantryItem
import com.coffeejournal.domain.model.RecipeStep
import com.coffeejournal.domain.reference.CafeRecipes
import com.coffeejournal.domain.reference.Champions
import com.coffeejournal.domain.rules.BeanNames
import com.coffeejournal.domain.rules.BeanRecords
import com.coffeejournal.domain.rules.Dates
import com.coffeejournal.domain.rules.Ids
import com.coffeejournal.domain.rules.Packages
import com.coffeejournal.domain.rules.PantryRules
import com.coffeejournal.ui.ai.NoteHelperResult
import com.coffeejournal.ui.form.timer.BrewTimerResult
import com.coffeejournal.ui.nav.Route
import com.coffeejournal.ui.theme.DerivationDispatcher
import com.coffeejournal.ui.theme.deriveOffMain
import kotlin.concurrent.Volatile
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Autocomplete sources for the form (web datalists). */
data class FormSuggestions(
    val beanNames: List<String> = emptyList(),
    val blendBeanNames: List<String> = emptyList(),
    val roasteries: List<String> = emptyList(),
    val selections: List<String> = emptyList(),
    val farms: List<String> = emptyList(),
    val drippers: List<String> = emptyList(),
    val filters: List<String> = emptyList(),
    val waters: List<String> = emptyList(),
    val myRecipes: List<MyRecipe> = emptyList(),
    /** Built from the database (the empty defaults are not "nothing registered"). */
    val loaded: Boolean = false,
)

sealed interface FormEvent {
    data class Saved(val entryId: String, val wasEdit: Boolean) : FormEvent
    data object NotFound : FormEvent
}

class RecordFormViewModel(
    private val args: FormArgs,
    private val entries: EntryRepository,
    private val pantry: PantryRepository,
    private val misc: MiscRepository,
    private val myRecipes: MyRecipeRepository,
    private val pipeline: SaveEntryPipeline,
    private val photos: PhotoStore,
    /** Keeps the typed form across process death (e.g. while the camera app is in front). Null in plain unit tests. */
    private val savedState: SavedStateHandle? = null,
    /** Keeps the typed form as a draft in the settings table while it is written. Null in plain unit tests. */
    private val drafts: RecordDrafts? = null,
    /** Which parts of the form are folded, kept on this device. Null in plain unit tests (nothing folded). */
    private val foldStore: FormFoldStore? = null,
    /** The 기본 레시피 a new brew starts with ([DefaultRecipe]). Null in plain unit tests (none). */
    private val defaultRecipe: DefaultRecipeStore? = null,
) : ViewModel() {
    private val restored: FormState? = savedState?.get<String>(STATE_KEY)?.let(FormStateCodec::decode)

    private val _state = MutableStateFlow(restored ?: FormMapper.newState(args.mode, args.cuppingType, Dates.nowMillis()))
    val state: StateFlow<FormState> = _state.asStateFlow()

    /**
     * The form as it was opened: the stored record, or a new form's starting values. Leaving asks, and a draft is
     * kept, only while the input differs from it. Null until loaded; kept in the saved state next to the input.
     */
    private var opened: FormState? = savedState?.get<String>(OPENED_KEY)?.let(FormStateCodec::decode)

    // a new form waits for its draft check (so a restored draft does not replace what was typed meanwhile) and its
    // default recipe or, after process death, for what it opened with when that was not kept; "같은 커피 다시 기록" waits
    // for the record it copies
    private val _loaded = MutableStateFlow(
        args.entryId == null && args.againFrom == null && ((restored != null && opened != null) || (drafts == null && defaultRecipe == null)),
    )
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val draftKey = RecordDrafts.keyFor(args)

    /** Set once the record is saved or the form is left: nothing more is written to the draft. */
    private var draftClosed = false

    private val _restoredDraft = MutableStateFlow(savedState?.get<Int>(DRAFT_NOTICE_KEY)?.let(::RestoredDraft))
    /** The form opened with the draft left last time (the banner with 새로 쓰기); null otherwise or once closed. */
    val restoredDraft: StateFlow<RestoredDraft?> = _restoredDraft.asStateFlow()

    private val _folds = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** Which parts of the form are folded, per kind of record ([FormFold]): read before the form shows. */
    val folds: StateFlow<Map<String, Boolean>> = _folds.asStateFlow()

    /** Whether this form takes the default recipe when it opens (a new brew): the form then says so while it holds it. */
    val takesDefaultRecipe: Boolean = DefaultRecipe.appliesTo(args)

    /** The default recipe (id, name) the form opened with; kept in the saved state next to [opened]. */
    private var openedWith: Pair<String, String>? = savedState?.get<String>(OPENED_RECIPE_KEY)?.split('\u0000')
        ?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    /**
     * The default recipe's name while the form holds it as it opened with it, and it is still the default ([defaultId]);
     * null otherwise (no default, a draft or the user changed the recipe, another recipe applied, the default changed).
     */
    fun startedWithDefault(s: FormState, defaultId: String?): String? {
        val (id, name) = openedWith ?: return null
        val start = opened ?: return null
        return name.takeIf { id == defaultId && DefaultRecipe.holdsOpenedRecipe(s, start) }
    }

    /** The 기본 레시피's id ([DefaultRecipe.KEY]), for the ⭐ 내 레시피 cards; null when none is chosen. */
    val defaultRecipeId: StateFlow<String?> = (defaultRecipe?.observeId() ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Makes recipe [id] the default for the next new brews, or none with null; this form is left as it is. */
    fun setDefaultRecipe(id: String?) {
        val store = defaultRecipe ?: return
        viewModelScope.launch { store.set(id) }
    }

    /** Folds or unfolds [part] for records of the kind being written, for this and the next forms. */
    fun toggleFold(part: String) {
        val category = _state.value.category
        setFolds(_folds.value + (FormFold.key(category, part) to !FormFold.isFolded(_folds.value, category, part)))
    }

    /** 모두 접기 / 모두 펼치기: every part of this kind of record. */
    fun foldAll(folded: Boolean) = setFolds(FormFold.all(_folds.value, _state.value.category, folded))

    private fun setFolds(next: Map<String, Boolean>) {
        _folds.value = next
        foldStore?.save(next)
    }

    /** The running save, so a second tap on 저장 while it is in flight does nothing. */
    private var saveJob: Job? = null

    private val _events = MutableSharedFlow<FormEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<FormEvent> = _events

    private var existing: Entry? = null
    @Volatile private var allEntries: List<Entry> = emptyList()

    /** Built off the main thread (gap #10): it flattens every record. */
    val suggestions: StateFlow<FormSuggestions> = combine(
        entries.observeAll(), pantry.observeAll(), misc.observeAll(), myRecipes.observeAll(),
    ) { ens, items, miscItems, recipes -> SuggestionSources(ens, items, miscItems, recipes) }
        .deriveOffMain { src ->
            allEntries = src.entries
            buildSuggestions(src.entries, src.pantry, src.misc, src.recipes)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FormSuggestions())

    private class SuggestionSources(val entries: List<Entry>, val pantry: List<PantryItem>, val misc: List<MiscItem>, val recipes: List<MyRecipe>)

    init {
        if (savedState != null) {
            _state.drop(1).onEach { savedState[STATE_KEY] = FormStateCodec.encode(it) }.launchIn(viewModelScope)
        }
        if (drafts != null) {
            // the pause is timed off the main thread; the write itself is asked for on it, in order with leaving
            @OptIn(FlowPreview::class)
            _state.drop(1).debounce(DRAFT_DELAY_MS).flowOn(DerivationDispatcher).onEach(::writeDraft).launchIn(viewModelScope)
        }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        foldStore?.let { _folds.value = it.load() }
        val id = args.entryId
        if (id != null) {
            val en = entries.getById(id)
            if (en == null) { drafts?.delete(draftKey); _events.emit(FormEvent.NotFound); return }
            existing = en
            val repeat = FormMapper.hasEarlierSameBean(en, entries.getAll())
            val stored = FormMapper.fromEntry(en, args.mode).copy(repeatBean = repeat, autofillBanner = repeat)
            if (restored == null) {
                val draft = takeDraft(stored)
                markOpened(stored)
                _state.value = draft ?: stored
            } else if (opened == null) markOpened(stored)
            _loaded.value = true
            return
        }
        // 최근 원두 기록의 분쇄도·사용한 물을 기본값으로 (web openForm)
        val brews = entries.getAll().filter { it.isBrew }.sortedByDescending { it.createdAt }
        val lastGrind = brews.firstOrNull { it.grind.isNotBlank() }?.grind ?: ""
        val lastWater = brews.firstOrNull { it.waterType.isNotBlank() }?.waterType ?: ""
        // "같은 커피 다시 기록": the café record copied (a plain new café form when it is gone)
        val source = args.againFrom?.let { entries.getById(it) }
        // 기본 레시피: a new brew starts with it (a deleted one is none)
        val start = if (takesDefaultRecipe) defaultRecipe?.id()?.let { myRecipes.getById(it) } else null
        val keepOpenedWith = { if (start != null) setOpenedWith(start.id to start.name) }
        val withDefaults = { s: FormState ->
            when {
                source != null -> FormMapper.again(source, s.createdAt, s.draftId)
                s.mode == com.coffeejournal.ui.nav.FormMode.CAFE -> s
                else -> s.copy(grind = s.grind.ifBlank { lastGrind }, waterType = s.waterType.ifBlank { lastWater })
                    .let { brew -> start?.let { DefaultRecipe.startWith(brew, it) } ?: brew }
            }
        }
        if (restored != null) {
            // after process death the saved state wins; one kept by an older version is measured against a new form
            if (opened == null) {
                markOpened(withDefaults(FormMapper.newState(args.mode, args.cuppingType, restored.createdAt, draftId = restored.draftId)))
                keepOpenedWith()
            }
            _loaded.value = true
            return
        }
        _state.update(withDefaults)
        keepOpenedWith()
        val opening = _state.value
        val draft = takeDraft(opening)
        markOpened(opening)
        draft?.let { _state.value = it }
        _loaded.value = true
    }

    private fun setOpenedWith(recipe: Pair<String, String>) {
        openedWith = recipe
        savedState?.set(OPENED_RECIPE_KEY, recipe.first + "\u0000" + recipe.second)
    }

    private fun markOpened(start: FormState) {
        opened = start
        savedState?.set(OPENED_KEY, FormStateCodec.encode(start))
    }

    /** The draft left last time, when there is one that holds more than the form opens with (else it is dropped). */
    private suspend fun takeDraft(start: FormState): FormState? {
        val store = drafts ?: return null
        store.pruneExpired()
        val draft = store.load(draftKey) ?: return null
        val state = FormDrafts.reopened(draft, args)
        if (state.editingId != args.entryId || !FormDrafts.changed(start, state)) {
            store.delete(draftKey)
            return null
        }
        _restoredDraft.value = RestoredDraft(draft.droppedPhotos)
        savedState?.set(DRAFT_NOTICE_KEY, draft.droppedPhotos)
        return state
    }

    /** Keeps [s] as the draft, or deletes the draft once the input is back to the form as it was opened. */
    private fun writeDraft(s: FormState) {
        val store = drafts ?: return
        val start = opened ?: return
        if (draftClosed || s.saving) return
        if (FormDrafts.changed(start, s)) store.put(draftKey, FormDrafts.draftOf(s, Dates.nowMillis())) else store.delete(draftKey)
    }

    /** Whether the input differs from the form as it was opened: leaving then asks first. */
    fun hasChanges(): Boolean = opened?.let { FormDrafts.changed(it, _state.value) } ?: false

    /** 나가기 (and any other way out): the input as it is now becomes the draft, without waiting for the debounce. */
    fun keepDraft() {
        writeDraft(_state.value)
        draftClosed = true
    }

    /** 지우고 나가기: the draft is deleted and the form is left. */
    fun discardDraft() {
        draftClosed = true
        drafts?.delete(draftKey)
    }

    /** The banner's 새로 쓰기: the draft is deleted and the form starts over as it opens without one. */
    fun startOver() {
        val start = opened ?: return
        drafts?.delete(draftKey)
        closeDraftNotice()
        _state.value = start
    }

    fun closeDraftNotice() {
        _restoredDraft.value = null
        savedState?.remove<Int>(DRAFT_NOTICE_KEY)
    }

    /** Leaving by a way the dialog did not see (a pop from elsewhere) still leaves the draft up to date. */
    override fun onCleared() {
        if (!draftClosed) keepDraft()
    }

    /** Every field change goes through here so the computed 총 추출시간 stays in sync. */
    fun update(transform: (FormState) -> FormState) {
        _state.update { FormMapper.withStepsTime(transform(it)) }
    }

    fun onNameTyped(name: String) = update { FormMapper.onNameTyped(it, name) }

    /** Web f-name blur: same-bean records (editing record excluded) feed the empty bag-info fields. */
    fun onNameBlur() {
        val s = _state.value
        val key = BeanNames.coreBeanName(s.name)
        if (key.isBlank()) { update { it.copy(autofillBanner = false, repeatBean = false) }; return }
        viewModelScope.launch {
            val all = allEntries.ifEmpty { entries.getAll() }
            val matches = all.filter { it.id != s.editingId && BeanNames.coreBeanName(it.name) == key }
            update { FormMapper.autofill(it, matches) }
        }
    }

    fun applyChampion(c: Champions.Champion) = update { FormMapper.applyChampion(it, c) }
    fun applyCafeRecipe(r: CafeRecipes.Recipe) = update { FormMapper.applyCafeRecipe(it, r) }
    fun applyMyRecipe(r: MyRecipe) = update { FormMapper.applyMyRecipe(it, r) }
    fun deleteMyRecipe(id: String) {
        viewModelScope.launch {
            myRecipes.delete(id)
            defaultRecipe?.clearIf(id)
        }
    }

    /** The brew timer's rows replace the step log (the timer asked before replacing a log of the user's own). */
    fun applyTimerSteps(steps: List<RecipeStep>) = update { it.copy(steps = steps.map(StepForm::from)) }

    /** Notes picked in the AI note helper go after 내가 느낀 노트, each only once (case-insensitive). */
    fun addActualNotes(notes: List<String>) = update { it.copy(actualNotes = NoteHelperResult.merge(it.actualNotes, notes)) }

    /**
     * Opens the brew timer with the applied recipe, telling it whether a log of the user's own would be replaced. The
     * default recipe's rows a new brew opened with, untouched, are not the user's own: the timer replaces them freely.
     */
    fun timerRoute(): Route.BrewTimer {
        val s = _state.value
        val openedRows = openedWith != null && opened?.let { DefaultRecipe.sameSteps(s, it) } == true
        return Route.BrewTimer(
            recipe = BrewTimerResult.encodeRecipe(s.appliedRecipeRef?.takeIf { it.steps.isNotEmpty() }),
            hasLog = FormMapper.hasOwnStepLog(s) && !openedRows,
        )
    }

    fun setPhoto(index: Int, bytes: ByteArray) = update { s ->
        s.copy(bagPhotos = s.bagPhotos.mapIndexed { i, slot -> if (i == index) PhotoSlot(existingName = null, pending = bytes) else slot })
    }

    fun removePhoto(index: Int) = update { s ->
        s.copy(bagPhotos = s.bagPhotos.mapIndexed { i, slot -> if (i == index) PhotoSlot() else slot })
    }

    fun photoModel(slot: PhotoSlot): Any? = slot.pending ?: slot.existingName?.let { "file://" + photos.pathFor(it) }

    /**
     * Saves once per tap: a tap while a save is running is ignored. A new record keeps the form's [FormState.draftId],
     * so pressing 저장 again after a failure upserts the same row instead of adding a second copy.
     */
    fun save() {
        if (saveJob?.isActive == true || _state.value.saving) return
        val committed = FormMapper.commitPendingChips(_state.value)
        val error = FormMapper.validate(committed)
        if (error != null) { _state.value = committed.copy(error = error); return }
        val s = committed.copy(error = null, saving = true, draftId = committed.draftId.ifBlank { Ids.newId() })
        _state.value = s
        // NonCancellable: leaving the screen (e.g. system back) must not stop the save between the entry write and the
        // sibling / auto-registration / pantry steps; the event then simply has no listener.
        saveJob = viewModelScope.launch { withContext(NonCancellable) { persist(s) } }
    }

    private suspend fun persist(s: FormState) {
        val created = mutableListOf<String>()
        try {
            val id = s.editingId ?: s.draftId
            val isNew = s.editingId == null
            val previous = existing?.bagPhotos ?: emptyList()
            val finalPhotos = mutableListOf<String>()
            for (slot in s.bagPhotos) {
                val pending = slot.pending
                when {
                    pending != null -> photos.save(pending).also { created += it; finalPhotos += it }
                    slot.existingName != null -> finalPhotos += slot.existingName
                }
            }
            val entry = FormMapper.toEntry(s, id, existing, finalPhotos, Dates.nowMillis())
            pipeline.save(entry, isNew)
            // saved: the draft has done its job
            draftClosed = true
            drafts?.delete(draftKey)
            previous.filter { it !in finalPhotos }.forEach { runCatching { photos.delete(it) } }
            _events.emit(FormEvent.Saved(id, wasEdit = !isNew))
        } catch (e: Exception) {
            // the pending photos stay in the form, so a retry saves them again; drop this attempt's copies
            created.forEach { runCatching { photos.delete(it) } }
            _state.update { it.copy(saving = false, error = FormError(null, "저장하지 못했어요: ${e.message ?: "알 수 없는 오류"}")) }
        }
    }

    /** Web populateBeanNameDatalist: only opened, standard, non-blend, non-decaf bags — beans being drunk now, not past records. */
    private fun buildSuggestions(ens: List<Entry>, items: List<PantryItem>, miscItems: List<MiscItem>, recipes: List<MyRecipe>): FormSuggestions {
        val seen = HashSet<String>()
        val beanNames = mutableListOf<String>()
        items.filter { it.isOpened && Packages.pantryPackageType(it) == PackageType.STANDARD && !BeanNames.nameSaysBlend(it.name) && !BeanNames.isDecaf(it.name, "", "") }
            .sortedWith(compareBy<PantryItem> { PantryRules.peakStartMillis(it) }.thenByDescending { it.openedAt ?: it.createdAt })
            .forEach { item -> val k = BeanNames.coreBeanName(item.name); if (k.isNotBlank() && seen.add(k)) beanNames += item.name }

        val blendSeen = HashSet<String>()
        val blendNames = ens.filter { it.isBrew && it.beanMode != BeanMode.CUSTOM_BLEND && it.name.isNotBlank() }
            .sortedByDescending { it.createdAt }
            .mapNotNull { en -> val k = BeanNames.coreBeanName(en.name); if (k.isNotBlank() && blendSeen.add(k)) en.name else null }

        fun ownedFirst(type: String): List<String> = ownedFirstNames(miscItems, type)

        val farmSeen = HashSet<String>()
        val farms = BeanRecords.flatten(ens, blendBeans = true).filter { it.farmProducer.isNotBlank() }.sortedByDescending { it.createdAt }
            .mapNotNull { r -> val v = r.farmProducer.trim(); if (farmSeen.add(v.lowercase())) v else null }

        val waterSeen = HashSet<String>()
        val waters = mutableListOf<String>()
        ownedFirst(MiscType.WATER).forEach { if (waterSeen.add(it.trim().lowercase())) waters += it.trim() }
        ens.filter { it.waterType.isNotBlank() }.sortedByDescending { it.createdAt }
            .forEach { if (waterSeen.add(it.waterType.trim().lowercase())) waters += it.waterType.trim() }

        return FormSuggestions(
            beanNames = beanNames,
            blendBeanNames = blendNames,
            roasteries = miscItems.filter { it.type == MiscType.SOURCE }.map { it.name }.distinct(),
            selections = miscItems.filter { it.type == MiscType.SELECTION }.map { it.name }.distinct(),
            farms = farms,
            drippers = ownedFirst(MiscType.DRIPPER),
            filters = ownedFirst(MiscType.FILTER),
            waters = waters,
            myRecipes = recipes.sortedByDescending { it.createdAt },
            loaded = true,
        )
    }

    private companion object {
        const val STATE_KEY = "recordForm"
        const val OPENED_KEY = "recordForm.opened"
        const val DRAFT_NOTICE_KEY = "recordForm.draftNotice"
        const val OPENED_RECIPE_KEY = "recordForm.openedRecipe"
        /** A draft is written this long after the last change. */
        const val DRAFT_DELAY_MS = 700L
    }
}

/** Equipment names of one misc type, owned items first (web dripper-datalist / filter-datalist). */
internal fun ownedFirstNames(miscItems: List<MiscItem>, type: String): List<String> = miscItems.filter { it.type == type }
    .sortedWith(compareBy<MiscItem> { if (it.status.isBlank() || it.status == MiscStatus.OWNED) 0 else 1 }.thenByDescending { it.createdAt })
    .map { it.name }.distinct()

/** JSON form of [FormState] for the SavedStateHandle; picked photo bytes are left out (stored file names are kept). */
internal object FormStateCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(state: FormState): String = json.encodeToString(FormState.serializer(), state)

    /** Null when the text cannot be read (e.g. written by an older app version). Transient flags come back reset. */
    fun decode(text: String): FormState? = runCatching { json.decodeFromString(FormState.serializer(), text) }.getOrNull()
}
