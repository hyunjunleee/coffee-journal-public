package com.coffeejournal.android

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coffeejournal.data.repo.BlendRepository
import com.coffeejournal.data.repo.CafePlaceRepository
import com.coffeejournal.data.repo.EntryRepository
import com.coffeejournal.data.repo.MiscRepository
import com.coffeejournal.data.repo.PantryRepository
import com.coffeejournal.data.repo.SettingsRepository
import com.coffeejournal.data.repo.StudyRepository
import com.coffeejournal.domain.model.MiscType
import com.coffeejournal.domain.rules.Dates
import com.coffeejournal.ui.bean.b.BlendFormViewModel
import com.coffeejournal.ui.bean.b.FlatItemFormViewModel
import com.coffeejournal.ui.calendar.forms.BookFormViewModel
import com.coffeejournal.ui.calendar.forms.ClassFormViewModel
import com.coffeejournal.ui.extract.PantryEditorViewModel
import com.coffeejournal.ui.form.FormArgs
import com.coffeejournal.ui.form.RecordDraftTexts
import com.coffeejournal.ui.form.RecordDrafts
import com.coffeejournal.ui.form.RecordFormViewModel
import com.coffeejournal.ui.misc.MiscFormViewModel
import com.coffeejournal.ui.nav.FormMode
import com.coffeejournal.ui.nav.Route
import com.coffeejournal.ui.nav.appGraph
import com.coffeejournal.ui.theme.CoffeeJournalTheme
import com.coffeejournal.ui.theme.LeaveTexts
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * 사용자 요청 (2026-09-28): an accidental back must not lose what was typed. The record form keeps a real-time draft
 * (device key, restored with a banner next time), and every input form asks before it is left with unsaved changes —
 * but never right after an existing item was opened, and never after a successful save.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class DraftFlowTest : FlowTestBase() {

    private val namePlaceholder = "예: 콜롬비아 라 플라타 게이샤 워시드"
    private val newKey = RecordDrafts.keyFor(FormArgs(FormMode.EXTRACT))
    private val store = ViewModelStore()

    @After
    fun clearViewModels() = store.clear()

    private fun stored(key: String): String? = runBlocking { koinGet<SettingsRepository>().get(key) }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun leaveDialog(title: String): SemanticsMatcher = hasText(title) and hasAnyAncestor(isDialog())

    private lateinit var nav: NavHostController

    /** The app's graph without the tab bar, starting at [start]; [go] opens the next screen on top. */
    private fun startNav(start: Route = Route.Misc) {
        compose.setContent {
            CoffeeJournalTheme {
                val n = rememberNavController()
                LaunchedEffect(n) { nav = n }
                NavHost(navController = n, startDestination = start) { appGraph(n) }
            }
        }
        settle()
    }

    private fun go(route: Route) {
        compose.runOnUiThread { nav.navigate(route) }
        settle()
    }

    // ───────────────────────── the record form's draft ─────────────────────────

    @Test
    fun recordForm_backKeepsTheDraft_reopeningRestoresIt_savingClearsIt() {
        SampleData.seed()
        launchApp()
        openNewRecord()
        waitForText("새 기록")
        typeInto(namePlaceholder, "임시 저장 원두")
        typeInto("20", "16")
        // written a moment after typing stops, before anyone leaves
        waitUntil("the draft is written") { stored(newKey)?.contains("임시 저장 원두") == true }

        back()
        waitFor(leaveDialog(RecordDraftTexts.LEAVE_TITLE))
        assertTrue(has(hasText(RecordDraftTexts.LEAVE_NEW)))
        clickNode(dialogButton(LeaveTexts.STAY))
        waitGone(isDialog())
        assertTrue("계속 쓰기 stays on the form", has(field("임시 저장 원두")))

        back()
        waitFor(leaveDialog(RecordDraftTexts.LEAVE_TITLE))
        clickNode(dialogButton(RecordDraftTexts.LEAVE))
        waitForText("+ 새 기록 추가")
        assertTrue(runBlocking { koinGet<EntryRepository>().getAll() }.none { it.name == "임시 저장 원두" })

        // the next new record opens with it, and says so
        openNewRecord()
        waitForText("✓ " + RecordDraftTexts.RESTORED)
        assertTrue(has(field("임시 저장 원두")))
        assertTrue(has(field("16")))

        clickText("저장")
        waitUntil("the new record's detail") { has(hasText("임시 저장 원두")) && has(button("수정")) && !has(hasText("새 기록")) }
        waitUntil("the draft is gone once saved") { stored(newKey) == null }
        assertEquals("16", runBlocking { koinGet<EntryRepository>().getAll() }.single { it.name == "임시 저장 원두" }.dose)

        // a new form after the save starts blank, and backing out of it does not ask
        back()
        openNewRecord()
        waitForText("새 기록")
        waitFor(field(namePlaceholder))
        assertFalse(has(hasText("✓ " + RecordDraftTexts.RESTORED)))
        back()
        waitForText("+ 새 기록 추가")
        assertFalse(has(isDialog()))
        assertNull(stored(newKey))
    }

    @Test
    fun recordForm_systemBack_discardAndLeave_deletesTheDraft() {
        SampleData.seed()
        launchApp()
        openNewRecord()
        waitForText("새 기록")
        typeInto(namePlaceholder, "지울 원두")
        waitUntil("the draft is written") { stored(newKey) != null }

        systemBack()
        waitFor(leaveDialog(RecordDraftTexts.LEAVE_TITLE))
        clickNode(dialogButton(RecordDraftTexts.DISCARD))
        waitForText("+ 새 기록 추가")
        waitUntil("the draft is deleted") { stored(newKey) == null }
        // nothing writes it back afterwards (a late debounced write, the view model being cleared)
        Thread.sleep(1_200)
        settle()
        assertNull(stored(newKey))

        openNewRecord()
        waitForText("새 기록")
        waitFor(field(namePlaceholder))
        assertFalse(has(hasText("✓ " + RecordDraftTexts.RESTORED)))
        assertFalse(has(field("지울 원두")))
    }

    @Test
    fun recordForm_existingRecord_noQuestionUntilChanged_editDraft_startOver() {
        SampleData.seed()
        startNav(Route.EntryDetail("e1"))
        val editKey = RecordDrafts.keyFor(FormArgs(entryId = "e1"))
        waitFor(button("수정"))

        // right after opening, both backs leave at once
        clickText("수정")
        waitFor(field("에티오피아 예가체프 워카 첼베사"))
        back()
        waitUntil("back on the detail") { has(button("삭제")) && !has(hasText("기록 수정")) }
        assertFalse(has(isDialog()))
        clickText("수정")
        waitFor(field("에티오피아 예가체프 워카 첼베사"))
        systemBack()
        waitUntil("back on the detail") { has(button("삭제")) && !has(hasText("기록 수정")) }
        assertFalse(has(isDialog()))
        assertNull("an untouched record leaves no draft", stored(editKey))

        // a change: 취소 asks, 나가기 keeps it as the edit's draft and leaves the record as it was
        clickText("수정")
        waitFor(field("에티오피아 예가체프 워카 첼베사"))
        replaceIn("15", "14")
        clickText("취소")
        waitFor(leaveDialog(RecordDraftTexts.LEAVE_TITLE))
        assertTrue(has(hasText(RecordDraftTexts.LEAVE_EDIT)))
        clickNode(dialogButton(RecordDraftTexts.LEAVE))
        waitUntil("back on the detail") { has(button("삭제")) && !has(hasText("기록 수정")) }
        assertEquals("15", runBlocking { koinGet<EntryRepository>().getById("e1") }!!.dose)
        waitUntil("the edit's draft") { stored(editKey)?.contains("\"dose\":\"14\"") == true }
        assertNull("the new-record draft is another one", stored(newKey))

        clickText("수정")
        waitForText("✓ " + RecordDraftTexts.RESTORED)
        assertTrue(has(field("14")))
        // 새로 쓰기: the stored record again, and nothing left to ask about
        clickText(RecordDraftTexts.START_OVER)
        waitFor(field("15"))
        assertFalse(has(hasText("✓ " + RecordDraftTexts.RESTORED)))
        waitUntil("the draft is deleted") { stored(editKey) == null }
        back()
        waitUntil("back on the detail") { has(button("삭제")) && !has(hasText("기록 수정")) }
        assertFalse(has(isDialog()))
    }

    // ───────────────────────── the draft behind the view model ─────────────────────────

    private inline fun <reified V : ViewModel> vm(key: String, crossinline create: () -> V): V =
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
        })[key, V::class.java]

    private fun formVm(key: String, handle: SavedStateHandle = SavedStateHandle(), args: FormArgs = FormArgs()) = vm(key) {
        RecordFormViewModel(args, koinGet(), koinGet(), koinGet(), koinGet(), koinGet(), koinGet(), handle, koinGet<RecordDrafts>())
    }

    private fun SavedStateHandle.copied(): SavedStateHandle = SavedStateHandle(keys().associateWith { get<Any>(it) })

    /**
     * Runs the main looper (the test thread) until [condition] holds; no Compose content is involved, so this does
     * not wait for Compose to become idle.
     */
    private fun pump(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for: $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun recordForm_processDeathRestoreWinsOverTheDraft() {
        // a draft from an earlier visit
        val first = formVm("first")
        pump("loaded") { first.loaded.value }
        first.onNameTyped("초안 원두")
        first.keepDraft()
        pump("draft kept") { stored(newKey)?.contains("초안 원두") == true }

        // the next visit opens with it and goes on typing
        val handle = SavedStateHandle()
        val second = formVm("second", handle)
        pump("loaded") { second.loaded.value }
        assertEquals("초안 원두", second.state.value.name)
        assertNotNull(second.restoredDraft.value)
        second.update { it.copy(notes = "카메라 켜기 전 메모") }
        pump("kept in the saved state") { handle.get<String>("recordForm")?.contains("카메라 켜기 전 메모") == true }
        // meanwhile the draft in the settings table says something else
        pump("the draft follows") { stored(newKey)?.contains("카메라 켜기 전 메모") == true }
        runBlocking { koinGet<SettingsRepository>().put(newKey, stored(newKey)!!.replace("카메라 켜기 전 메모", "다른 메모")) }

        // process death: the saved state wins over the draft, and still knows what the form opened with
        val after = formVm("after", handle.copied())
        pump("restored") { after.loaded.value }
        assertEquals("초안 원두", after.state.value.name)
        assertEquals("카메라 켜기 전 메모", after.state.value.notes)
        assertNotNull("the banner comes back with it", after.restoredDraft.value)
        assertTrue(after.hasChanges())

        // 새로 쓰기 on the next visit: the form as it opens without a draft, and the draft is gone
        val blankHandle = SavedStateHandle()
        val blank = formVm("blank", blankHandle)
        pump("loaded") { blank.loaded.value }
        assertEquals("the draft as the settings table had it", "다른 메모", blank.state.value.notes)
        blank.startOver()
        assertNull(blank.restoredDraft.value)
        assertEquals("", blank.state.value.name)
        assertFalse(blank.hasChanges())
        pump("draft deleted") { stored(newKey) == null }

        // that blank form kept by an older version (no opened form next to it): a new form is the measure
        pump("kept") { blankHandle.get<String>("recordForm")?.contains("다른 메모") == false }
        val older = formVm("older", SavedStateHandle(mapOf("recordForm" to blankHandle.get<String>("recordForm"))))
        pump("restored") { older.loaded.value }
        assertFalse("an untouched form does not ask", older.hasChanges())
        older.onNameTyped("바뀐 원두")
        assertTrue(older.hasChanges())
    }

    @Test
    fun recordForm_pickedPhotoIsNotKept_andSaid_oldDraftsAreNotOffered() {
        val first = formVm("first")
        pump("loaded") { first.loaded.value }
        first.onNameTyped("사진 고른 원두")
        first.setPhoto(0, byteArrayOf(1, 2, 3))
        first.keepDraft()
        pump("draft kept") { stored(newKey)?.contains("사진 고른 원두") == true }
        val next = formVm("next")
        pump("loaded") { next.loaded.value }
        assertEquals("사진 고른 원두", next.state.value.name)
        assertEquals(1, next.restoredDraft.value?.droppedPhotos)
        assertTrue("the slot is empty again", next.state.value.bagPhotos.none { it.hasImage })

        next.discardDraft()
        pump("discarded") { stored(newKey) == null }

        // a draft last written 31 days ago is not offered, and is deleted
        val settings = koinGet<SettingsRepository>()
        runBlocking { settings.put(newKey, """{"savedAt":${Dates.nowMillis() - 31 * Dates.DAY_MS},"state":{"name":"오래된 원두"}}""") }
        val stale = formVm("stale")
        pump("loaded") { stale.loaded.value }
        assertEquals("", stale.state.value.name)
        assertNull(stale.restoredDraft.value)
        pump("deleted") { stored(newKey) == null }
        // a week old: still offered
        runBlocking { settings.put(newKey, """{"savedAt":${Dates.nowMillis() - 7 * Dates.DAY_MS},"state":{"name":"지난주 원두"}}""") }
        val recent = formVm("recent")
        pump("loaded") { recent.loaded.value }
        assertEquals("지난주 원두", recent.state.value.name)
    }

    // ───────────────────────── the other forms ask before dropping input ─────────────────────────

    /** Opens [route], waits for [loaded] (a field showing the stored value), and returns once it is left. */
    private fun backWithoutQuestion(route: Route, loaded: String, viaSystemBack: Boolean) {
        go(route)
        waitFor(field(loaded))
        if (viaSystemBack) systemBack() else back()
        waitGone(field(loaded))
        assertFalse("${route::class.simpleName}: no question for an untouched form", has(isDialog()))
    }

    @Test
    fun everyForm_untouchedExistingItem_leavesWithoutAsking_changedOneAsks() {
        SampleData.seed()
        startNav(Route.Misc)
        val forms = listOf(
            Route.PantryEditor("p2") to "과테말라 안티구아 부르봉",
            Route.MiscForm(type = MiscType.DRIPPER, itemId = "m1") to "오리가미 드리퍼 S",
            Route.FlatItemForm(type = MiscType.SOURCE, itemId = "m4") to "커피 리브레",
            Route.BlendForm("bl1") to "에티오피아+콜롬비아",
            Route.BookForm("b1") to "커핑 바이블",
            Route.ClassForm("c1") to "홈카페 원데이 클래스",
            Route.VideoForm("v1") to "추출 변수와 맛의 관계",
            Route.RecordForm(mode = FormMode.EXTRACT, entryId = "e1") to "에티오피아 예가체프 워카 첼베사",
            Route.RecordForm(mode = FormMode.CAFE, entryId = "e4") to "브라질 세하도 내추럴",
        )
        for ((route, value) in forms) {
            backWithoutQuestion(route, value, viaSystemBack = false)
            backWithoutQuestion(route, value, viaSystemBack = true)
        }

        // changed: back asks; 나가기 leaves the stored item as it was
        for ((route, value) in forms.filter { it.first !is Route.RecordForm }) {
            go(route)
            replaceIn(value, "$value 고침")
            back()
            waitFor(leaveDialog(LeaveTexts.DISCARD_TITLE), "question on ${route::class.simpleName}")
            assertTrue(has(hasText(LeaveTexts.DISCARD_BODY)))
            clickNode(dialogButton(LeaveTexts.LEAVE))
            waitGone(field("$value 고침"))
        }
        runBlocking {
            assertEquals("과테말라 안티구아 부르봉", koinGet<PantryRepository>().getById("p2")!!.name)
            assertEquals("오리가미 드리퍼 S", koinGet<MiscRepository>().getById("m1")!!.name)
            assertEquals("커피 리브레", koinGet<MiscRepository>().getById("m4")!!.name)
            assertEquals("에티오피아+콜롬비아", koinGet<BlendRepository>().getById("bl1")!!.name)
            assertEquals("커핑 바이블", koinGet<StudyRepository>().getBook("b1")!!.title)
            assertEquals("홈카페 원데이 클래스", koinGet<StudyRepository>().getClass("c1")!!.title)
            assertEquals("추출 변수와 맛의 관계", koinGet<StudyRepository>().getVideo("v1")!!.title)
        }
    }

    @Test
    fun pantryEditor_newBag_keepWriting_thenCancelAndLeave() {
        startNav(Route.Misc)
        go(Route.PantryEditor())
        val placeholder = "예: 에티오피아 벤사 내추럴"
        typeInto(placeholder, "보관함 새 원두")
        systemBack()
        waitFor(leaveDialog(LeaveTexts.DISCARD_TITLE))
        clickNode(dialogButton(LeaveTexts.STAY))
        waitGone(isDialog())
        assertTrue(has(field("보관함 새 원두")))

        clickText("취소")
        waitFor(leaveDialog(LeaveTexts.DISCARD_TITLE))
        clickNode(dialogButton(LeaveTexts.LEAVE))
        waitGone(field("보관함 새 원두"))
        assertTrue(runBlocking { koinGet<PantryRepository>().getAll() }.isEmpty())

        // a saved bag closes the form without a question
        go(Route.PantryEditor())
        typeInto(placeholder, "저장할 원두")
        clickText("저장")
        waitGone(field("저장할 원두"))
        assertFalse(has(isDialog()))
        assertEquals(listOf("저장할 원두"), runBlocking { koinGet<PantryRepository>().getAll() }.map { it.name })
    }

    @Test
    fun cafeAdd_leavesUntouched_asksOnceANameIsTyped() {
        startNav(Route.Misc)
        go(Route.CafeAdd)
        val placeholder = "예: OO카페 (서울 성수동)"
        // nothing typed: back leaves at once
        systemBack()
        waitGone(hasText(placeholder))
        assertFalse(has(isDialog()))

        go(Route.CafeAdd)
        typeInto(placeholder, "가 볼 카페")
        systemBack()
        waitFor(leaveDialog(LeaveTexts.DISCARD_TITLE))
        clickNode(dialogButton(LeaveTexts.STAY))
        waitGone(isDialog())
        assertTrue(has(field("가 볼 카페")))

        clickText("취소")
        waitFor(leaveDialog(LeaveTexts.DISCARD_TITLE))
        clickNode(dialogButton(LeaveTexts.LEAVE))
        waitGone(field("가 볼 카페"))
        assertTrue(runBlocking { koinGet<CafePlaceRepository>().getAll() }.isEmpty())
    }

    @Test
    fun myRecipes_leavingAndCancellingTheNewRecipeAsk() {
        startNav(Route.Misc)
        go(Route.MyRecipes())
        waitForText("내 레시피")
        // untouched: back leaves at once
        back()
        waitGone(hasText("내 레시피"))
        assertFalse(has(isDialog()))

        go(Route.MyRecipes())
        typeInto("예: 밝은 산미용 3단 푸어", "아침 레시피")
        back()
        waitFor(leaveDialog(LeaveTexts.DISCARD_TITLE))
        clickNode(dialogButton(LeaveTexts.STAY))
        waitGone(isDialog())
        // the form's own 취소 asks about closing it
        clickText("취소")
        waitForText("지금 닫으면 입력한 내용은 사라져요.")
        clickNode(dialogButton("닫기"))
        waitGone(field("아침 레시피"))
        assertTrue("the screen stays", has(hasText("내 레시피")))
        back()
        waitGone(hasText("내 레시피"))
        assertFalse(has(isDialog()))
    }

    // ───────────────────────── dirty rules behind every form ─────────────────────────

    @Test
    fun viewModels_measureAgainstTheLoadedItem_alsoAfterProcessDeath() {
        SampleData.seed()
        val pantryHandle = SavedStateHandle()
        val pantry = vm("pantry") { PantryEditorViewModel("p2", koinGet(), pantryHandle) }
        pump("pantry loaded") { pantry.form.value.loaded && pantry.form.value.name.isNotBlank() }
        pantry.formatPrice()
        assertFalse("the loaded bag, price blur included", pantry.hasChanges())
        pantry.update { copy(notes = "냉동") }
        assertTrue(pantry.hasChanges())
        pantry.update { copy(notes = "") }
        assertFalse("typed back to the stored value", pantry.hasChanges())
        pantry.update { copy(notes = "냉동") }
        pump("kept") { pantryHandle.get<String>("pantryForm")?.contains("냉동") == true }
        val pantryAfter = vm("pantryAfter") { PantryEditorViewModel("p2", koinGet(), pantryHandle.copied()) }
        assertTrue("after process death the stored bag is still the measure", pantryAfter.hasChanges())
        pantryAfter.update { copy(notes = "") }
        assertFalse(pantryAfter.hasChanges())

        val misc = vm("misc") { MiscFormViewModel(MiscType.DRIPPER, "m1", koinGet(), koinGet(), SavedStateHandle()) }
        pump("misc loaded") { !misc.state.value.loading && misc.state.value.name.isNotBlank() }
        assertFalse(misc.hasChanges())
        misc.setPhoto(1, byteArrayOf(4, 5))
        assertTrue("a picked photo counts", misc.hasChanges())

        val flat = vm("flat") { FlatItemFormViewModel(MiscType.SOURCE, "m4", koinGet()) }
        pump("flat loaded") { flat.state.value.loaded }
        assertFalse(flat.hasChanges())
        flat.setLocation("대한민국 부산광역시")
        assertTrue(flat.hasChanges())

        val blend = vm("blend") { BlendFormViewModel("bl1", koinGet(), koinGet()) }
        pump("blend loaded") { blend.state.value.loaded }
        assertFalse(blend.hasChanges())
        blend.addRow()
        assertTrue(blend.hasChanges())

        val bookHandle = SavedStateHandle()
        val book = vm("book") { BookFormViewModel("b1", koinGet(), bookHandle) }
        pump("book loaded") { book.state.value.title.isNotBlank() }
        assertFalse(book.hasChanges())
        book.update { copy(titleError = true) }
        assertFalse("a shown error is not input", book.hasChanges())
        book.update { copy(rating = 5) }
        assertTrue(book.hasChanges())
        pump("kept") { bookHandle.get<String>("bookForm")?.contains("\"rating\":5") == true }
        val bookAfter = vm("bookAfter") { BookFormViewModel("b1", koinGet(), bookHandle.copied()) }
        assertTrue(bookAfter.hasChanges())
        bookAfter.update { copy(rating = 4) }
        assertFalse(bookAfter.hasChanges())

        val newClass = vm("class") { ClassFormViewModel(null, koinGet(), SavedStateHandle()) }
        assertFalse(newClass.hasChanges())
        newClass.update { copy(title = "라떼 아트") }
        assertTrue(newClass.hasChanges())
    }

    // ───────────────────────── pictures ─────────────────────────

    /** The record form's leave question and, on the next visit, the restored draft's banner. */
    private fun leaveQuestionAndBanner(question: String, banner: String) {
        SampleData.seed()
        launchApp()
        openNewRecord()
        waitForText("새 기록")
        typeInto(namePlaceholder, "케냐 니에리 기통가 AA")
        back()
        waitFor(leaveDialog(RecordDraftTexts.LEAVE_TITLE))
        captureScreenRoboImage(question)
        clickNode(dialogButton(RecordDraftTexts.LEAVE))
        waitForText("+ 새 기록 추가")
        openNewRecord()
        waitForText("✓ " + RecordDraftTexts.RESTORED)
        settle()
        captureScreenRoboImage(banner)
    }

    /** Kept pictures (recordRoborazziDebug). */
    @Test
    fun screenshots_leaveQuestion_andDraftBanner() =
        leaveQuestionAndBanner("screenshots/84-form-leave-question.png", "screenshots/85-form-draft-banner.png")

    /** The same at 320 dp with text at 1.3× (render matrix, not committed): the three buttons wrap, nothing is cut. */
    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun matrix_narrowLargerText() {
        RuntimeEnvironment.setFontScale(1.3f)
        leaveQuestionAndBanner("screenshots/matrix/76-w320-fs13-leave-question.png", "screenshots/matrix/77-w320-fs13-draft-banner.png")
    }
}
