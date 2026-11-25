package it.ponte

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Ricerca di alimenti e valori nutrizionali su Open Food Facts (per nome o per codice a barre). */
object OpenFood {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

    private fun parse(p: JSONObject): Food? {
        val n = p.optJSONObject("nutriments") ?: return null
        val name = p.optString("product_name").trim()
        if (name.isEmpty()) return null
        fun v(k: String) = n.optDouble(k, 0.0).toFloat()
        val kcal = if (n.has("energy-kcal_100g")) v("energy-kcal_100g") else v("energy_100g") / 4.184f
        if (kcal <= 0f && v("proteins_100g") + v("carbohydrates_100g") + v("fat_100g") <= 0f) return null
        val serving = p.optString("serving_quantity").toFloatOrNull()?.toInt()?.takeIf { it in 5..1000 } ?: 100
        return Food(
            "", name, p.optString("brands").split(",").firstOrNull()?.trim().orEmpty(), serving,
            Nut(kcal, v("proteins_100g"), v("carbohydrates_100g"), v("sugars_100g"), v("fat_100g"), v("fiber_100g"), v("salt_100g")),
        )
    }

    private const val FIELDS = "product_name,brands,nutriments,serving_quantity"

    private fun norm(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    private fun fetch(url: String): List<Food> = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Relay/1.0 (app personale)").build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList<Food>()
            val o = JSONObject(resp.body!!.string())
            val one = o.optJSONObject("product")
            if (one != null) return@use listOfNotNull(parse(one))
            val a = o.optJSONArray("products") ?: return@use emptyList<Food>()
            (0 until a.length()).mapNotNull { parse(a.getJSONObject(it)) }
        }
    } catch (_: Exception) { emptyList() }

    private fun list(term: String, filter: String): List<Food> =
        fetch("https://it.openfoodfacts.org/cgi/search.pl?search_terms=${URLEncoder.encode(term, "UTF-8")}&search_simple=1&action=process&json=1&page_size=24&sort_by=unique_scans_n&fields=$FIELDS$filter")

    /** Tabelle di composizione degli alimenti CREA (900 alimenti italiani, valori per 100 g): nome|kcal|prot|carb|zuccheri|grassi|fibre|sale|porzione. */
    var assets: android.content.res.AssetManager? = null
    private val GENERIC: List<Food> by lazy {
        try {
            assets!!.open("crea.txt").bufferedReader().useLines { ls ->
                ls.mapNotNull { l ->
                    val x = l.split("|"); if (x.size < 9) return@mapNotNull null
                    fun f(i: Int) = x[i].toFloat()
                    Food("", x[0], "CREA", x[8].toInt(), Nut(f(1), f(2), f(3), f(4), f(5), f(6), f(7)))
                }.toList()
            }
        } catch (_: Exception) { emptyList() }
    }

    fun crea(name: String): Food? = GENERIC.firstOrNull { it.name == name }

    suspend fun search(query: String): List<Food> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        if (q.all { it.isDigit() } && q.length in 8..14) return@withContext fetch("https://world.openfoodfacts.org/api/v2/product/$q.json?fields=$FIELDS")
        val esselunga = norm(q).contains("esselunga")
        val term = q.replace(Regex("(?i)esselunga"), " ").replace(Regex("\\s+"), " ").trim()
        val words = norm(term).split(" ").filter { it.isNotBlank() }
        val local = if (esselunga) emptyList() else GENERIC.filter { f -> val n = norm(f.name); words.isNotEmpty() && words.all { n.contains(it) } }.sortedBy { f -> val n = norm(f.name); (if (n.startsWith(words[0])) 0 else 1) * 1000 + n.length }.take(12)
        val online = if (esselunga) list(term, "&tagtype_0=brands&tag_contains_0=contains&tag_0=esselunga")
        else list(term, "&tagtype_0=countries&tag_contains_0=contains&tag_0=italy").let { if (it.size >= 6) it else it + list(term, "") }
        (local + online).distinctBy { norm(it.name) + "|" + norm(it.brand) }.take(30)
    }
}
