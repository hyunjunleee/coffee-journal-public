package com.coffeejournal.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coffeejournal.data.repo.CafePlaceRepository
import com.coffeejournal.data.repo.EntryRepository
import com.coffeejournal.data.repo.MiscRepository
import com.coffeejournal.data.repo.MyRecipeRepository
import com.coffeejournal.domain.model.Category
import com.coffeejournal.domain.model.Entry
import com.coffeejournal.domain.model.GeoPoint
import com.coffeejournal.domain.model.MiscItem
import com.coffeejournal.domain.model.MiscType
import com.coffeejournal.domain.model.MyRecipe
import com.coffeejournal.domain.model.RecipeRef
import com.coffeejournal.domain.model.RecipeStep
import com.coffeejournal.domain.model.Scope
import com.coffeejournal.domain.reference.CafeRecipes
import com.coffeejournal.domain.rules.Dates
import com.coffeejournal.domain.rules.MapLinks
import com.coffeejournal.domain.rules.ReminderKind
import com.coffeejournal.ui.ai.AiErrors
import com.coffeejournal.ui.ai.AiKeySlot
import com.coffeejournal.ui.ai.AiProvider
import com.coffeejournal.ui.ai.AiTexts
import com.coffeejournal.ui.bean.b.WorldMapCanvas
import com.coffeejournal.ui.bean.b.WorldMapGeometry
import com.coffeejournal.ui.bean.b.WorldMapState
import com.coffeejournal.ui.form.DefaultRecipeStore
import com.coffeejournal.ui.form.FormFold
import com.coffeejournal.ui.form.timer.BrewClock
import com.coffeejournal.ui.form.timer.BrewTimerResult
import com.coffeejournal.ui.guide.GuideTexts
import com.coffeejournal.ui.guide.KeyHowTos
import com.coffeejournal.ui.map.MapPickTarget
import com.coffeejournal.ui.map.WorldMapInsets
import com.coffeejournal.ui.map.WorldProjection
import com.coffeejournal.ui.map.detail.DetailMapCamera
import com.coffeejournal.ui.map.search.LocateResult
import com.coffeejournal.ui.map.search.LocateTexts
import com.coffeejournal.ui.map.search.PlaceSearchService
import com.coffeejournal.ui.map.search.PlaceSearchTexts
import com.coffeejournal.ui.nav.FormMode
import com.coffeejournal.ui.nav.Route
import com.coffeejournal.ui.nav.appGraph
import com.coffeejournal.ui.notify.ReminderPrefs
import com.coffeejournal.ui.notify.ReminderTexts
import com.coffeejournal.ui.notify.ReminderTime
import com.coffeejournal.ui.settings.SettingsTexts
import com.coffeejournal.ui.theme.BodyFont
import com.coffeejournal.ui.theme.CoffeeJournalTheme
import com.coffeejournal.ui.theme.Display
import com.coffeejournal.ui.theme.DisplaySettings
import com.coffeejournal.ui.theme.Ink
import com.coffeejournal.ui.theme.NumberFont
import com.coffeejournal.ui.theme.TextSize
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders individual routes on the JVM against seeded data; doubles as a crash check for every screen. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class RouteScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun setUp() {
        startTestKoin(ApplicationProvider.getApplicationContext())
        SampleData.seed()
    }

    private fun settle() { repeat(6) { Thread.sleep(250); compose.waitForIdle() } }

    private fun show(route: Route, file: String, after: (() -> Unit)? = null) {
        compose.setContent {
            CoffeeJournalTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = route) { appGraph(nav) }
            }
        }
        settle()
        after?.invoke()
        compose.onRoot().captureRoboImage("screenshots/$file")
    }

    @Test fun recordForm_brew() = show(Route.RecordForm(mode = FormMode.EXTRACT), "10-form-brew.png")
    @Test fun recordForm_cupping() = show(Route.RecordForm(mode = FormMode.CUPPING), "11-form-cupping.png")
    @Test fun recordForm_edit() = show(Route.RecordForm(mode = FormMode.EXTRACT, entryId = "e1"), "12-form-edit.png")
    @Test fun entryDetail_brew() = show(Route.EntryDetail("e1"), "13-detail-brew.png")
    @Test fun entryDetail_cupping() = show(Route.EntryDetail("e5"), "14-detail-cupping.png")
    @Test fun pantry() = show(Route.Pantry, "15-pantry.png")
    @Test fun pantryEditor() = show(Route.PantryEditor("p2"), "16-pantry-editor.png")
    @Test fun myRecipes() = show(Route.MyRecipes(), "17-my-recipes.png")
    @Test fun backup() = show(Route.Backup, "18-backup.png")
    @Test fun miscForm() = show(Route.MiscForm(type = "dripper", itemId = "m1"), "19-misc-form.png")
    @Test fun bookForm() = show(Route.BookForm("b1"), "20-book-form.png")
    @Test fun classForm() = show(Route.ClassForm(), "21-class-form.png")
    @Test fun calendar_study() = show(Route.Calendar, "22-calendar-study.png") { compose.onNodeWithText("스터디").performClick(); settle() }
    @Test fun calendar_cupping() = show(Route.Calendar, "23-calendar-cupping.png") { compose.onNodeWithText("커핑").performClick(); settle() }
    /**
     * Picks a 원두 sub tab and checks the view really switched before the capture (design-8: a click on a chip
     * scrolled off the row used to miss, and three PNGs showed the map). The chip is scrolled into the row first.
     */
    private fun beanView(chip: String, shows: String) {
        compose.onNode(hasText(chip) and hasClickAction()).performScrollTo().performClick()
        settle()
        compose.onNode(hasText(chip) and hasClickAction()).assertIsSelected()
        compose.onAllNodes(hasText(shows, substring = true)).onFirst().assertIsDisplayed()
    }

    @Test fun bean_notes() = show(Route.Bean, "30-bean-notes.png") { beanView("커피 노트", "플레이버 휠") }
    @Test fun bean_process() = show(Route.Bean, "31-bean-process.png") { beanView("가공 방식", "가공 방식 종류") }
    @Test fun bean_roast() = show(Route.Bean, "32-bean-roast.png") { beanView("배전도", "라이트계·중간·다크계") }
    @Test fun bean_variety() = show(Route.Bean, "33-bean-variety.png") { beanView("품종", "품종별로 보기") }
    @Test fun bean_specialty() {
        // one lot with a cup score, so the view shows a card and not only its empty note
        runBlocking {
            GlobalContext.get().get<EntryRepository>().upsert(
                Entry(
                    id = "e6", createdAt = Dates.toMillis(LocalDate(2026, 8, 30), 10, 0), category = Category.BEAN,
                    name = "파나마 에스메랄다 게이샤 (Best of Panama)", country = "파나마", region = "Boquete", score = "92.5",
                    expectedNotes = "자스민, 베르가못, 복숭아", roasterDesc = "Best of Panama 2026 워시드 랏.",
                )
            )
        }
        show(Route.Bean, "34-bean-specialty.png") { beanView("✦ Competition Lots", "파나마 에스메랄다 게이샤") }
    }
    @Test fun bean_map() = show(Route.Bean, "37-bean-map.png") {
        compose.onNode(hasText("커피 지도 + 농장(생산자)") and hasClickAction()).assertIsSelected().assertIsDisplayed()
    }
    /** Pinched in about 10× on Central America: the regions of the bean form's lists appear as dots, none on another. */
    @Test fun bean_mapZoomed() = show(Route.Bean, "89-bean-map-zoomed.png") {
        val map = compose.onNode(hasContentDescription("커피 지도.", substring = true))
        val n = map.fetchSemanticsNode()
        val size = Size(n.size.width.toFloat(), n.size.height.toFloat())
        // Antigua, Guatemala on the fitted map, in the map node's px
        val m = WorldMapGeometry.fitScale(size.width, size.height)
        val c = WorldMapGeometry.toCanvas(WorldProjection.toView(14.56, -90.73).let { Offset(it.x.toFloat(), it.y.toFloat()) }, m, Offset(size.width / 2f, size.height / 2f))
        repeat(3) {
            map.performTouchInput { pinch(c - Offset(100f, 0f), c - Offset(250f, 0f), c + Offset(100f, 0f), c + Offset(250f, 0f), durationMillis = 500) }
            settle()
        }
        compose.onNode(hasText("전체 보기") and hasClickAction()).assertIsDisplayed()
    }
    /** Pinched in on the Hawaii inset (the web's map ends at 128°W) and panned to it: Kona and the other islands' regions. */
    @Test fun bean_mapHawaii() = show(Route.Bean, "90-bean-map-hawaii.png") {
        val map = compose.onNode(hasContentDescription("커피 지도.", substring = true))
        val n = map.fetchSemanticsNode()
        val size = Size(n.size.width.toFloat(), n.size.height.toFloat())
        val m = WorldMapGeometry.fitScale(size.width, size.height)
        val c = WorldMapGeometry.toCanvas(WorldMapInsets.frame.center, m, Offset(size.width / 2f, size.height / 2f))
        // the inset is at the map's left edge: pinch with the fingers above and below it
        repeat(3) {
            map.performTouchInput { pinch(c - Offset(0f, 50f), c - Offset(0f, 120f), c + Offset(0f, 50f), c + Offset(0f, 120f), durationMillis = 500) }
            settle()
        }
        map.performTouchInput { swipe(Offset(size.width * 0.3f, size.height * 0.5f), Offset(size.width * 0.75f, size.height * 0.5f), durationMillis = 600) }
        settle()
        compose.onNode(hasText("전체 보기") and hasClickAction()).assertIsDisplayed()
    }
    /**
     * The coffee map at its deepest zoom over Rwanda and Burundi (set directly: pinches drift): every region has its
     * dot, and one too close to another sits beside its place with a line to it.
     */
    @Test fun bean_mapDeepest() {
        val density = 2.625f // 420dpi
        val w = 380f * density
        val m = WorldMapGeometry.fitScale(w, w / 1.6f) * WorldMapGeometry.MAX_SCALE
        val at = WorldProjection.toView(-2.4, 29.7).let { Offset(it.x.toFloat(), it.y.toFloat()) }
        val state = WorldMapState(scale = WorldMapGeometry.MAX_SCALE, pan = (WorldMapGeometry.viewCenter - at) * m)
        compose.setContent {
            CoffeeJournalTheme {
                Box(Modifier.background(Ink.bg).padding(16.dp)) {
                    WorldMapCanvas(state = state, visited = setOf("Rwanda"), triedRegions = setOf("Rwanda|huye"), onCountryTap = {}, onRegionTap = {}, modifier = Modifier.width(380.dp))
                }
            }
        }
        settle()
        compose.onNode(hasContentDescription("커피 지도.", substring = true)).captureRoboImage("screenshots/91-bean-map-deepest.png")
    }
    @Test fun bean_roastery() = show(Route.Bean, "38-bean-roastery.png") { beanView("로스터리", "한국 로스터리 지도") }
    /** Roasteries and a café with positions (and one found by its 지역 text), for the map screenshots. */
    private fun seedMapPlaces() = runBlocking {
        val koin = GlobalContext.get()
        koin.get<MiscRepository>().upsertAll(listOf(
            MiscItem(id = "mp1", type = MiscType.SOURCE, name = "프릳츠 도화", scope = Scope.DOMESTIC, location = "서울 마포구", createdAt = 11, lat = 37.5410, lng = 126.9510),
            MiscItem(id = "mp2", type = MiscType.SOURCE, name = "펠트 청계천", scope = Scope.DOMESTIC, location = "서울 중구", createdAt = 12, lat = 37.5680, lng = 126.9900),
            MiscItem(id = "mp3", type = MiscType.SOURCE, name = "모모스", scope = Scope.DOMESTIC, location = "부산 금정구", createdAt = 13, lat = 35.2270, lng = 129.0880),
            MiscItem(id = "mp4", type = MiscType.SOURCE, name = "테라로사", scope = Scope.DOMESTIC, location = "강원 강릉시", createdAt = 14),
            MiscItem(id = "mp5", type = MiscType.SOURCE, name = "Coava", scope = Scope.OVERSEAS, location = "Portland, USA", createdAt = 15),
        ))
        koin.get<CafePlaceRepository>().set("FELT 청계천", GeoPoint(37.5663, 126.9910))
    }

    @Test fun roastery_koreaMap() {
        seedMapPlaces()
        show(Route.Bean, "46-roastery-korea.png") { beanView("로스터리", "한국 로스터리 지도") }
    }
    @Test fun roastery_provinceMap() {
        seedMapPlaces()
        show(Route.Bean, "47-roastery-province.png") {
            beanView("로스터리", "한국 로스터리 지도")
            // the three 서울 roasteries share one chip nationally; it opens 서울
            compose.onNode(hasContentDescription("서울특별시에 3곳", substring = true)).performClick()
            settle()
            compose.onNode(hasText("← 전국 · 서울특별시") and hasClickAction()).assertExists()
            compose.onNode(hasContentDescription("펠트 청계천, ", substring = true)).performClick()
            settle()
        }
    }
    @Test fun roastery_world() {
        seedMapPlaces()
        show(Route.Bean, "49-roastery-world.png") {
            beanView("로스터리", "한국 로스터리 지도")
            compose.onNode(hasText("해외") and hasClickAction()).performClick()
            settle()
            compose.onNode(hasContentDescription("Coava, ", substring = true)).performClick()
            settle()
        }
    }
    @Test fun cafeMap() {
        seedMapPlaces()
        // a café added by hand, without a position yet: listed under the map with its picker and 삭제
        runBlocking { GlobalContext.get().get<CafePlaceRepository>().add("가 볼 카페 연남") }
        show(Route.Bean, "50-cafe-map.png") {
            beanView("로스터리", "한국 로스터리 지도")
            compose.onNode(hasText("카페 지도") and hasClickAction()).performClick()
            settle()
            compose.onNode(hasContentDescription("FELT 청계천, 방문 1회")).performClick()
            settle()
        }
    }
    /** "+ 카페 추가": a name that already is a café says which one, instead of making a second. */
    @Test fun cafeAdd() {
        seedMapPlaces()
        show(Route.CafeAdd, "78-cafe-add.png") {
            compose.onNode(hasSetTextAction() and hasText("예: OO카페 (서울 성수동)")).performTextInput("felt 청계천")
            settle()
            compose.onNode(hasText("이미 있는 카페예요", substring = true)).assertExists()
        }
    }
    /** Calendar › 카페: the cafés of the list and one added by hand, each with its position, and "+ 카페 추가". */
    @Test fun calendar_cafes() {
        seedMapPlaces()
        runBlocking { GlobalContext.get().get<CafePlaceRepository>().add("가 볼 카페 연남") }
        show(Route.Calendar, "79-calendar-cafes.png") {
            click("카페")
            click("전체 보기")
            compose.onNode(hasText("+ 카페 추가") and hasClickAction()).performScrollTo()
            settle()
        }
    }
    @Test fun mapPicker() = show(Route.MapPicker(target = "roastery", name = "커피 리브레", scope = Scope.DOMESTIC, point = "37.5446,127.0557"), "48-map-picker.png") {
        compose.onNode(hasText("← 전국 · 서울특별시") and hasClickAction()).assertExists()
    }
    /**
     * The detail map (feature-plan-v2 §1.7) without network: the notice and the way back to the SGIS map. MapLibre
     * does not render on the JVM, so the map states below use the tests' stand-in renderer.
     */
    @Test fun detailMap_offline() {
        seedMapPlaces()
        installFakeDetailMap(FakeDetailMapRenderer(online = false))
        show(Route.DetailMap(bounds = DetailMapCamera.provinceBounds("11")!!.encode()), "65-detail-map-offline.png") {
            compose.onNode(hasText("← 한국 지도로 돌아가기") and hasClickAction()).assertExists()
        }
    }
    /** The detail map's layout around the (stand-in, grey) map: attribution, a selected roastery's panel. */
    @Test fun detailMap_placeholder() {
        seedMapPlaces()
        installFakeDetailMap()
        show(Route.DetailMap(camera = "37.5680,126.9900,16.0", focus = "펠트 청계천"), "66-detail-map-placeholder.png") {
            compose.onNode(hasText("📍 서울특별시 중구")).assertExists()
        }
    }
    /** "상세 지도에서 정확히": the crosshair over the (stand-in) map and the point it sets. */
    @Test fun detailMap_pick() {
        installFakeDetailMap()
        show(Route.DetailMap(mode = "pick", name = "커피 리브레", camera = "37.5446,127.0557,16.0"), "67-detail-map-pick.png") {
            compose.onNode(hasText("📍 서울특별시 성동구")).assertExists()
        }
    }
    @Test fun bean_selection() = show(Route.Bean, "39-bean-selection.png") { beanView("생두 수입사", "Nordic Approach") }
    @Test fun bean_blend() = show(Route.Bean, "40-bean-blend.png") { beanView("블렌드", "+ 블렌드 기록 추가") }
    @Test fun blendForm() = show(Route.BlendForm("bl1"), "41-blend-form.png")
    @Test fun flatItemForm() = show(Route.FlatItemForm(type = "source", itemId = "m4"), "42-flat-item-form.png")
    @Test fun countryDetail() = show(Route.CountryDetail("Ethiopia"), "43-country-detail.png")
    @Test fun about() = show(Route.About, "45-about.png")
    @Test fun variety_detail() = show(Route.VarietyDetail("gesha"), "35-variety-detail.png")

    // ───────── feature-plan-v2 §2: timer, comparison, calculator, CVA, statistics ─────────

    private fun click(text: String, index: Int = 0) {
        compose.onAllNodes(hasText(text) and hasClickAction())[index].performScrollTo().performClick()
        settle()
    }

    /** Scrolls the page so the first node matching [matcher] sits at the top of the screen. */
    private fun bringToTop(matcher: SemanticsMatcher) {
        compose.onAllNodes(matcher).onFirst().performScrollTo()
        settle()
        val node = compose.onAllNodes(matcher).onFirst().fetchSemanticsNode()
        val scroller = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNodes().maxBy { it.boundsInRoot.height }
        val dy = node.boundsInRoot.top - scroller.boundsInRoot.top - 12 * scroller.layoutInfo.density.density
        compose.runOnUiThread { scroller.config[SemanticsActions.ScrollBy].action?.invoke(0f, dy) }
        settle()
    }

    /**
     * The brew timer with the 유어홈 recipe, mid-way: the first pour just ended; its grams panel holds the recipe's 50 g
     * as the estimate, and the next pour can already start.
     */
    @Test fun brewTimer() {
        val clock = FakeBrewClock()
        loadKoinModules(module { single<BrewClock> { clock } })
        val recipe = CafeRecipes.all.first { it.id == "yourhome" }
        show(Route.BrewTimer(recipe = BrewTimerResult.encodeRecipe(RecipeRef(recipe.name, recipe.steps)), hasLog = true), "53-brew-timer.png") {
            click("💧 붓기 시작")
            clock.advance(9_000)
            settle()
            click("붓기 끝")
        }
    }

    @Test fun brewCompare() = show(Route.BrewCompare("에티오피아 예가체프 워카 첼베사"), "54-brew-compare.png")

    @Test fun calculator() = show(Route.RecordForm(mode = FormMode.EXTRACT, entryId = "e1"), "55-calculator.png") {
        click("🧮 비율 · 추출수율 계산기")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("calc-tds"))).performScrollTo().performTextInput("1.35")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("calc-beverage"))).performTextInput("200")
        bringToTop(hasText("🧮 비율 · 추출수율 계산기") and hasClickAction())
    }

    @Test fun recordForm_cva() = show(Route.RecordForm(mode = FormMode.EXTRACT, entryId = "e2"), "56-form-cva.png") {
        click("CVA")
        bringToTop(hasText("SCA 커핑 평가 (100점)"))
    }

    @Test fun entryDetail_cva() {
        runBlocking { GlobalContext.get().get<EntryRepository>().upsert(SampleCva.tasting) }
        show(Route.EntryDetail(SampleCva.tasting.id), "57-detail-cva.png") { bringToTop(hasText("SCA CVA · 묘사 + 정동 평가")) }
    }

    /** 통계 over the whole journal (the sample records are from September 2026). */
    @Test fun stats() = show(Route.Stats, "58-stats.png") { click("전체") }

    @Test fun stats_charts() {
        runBlocking { GlobalContext.get().get<EntryRepository>().upsert(SampleCva.tasting) }
        show(Route.Stats, "59-stats-charts.png") {
            click("전체")
            bringToTop(hasText("점수 추이"))
        }
    }
    @Test fun process_detail() = show(Route.ProcessDetail("워시드", "워시드"), "36-process-detail.png")

    // 설정: first visit (the display choices at their defaults), then its 알림 section on at 07:30 with the permission
    // taken away (feature plan v2 §3), then a serif, larger text setting on 설정 and on the home tab
    @Test fun settings() = show(Route.Settings, "60-settings.png") {
        compose.onNode(hasText(SettingsTexts.PREVIEW_TITLE)).assertIsDisplayed()
    }
    @Test fun notificationSettings_on() {
        runBlocking {
            val prefs = GlobalContext.get().get<ReminderPrefs>()
            prefs.setEnabled(true)
            prefs.setKind(ReminderKind.LOW_STOCK, false)
            prefs.setTime(ReminderTime(7, 30))
        }
        show(Route.Settings, "61-settings-reminders-on.png") {
            bringToTop(hasText("알림"))
            compose.onNode(hasText(ReminderTexts.BLOCKED)).assertIsDisplayed()
        }
    }
    @Test fun settings_serifLarger() {
        Display.current = DisplaySettings(bodyFont = BodyFont.SERIF, numberFont = NumberFont.BODY, textSize = TextSize.LARGER)
        show(Route.Settings, "68-settings-serif-larger.png")
    }
    @Test fun home_serifLarger() {
        Display.current = DisplaySettings(bodyFont = BodyFont.SERIF, textSize = TextSize.LARGER)
        show(Route.Extract, "69-home-serif-larger.png")
    }

    // ───────── AI 노트 도우미 (fake services, synthetic replies) ─────────

    /** The answer arrives after the settings and keys are read: wait for [text] before the capture. */
    private fun waitForText(text: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "no '$text' on screen" }
            Thread.sleep(100)
            compose.waitForIdle()
        }
        settle()
    }

    /** 설정's AI section from the option's description: the Gemini key saved, the model and the search settings (defaults). */
    @Test fun settings_ai() {
        AiSetup.key(AiKeySlot.GEMINI)
        show(Route.Settings, "70-settings-ai.png") {
            bringToTop(hasText(AiTexts.SECTION))
            waitForText("저장됨 …a1b2")
            bringToTop(hasText(AiProvider.GEMINI_TAVILY.summary))
            waitForText(AiTexts.credits(3))
        }
    }

    /** Mode A with Tavily + Gemini: marks, a grey sentence, ✓ / ✗ quote badges, the queries, sources with their kinds. */
    @Test fun noteHelper_note() {
        AiSetup.ready(AiProvider.GEMINI_TAVILY)
        AiSetup.tavilyAnswers()
        show(Route.NoteHelper(mode = "note", query = "자스민"), "71-note-helper-note.png") { waitForText(AiTexts.NOT_FOUND) }
    }

    /** Mode B from the record form: the candidate notes, one picked. */
    @Test fun noteHelper_describe() {
        AiSetup.ready(AiProvider.GEMINI_TAVILY)
        AiSetup.tavilyAnswers(AiReplies.DESCRIBE_ANSWER, AiReplies.DESCRIBE_QUERIES)
        show(Route.NoteHelper(mode = "describe", query = "잘 익은 자두 같고 끝이 쌉쌀해요", returnToForm = true), "72-note-helper-describe.png") {
            waitForText(AiTexts.CANDIDATES)
            click("자두")
            waitForText("${AiTexts.ADD_TO_NOTES} (1)")
        }
    }

    /** Gemini + Google 검색 on a free project: the billing message and the way to 설정. */
    @Test fun noteHelper_error() {
        AiSetup.ready(AiProvider.GEMINI_SEARCH)
        AiSetup.http.on("generativelanguage", status = 429) { AiReplies.GEMINI_429 }
        show(Route.NoteHelper(mode = "note", query = "자스민"), "73-note-helper-error.png") { waitForText(AiErrors.SEARCH_BILLING) }
    }

    /** Gemini 무료 + Tavily while Tavily searches: the query step done, the search under way with its dots, the answer to come. */
    @Test fun noteHelper_asking() {
        AiSetup.ready(AiProvider.GEMINI_TAVILY)
        val search = AiSetup.http.hold("api.tavily.com")
        AiSetup.tavilyAnswers()
        show(Route.NoteHelper(mode = "note", query = "자스민"), "75-note-helper-asking.png") { waitForText("✓ ${AiTexts.STAGE_QUERY}") }
        search.complete(Unit)
    }

    // ───────── lane J: the timer's ✕ for a mistaken pour, a café blend's bean blocks, the café record's folded recipe ─────────

    private fun type(placeholder: String, text: String, index: Int = 0) {
        compose.onAllNodes(hasSetTextAction() and hasText(placeholder))[index].performScrollTo().performTextInput(text)
        settle()
    }

    /** 붓기 시작·끝 tapped by mistake during the bloom, removed with its ✕: "2차 푸어를 지웠어요 · 되돌리기" where it was. */
    @Test fun brewTimer_removedPour() {
        val clock = FakeBrewClock()
        loadKoinModules(module { single<BrewClock> { clock } })
        show(Route.BrewTimer(recipe = null, hasLog = false), "80-brew-timer-remove.png") {
            click("💧 붓기 시작")
            clock.advance(10_000); settle()
            click("붓기 끝")
            click("확인")
            clock.advance(2_000); settle()
            click("뜸")
            clock.advance(8_000); settle()
            click("💧 붓기 시작")
            clock.advance(2_000); settle()
            click("붓기 끝")
            compose.onNode(hasContentDescription("2차 푸어 삭제") and hasClickAction()).performScrollTo().performClick()
            settle()
            bringToTop(hasText("붓기 시작·끝으로", substring = true))
        }
    }

    /**
     * A café blend: "+ 원두 추가 (블렌드)" gave bean 2 the same block, without example placeholders; its 로스터리 and
     * 로스팅 show bean 1's in grey, and the shares add up to 90% (the gentle hint).
     */
    @Test fun recordForm_cafeBlend() = show(Route.RecordForm(mode = FormMode.EXTRACT), "81-form-cafe-blend.png") {
        type("예: 콜롬비아 라 플라타 게이샤 워시드", "하우스 블렌드")
        type("예: 커피정경", "프릳츠")
        type("에티오피아", "콜롬비아")
        click("미디엄")
        click("+ 원두 추가 (블렌드)")
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("bean-block-0")))[0].performTextInput("60")
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("bean-block-1")))[0].performTextInput("30")
        // bean 2's 국가 (its block shows no example placeholders): 비율, 로스터리, 생두 수입사, 국가
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("bean-1")))[3].performScrollTo().performTextInput("에티오피아")
        settle()
        bringToTop(hasTestTag("bean-block-1"))
    }

    /** The detail of a café blend: each bean its own group with its share. */
    @Test fun entryDetail_cafeBlend() {
        runBlocking { GlobalContext.get().get<EntryRepository>().upsert(SampleBlend.house) }
        show(Route.EntryDetail(SampleBlend.house.id), "82-detail-cafe-blend.png")
    }

    /** A café record: the recipe is folded away behind one row until the café tells it. */
    @Test fun recordForm_cafeRecipeFolded() = show(Route.RecordForm(mode = FormMode.CAFE), "83-form-cafe-recipe.png") {
        bringToTop(hasText("가게에 적힌 원두 설명", substring = true))
    }

    /** Claude with web search: ✓ from the cited excerpt, the uncited search result listed after the cited one. */
    @Test fun noteHelper_claude() {
        AiSetup.ready(AiProvider.CLAUDE)
        AiSetup.http.on("api.anthropic.com") { AiReplies.CLAUDE }
        show(Route.NoteHelper(mode = "note", query = "자스민"), "74-note-helper-claude.png") { waitForText(AiTexts.FOUND) }
    }

    // ───────── 위치 지정: search and 현재 위치 (fake phone search and position) ─────────

    /** A café's picker after "현재 위치" and 검색: its name found near the phone, with the distances and the source. */
    @Test fun mapPicker_search() {
        PlaceSetup.grantLocation(ApplicationProvider.getApplicationContext())
        PlaceSetup.location.answer = LocateResult.Found(GeoPoint(37.5700, 126.9830), accuracyM = 25.0)
        PlaceSetup.search.hits = listOf(PlaceFixtures.FELT, PlaceFixtures.FELT_OTHER)
        show(Route.MapPicker(target = MapPickTarget.CAFE, name = "FELT 청계천"), "76-map-picker-search.png") {
            click(PlaceSearchTexts.HERE)
            waitForText(LocateTexts.found(25.0))
            click(PlaceSearchTexts.SEARCH)
            waitForText(PlaceFixtures.FELT.name)
        }
    }

    /**
     * A roastery's picker without a Kakao key: "테스트커피 장충" finds only the area on the phone, so the name is searched
     * again around 장충동: the cafés carrying it nearest first with their distance, the area after them, the sources with
     * OpenStreetMap's credit, and the tip that opens Kakao's how-to.
     */
    @Test fun mapPicker_nameAroundArea() {
        PlaceSetup.search.hits = listOf(PlaceFixtures.JANGCHUNG)
        AiSetup.http.on("q=${MapLinks.encode("테스트커피 장충")}&") { PhotonFixtures.EMPTY }
            .on("q=${MapLinks.encode("테스트커피")}&") { PhotonFixtures.CAFES }
        show(Route.MapPicker(target = MapPickTarget.ROASTERY, name = "테스트커피 장충"), "86-map-picker-area-search.png") {
            click(PlaceSearchTexts.SEARCH)
            waitForText("테스트커피 약수점")
        }
    }

    /** A key's how-to, full screen over 설정 (the Kakao one from 장소 검색, the Gemini one from AI 노트 도우미). */
    @Test fun keyGuide_kakao() = keyGuide(PlaceSearchTexts.KAKAO_GUIDE_OPEN, "87-key-guide-kakao.png")

    @Test fun keyGuide_gemini() = keyGuide(GuideTexts.open(AiKeySlot.GEMINI.label), "88-key-guide-gemini.png")

    // v1.5.0: "+ 새 기록 추가" chooses among every kind of record; a café record can be recorded again
    @Test fun newRecord() = show(Route.NewRecord, "92-new-record.png")
    @Test fun recordForm_cafeAgain() = show(Route.RecordForm(mode = FormMode.CAFE, againFrom = "e4"), "93-form-cafe-again.png")
    @Test fun entryDetail_cafe() = show(Route.EntryDetail("e4"), "94-detail-cafe.png")
    // most of the record form folds; folded, a part says what it holds
    @Test fun recordForm_folded() = show(Route.RecordForm(mode = FormMode.EXTRACT, entryId = "e1"), "95-form-folded.png") { click(FormFold.FOLD_ALL) }
    @Test fun recordForm_cuppingFolded() = show(Route.RecordForm(mode = FormMode.CUPPING, entryId = "e5"), "96-form-cupping-folded.png") {
        click(FormFold.FOLD_ALL)
    }
    // v1.6.0: 기본 레시피 — the new brew starts with it, and 내 레시피 and 설정 say which it is
    private fun withDefaultRecipe() = runBlocking {
        val now = Dates.nowMillis()
        GlobalContext.get().get<MyRecipeRepository>().upsertAll(
            listOf(
                MyRecipe(
                    id = "r1", name = "아침 3단", dose = "17", water = "272", temp = "93", dripper = "V60", filter = "하리오 01", grind = "코만단테 22클릭",
                    steps = listOf(RecipeStep("0:00", "40", "30", "뜸"), RecipeStep("0:30", "110", "20", "2차"), RecipeStep("1:10", "122", "", "3차")), createdAt = now,
                ),
                MyRecipe(id = "r2", name = "간단 1:16", dose = "16", water = "256", temp = "91", dripper = "칼리타 웨이브", rating = 4, createdAt = now - 1),
            ),
        )
        GlobalContext.get().get<DefaultRecipeStore>().set("r1")
    }
    @Test fun recordForm_defaultRecipe() { withDefaultRecipe(); show(Route.RecordForm(mode = FormMode.EXTRACT), "97-form-default-recipe.png") }
    @Test fun myRecipes_default() { withDefaultRecipe(); show(Route.MyRecipes(newRecipe = false), "98-my-recipes-default.png") }
    @Test fun newRecord_defaultRecipe() { withDefaultRecipe(); show(Route.NewRecord, "99-new-record-default-recipe.png") }

    private fun keyGuide(link: String, file: String) {
        compose.setContent {
            CoffeeJournalTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = Route.Settings) { appGraph(nav) }
            }
        }
        settle()
        click(link)
        waitForText(KeyHowTos.KAKAO.asOf)
        captureScreenRoboImage("screenshots/$file")
    }

    /** 설정's 장소 검색 with a Kakao key saved. */
    @Test fun settings_placeSearch() {
        AiSetup.secrets.values[PlaceSearchService.KAKAO_SECRET] = "kakao-rest-key-9f3a"
        show(Route.Settings, "77-settings-place-search.png") {
            bringToTop(hasText(PlaceSearchTexts.SECTION))
            waitForText("저장됨 …9f3a")
        }
    }
}
