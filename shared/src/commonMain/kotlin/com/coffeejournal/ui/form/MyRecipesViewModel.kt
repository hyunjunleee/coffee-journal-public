package com.coffeejournal.ui.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coffeejournal.data.repo.MiscRepository
import com.coffeejournal.data.repo.MyRecipeRepository
import com.coffeejournal.domain.model.MiscType
import com.coffeejournal.domain.model.MyRecipe
import com.coffeejournal.domain.rules.Dates
import com.coffeejournal.domain.rules.Ids
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Fields of the "+ 새 레시피 만들기" form (web #new-my-recipe-form). */
@Serializable
data class MyRecipeDraft(
    val name: String = "",
    val dripper: String = "",
    val filter: String = "",
    val grind: String = "",
    val dose: String = "",
    val water: String = "",
    val temp: String = "",
    val time: String = "",
)

/** Autocomplete sources of the new-recipe form (web dripper-datalist / filter-datalist). */
data class RecipeEquipment(val drippers: List<String> = emptyList(), val filters: List<String> = emptyList())

class MyRecipesViewModel(private val repo: MyRecipeRepository, misc: MiscRepository, private val defaultRecipe: DefaultRecipeStore) : ViewModel() {
    /** The 기본 레시피's id ([DefaultRecipe]); null when none is chosen. */
    val defaultId: StateFlow<String?> = defaultRecipe.observeId()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Newest first; the default keeps its place (its card says so), so choosing one moves nothing under the finger. */
    val recipes: StateFlow<List<MyRecipe>> = repo.observeAll()
        .map { list -> list.sortedByDescending { it.createdAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val equipment: StateFlow<RecipeEquipment> = misc.observeAll()
        .map { items -> RecipeEquipment(ownedFirstNames(items, MiscType.DRIPPER), ownedFirstNames(items, MiscType.FILTER)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipeEquipment())

    fun delete(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            defaultRecipe.clearIf(id)
        }
    }

    /** Makes recipe [id] the default for new brews, or none with null. */
    fun setDefault(id: String?) { viewModelScope.launch { defaultRecipe.set(id) } }

    /** Returns false when the name is missing (the only required field). */
    fun create(draft: MyRecipeDraft): Boolean {
        val name = draft.name.trim()
        if (name.isEmpty()) return false
        viewModelScope.launch {
            val now = Dates.nowMillis()
            repo.upsert(
                MyRecipe(
                    id = Ids.newId(now), name = name, fromEntryId = null, beanName = "", rating = 0,
                    dose = draft.dose.trim(), water = draft.water.trim(), temp = draft.temp.trim(),
                    dripper = draft.dripper.trim(), filter = draft.filter.trim(), grind = draft.grind.trim(), time = draft.time.trim(),
                    steps = emptyList(), createdAt = now,
                )
            )
        }
        return true
    }
}
