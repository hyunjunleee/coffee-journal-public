package com.coffeejournal.ui.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coffeejournal.data.photo.PhotoStore
import com.coffeejournal.data.repo.BeanMetaRepository
import com.coffeejournal.data.repo.EntryRepository
import com.coffeejournal.data.repo.MyRecipeRepository
import com.coffeejournal.domain.model.Entry
import com.coffeejournal.domain.model.MyRecipe
import com.coffeejournal.domain.rules.BeanNames
import com.coffeejournal.domain.rules.Dates
import com.coffeejournal.domain.rules.Ids
import com.coffeejournal.domain.rules.Packages
import com.coffeejournal.ui.extract.ExtractGrouping
import com.coffeejournal.ui.theme.deriveOffMain
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DetailEvent {
    data object Deleted : DetailEvent
    data class RecipeSaved(val name: String, val asDefault: Boolean = false) : DetailEvent
}

class EntryDetailViewModel(
    private val entryId: String,
    private val entries: EntryRepository,
    private val beanMeta: BeanMetaRepository,
    private val myRecipes: MyRecipeRepository,
    private val photos: PhotoStore,
    /** 기본 레시피 ([DefaultRecipe]): a recipe saved from here can become it. Null in plain unit tests. */
    private val defaultRecipe: DefaultRecipeStore? = null,
) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val entry: Entry? = null,
        /** Other records of the same bean (web siblings), used to complete missing bag info. */
        val siblings: List<Entry> = emptyList(),
        val isBest: Boolean = false,
        /** Home group keys this record is listed under (each component for a custom blend); empty when it cannot be best. */
        val bestKeys: List<String> = emptyList(),
    )

    @Volatile private var deleting = false

    // the sibling scan normalises every record's name, so it runs off the main thread (gap #10)
    val state: StateFlow<UiState> = combine(entries.observeAll(), beanMeta.observeBest()) { all, best -> all to best }.deriveOffMain { (all, best) ->
        val en = all.firstOrNull { it.id == entryId }
        if (en == null) UiState(loading = deleting, entry = null)
        else {
            val key = BeanNames.coreBeanName(en.name)
            val keys = bestKeysOf(en)
            UiState(
                loading = false,
                entry = en,
                siblings = if (key.isBlank()) emptyList() else all.filter { it.id != en.id && BeanNames.coreBeanName(it.name) == key },
                isBest = keys.any { best[it] == en.id },
                bestKeys = keys,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val _events = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DetailEvent> = _events

    fun photoPath(fileName: String): String = "file://" + photos.pathFor(fileName)

    fun delete() {
        deleting = true
        viewModelScope.launch {
            entries.delete(entryId)
            _events.emit(DetailEvent.Deleted)
        }
    }

    /**
     * Sets or clears this record as the best recipe under the same keys the home groups read, so the ⭐ badge on the
     * detail and the group's best card always agree. Clearing only touches keys that point at this record.
     */
    fun toggleBest() {
        val s = state.value
        val en = s.entry ?: return
        if (s.bestKeys.isEmpty()) return
        viewModelScope.launch {
            if (s.isBest) {
                val best = beanMeta.getBest().associate { it.beanKey to it.entryId }
                s.bestKeys.filter { best[it] == en.id }.forEach { beanMeta.setBest(it, null) }
            } else {
                s.bestKeys.forEach { beanMeta.setBest(it, en.id) }
            }
        }
    }

    /** Web saveAsMyRecipe: the record's brew parameters and steps become a reusable recipe, the default one with [asDefault]. */
    fun saveAsMyRecipe(name: String, asDefault: Boolean = false) {
        val en = state.value.entry ?: return
        if (en.steps.isEmpty()) return
        val finalName = name.trim().ifBlank { EntryDisplay.defaultRecipeName(en) }
        viewModelScope.launch {
            val now = Dates.nowMillis()
            val id = Ids.newId(now)
            myRecipes.upsert(
                MyRecipe(
                    id = id, name = finalName, fromEntryId = en.id, beanName = en.name, rating = 0,
                    dose = en.dose, water = en.water, temp = en.temp, dripper = en.dripper, filter = en.filter, grind = en.grind, time = en.time,
                    steps = en.steps, createdAt = now,
                )
            )
            val madeDefault = asDefault && defaultRecipe != null
            if (madeDefault) defaultRecipe?.set(id)
            _events.emit(DetailEvent.RecipeSaved(finalName, madeDefault))
        }
    }
}

/**
 * Best-recipe keys of a record: the home group keys (ExtractGrouping.groupKeys: the core name, or each component's for a
 * custom blend), only for brew records, which are the group's recipe candidates.
 */
internal fun bestKeysOf(en: Entry): List<String> =
    if (Packages.isBrew(en)) ExtractGrouping.groupKeys(en) else emptyList()
