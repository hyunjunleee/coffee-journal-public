package com.coffeejournal.ui.map.search

import com.coffeejournal.domain.model.GeoPoint
import com.coffeejournal.domain.rules.MapLinks
import com.coffeejournal.ui.ai.AiConnectionException
import com.coffeejournal.ui.ai.AiHttp
import com.coffeejournal.ui.ai.AiHttpRequest
import com.coffeejournal.ui.ai.AiJson
import com.coffeejournal.ui.ai.arr
import com.coffeejournal.ui.ai.dbl
import com.coffeejournal.ui.ai.get
import com.coffeejournal.ui.ai.str

/**
 * OpenStreetMap's places through Photon (photon.komoot.io, komoot's public geocoder, no key; fair use, "extensive usage
 * will be throttled"): the keyless search beside the phone's own. It knows shops by name (a café is `amenity=cafe`, a
 * bean shop `shop=coffee`) where Android's geocoder knows mostly addresses, but only the places OpenStreetMap's mappers
 * added, so a Kakao key still finds far more Korean cafés. The data is © OpenStreetMap contributors (ODbL), credited
 * under the results. Only the typed words go out, with a position when the search starts from one.
 */
internal object OsmPhoton {
    const val URL = "https://photon.komoot.io/api/"
    const val LIMIT = 15

    /** South Korea with its islands (minLon, minLat, maxLon, maxLat), as the 국내 map shows it. */
    const val KOREA_BOX = "124.5,33.0,131.9,38.7"

    /** Around a position given: about 25 km each way, so a name search near an area stays in that area. */
    private const val NEAR_DEG = 0.25

    /** Names the app on each request, as public OpenStreetMap services ask. */
    val HEADERS = mapOf("User-Agent" to "CoffeeJournal (+https://github.com/hyunjunleee/coffee-journal-public)")

    /**
     * [query] kept to Korea when [domestic] (to the box around [near] when given), anywhere else otherwise (the results
     * in Korea are dropped later), nearer [near] first.
     */
    fun request(query: String, domestic: Boolean, near: GeoPoint?, limit: Int = LIMIT): AiHttpRequest {
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lng}" } ?: ""
        val box = when {
            near != null && domestic -> "&bbox=${near.lng - NEAR_DEG},${near.lat - NEAR_DEG},${near.lng + NEAR_DEG},${near.lat + NEAR_DEG}"
            domestic -> "&bbox=$KOREA_BOX"
            else -> ""
        }
        return AiHttpRequest("$URL?q=${MapLinks.encode(query)}&limit=$limit$bias$box", HEADERS)
    }

    /** The places, or null when no reply came back or it was not one (a throttled or failed request). */
    suspend fun search(http: AiHttp, query: String, domestic: Boolean, near: GeoPoint?): List<PlaceHit>? {
        val response = try {
            http.send(request(query, domestic, near))
        } catch (e: AiConnectionException) {
            return null
        }
        return if (response.status == 200) parse(response.body) else null
    }

    /** OpenStreetMap's tag values a café picker meets, in the app's words. */
    private val CATEGORIES = mapOf(
        "cafe" to "카페", "coffee" to "커피 원두", "bakery" to "베이커리", "restaurant" to "음식점", "fast_food" to "음식점",
        "pastry" to "베이커리", "confectionery" to "디저트", "tea" to "차", "deli" to "식료품점", "supermarket" to "식료품점",
    )

    /** Photon's `type` of an area and of a road. */
    private val AREAS = setOf("country", "state", "county", "city", "district", "locality")

    /**
     * Null when [body] is not a Photon reply. A named place keeps its name; a nameless building is named after its
     * address. An area is marked "지역", a road "도로", a building "주소", a shop or café by its kind.
     */
    fun parse(body: String): List<PlaceHit>? {
        val root = AiJson.parseObject(body) ?: return null
        if (root["features"] == null) return null
        return root["features"].arr.mapNotNull { f ->
            val coords = f["geometry"]["coordinates"].arr
            if (coords.size < 2) return@mapNotNull null
            val point = GeoPoint.of(coords[1].dbl, coords[0].dbl) ?: return@mapNotNull null
            val p = f["properties"]
            val korea = p["countrycode"].str == "KR"
            val address = address(p["street"].str, p["housenumber"].str, p["district"].str, p["city"].str, p["state"].str, p["country"].str, korea)
            val type = p["type"].str
            val name = p["name"].str?.trim()?.takeIf { it.isNotEmpty() }
            val category = when {
                type in AREAS -> "지역"
                type == "street" -> "도로"
                else -> CATEGORIES[p["osm_value"].str] ?: if (name == null) "주소" else null
            }
            val shown = name ?: address.ifEmpty { return@mapNotNull null }
            PlaceHit(shown, if (type in AREAS && address.isEmpty()) shown else address, point, category)
        }
    }

    /**
     * An address the way the country writes it: in Korea "서울특별시 새창로2길 17 (도화동)" (Photon leaves out the 구),
     * elsewhere "17 Main St, Kyoto, Japan"-like from the parts it has, the country last.
     */
    fun address(street: String?, number: String?, district: String?, city: String?, state: String?, country: String?, korea: Boolean): String {
        fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }
        return if (korea) {
            val road = listOfNotNull(street.clean(), number.clean()).joinToString(" ")
            val head = listOfNotNull(state.clean(), city.clean()).distinct().joinToString(" ")
            when {
                road.isNotEmpty() -> listOf(head, road).filter { it.isNotEmpty() }.joinToString(" ") + (district.clean()?.let { " ($it)" } ?: "")
                else -> listOfNotNull(head.ifEmpty { null }, district.clean()).joinToString(" ")
            }
        } else {
            listOfNotNull(listOfNotNull(street.clean(), number.clean()).joinToString(" ").ifEmpty { null }, district.clean(), city.clean(), state.clean(), country.clean())
                .distinct().joinToString(", ")
        }
    }
}
