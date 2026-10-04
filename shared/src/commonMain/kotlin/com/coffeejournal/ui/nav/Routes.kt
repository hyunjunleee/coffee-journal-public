package com.coffeejournal.ui.nav

import kotlinx.serialization.Serializable

/** Type-safe destinations. Tab roots come first; every other route is a full-screen page. */
sealed interface Route {
    @Serializable data object Extract : Route
    @Serializable data object Calendar : Route
    @Serializable data object Bean : Route
    @Serializable data object Misc : Route

    /**
     * mode: "extract" (원두), "cafe", "cupping". entryId != null edits an existing record; againFrom is a café record a new
     * one is filled from ("같은 커피 다시 기록").
     */
    @Serializable data class RecordForm(
        val mode: String = "extract",
        val entryId: String? = null,
        val cuppingType: String? = null,
        val againFrom: String? = null,
    ) : Route
    /** "+ 새 기록 추가": the chooser of every kind of record (NewRecordScreen). */
    @Serializable data object NewRecord : Route
    @Serializable data class EntryDetail(val entryId: String) : Route
    @Serializable data object Pantry : Route
    @Serializable data class PantryEditor(val itemId: String? = null) : Route
    @Serializable data class BlendForm(val blendId: String? = null) : Route
    @Serializable data class BookForm(val bookId: String? = null) : Route
    @Serializable data class VideoForm(val videoId: String? = null) : Route
    @Serializable data class ClassForm(val classId: String? = null) : Route
    @Serializable data class MiscForm(val type: String, val itemId: String? = null) : Route
    /** scope: a new roastery's 국내/해외 (the map tab it was added from); null keeps the form's default. */
    @Serializable data class FlatItemForm(val type: String, val itemId: String? = null, val scope: String? = null) : Route
    /** 내 레시피; [newRecipe]: the "새 레시피 만들기" form starts open (from a record form's ⭐ 내 레시피), not from 설정. */
    @Serializable data class MyRecipes(val newRecipe: Boolean = true) : Route
    @Serializable data object Backup : Route
    @Serializable data class CountryDetail(val en: String) : Route
    @Serializable data class NoteDetail(val kind: String, val noteKey: String) : Route
    @Serializable data class VarietyDetail(val varietyKey: String) : Route
    @Serializable data class ProcessDetail(val name: String, val seg: String? = null) : Route
    @Serializable data class RoasteryDetail(val name: String) : Route
    /** 카페 추가: a café by name without a visit; its position is set in the café picker next, or later. */
    @Serializable data object CafeAdd : Route
    @Serializable data object About : Route
    /** "지도에서 위치 지정": target "roastery" (point handed back to the form) or "cafe" (saved); point = "lat,lng". */
    @Serializable data class MapPicker(val target: String, val name: String = "", val scope: String = "국내", val point: String? = null) : Route
    /**
     * 상세 지도 (OpenStreetMap via OpenFreeMap, feature-plan-v2 §1.7). mode "view": the roastery or café pins ([layer],
     * [scope]); mode "pick": a crosshair whose point goes back to the location picker. It opens on [camera]
     * ("lat,lng,zoom"), else fitted to [bounds] ("south,west,north,east"); [focus] selects a pin; [name] is what is placed.
     */
    @Serializable data class DetailMap(
        val mode: String = "view",
        val layer: String = "roastery",
        val scope: String = "국내",
        val camera: String? = null,
        val bounds: String? = null,
        val focus: String? = null,
        val name: String = "",
    ) : Route
    /** recipe: the form's applied recipe as JSON (BrewTimerResult.encodeRecipe); hasLog: the form already has a step log. */
    @Serializable data class BrewTimer(val recipe: String? = null, val hasLog: Boolean = false) : Route
    @Serializable data class BrewCompare(val beanKey: String) : Route
    @Serializable data object Stats : Route
    /** 설정: typefaces, text size, transitions, reminders, the AI helper and sources; the small gear in each tab's header. */
    @Serializable data object Settings : Route
    /**
     * AI 노트 도우미: mode "note" explains the note [query] with web sources, "describe" finds the app's notes for the
     * taste described in [query]; [returnToForm] hands picked notes back to the record form (NoteHelperResult.KEY).
     */
    @Serializable data class NoteHelper(val mode: String, val query: String, val returnToForm: Boolean = false) : Route
}

object FormMode {
    const val EXTRACT = "extract"
    const val CAFE = "cafe"
    const val CUPPING = "cupping"
}
