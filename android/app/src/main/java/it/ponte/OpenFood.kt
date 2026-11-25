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

    /** Alimenti generici italiani (valori medi per 100 g): nome|kcal|prot|carb|zuccheri|grassi|fibre|sale|porzione. */
    private val GENERIC = """
Pasta di semola, cruda|353|13|72|2.7|1.5|3|0.01|80
Pasta di semola, cotta|158|5.8|31|0.6|0.9|1.8|0.01|200
Riso brillato, crudo|332|6.7|80|0.2|0.4|1|0.01|80
Riso, cotto|130|2.7|28|0.1|0.3|0.4|0.01|200
Pane comune|270|8.5|57|2.5|1.5|3.5|1.2|50
Pane integrale|224|7.5|45|2.5|1.3|6.5|1.2|50
Fette biscottate|408|11|73|9|8|4|0.9|10
Fiocchi d'avena|372|13.5|60|1|7|10|0|40
Biscotti secchi|430|7|76|22|11|2|0.8|30
Pizza margherita|271|11|33|3|10|2|1.5|300
Petto di pollo, crudo|100|22|0|0|1|0|0.15|150
Petto di tacchino, crudo|107|24|0|0|1|0|0.15|150
Manzo magro, crudo|120|21|0|0|4|0|0.15|150
Bresaola|151|32|0|0|2.6|0|4.5|50
Prosciutto crudo|268|27|0|0|18|0|5.5|50
Prosciutto cotto|215|19|0.5|0.5|15|0|2.5|50
Tonno al naturale, sgocciolato|103|25|0|0|1|0|1|80
Salmone, crudo|185|20|0|0|12|0|0.1|150
Merluzzo, crudo|71|17|0|0|0.3|0|0.2|150
Uovo di gallina, intero|128|12.4|0.7|0.7|8.7|0|0.35|60
Albume d'uovo|44|10|0.7|0.7|0|0|0.4|100
Latte parzialmente scremato|46|3.3|4.9|4.9|1.5|0|0.1|200
Latte intero|64|3.3|4.9|4.9|3.6|0|0.1|200
Yogurt bianco intero|66|3.8|4.3|4.3|3.9|0|0.1|125
Yogurt greco magro|53|10|4|4|0.2|0|0.1|150
Parmigiano Reggiano|392|33|0|0|28|0|1.6|30
Grana Padano|384|33|0|0|28|0|1.6|30
Mozzarella di vacca|253|18|0.7|0.7|20|0|0.6|125
Ricotta di vacca|146|8.8|3.5|3.5|10.9|0|0.2|100
Burro|758|0.8|1.1|1.1|83|0|0.05|10
Olio extravergine d'oliva|884|0|0|0|100|0|0|10
Mela|45|0.2|11|11|0|2|0|150
Banana|65|1.2|15|15|0.3|1.9|0|120
Arancia|34|0.7|7.8|7.8|0.2|1.6|0|150
Pomodori da insalata|19|1.2|2.8|2.8|0.2|1.1|0|150
Zucchine|11|1.3|1.4|1.4|0.1|1.1|0|200
Spinaci|31|3.4|2.9|0.4|0.7|2|0.2|150
Patate|85|2.1|18|1.1|0|1.6|0|200
Mandorle|603|22|7|4|55|12|0|30
Noci|689|15|5|3|68|6|0|30
""".trim().lines().mapNotNull { l ->
        val x = l.split("|"); if (x.size < 9) return@mapNotNull null
        fun f(i: Int) = x[i].toFloat()
        Food("", x[0], "Valori medi", x[8].toFloat().toInt(), Nut(f(1), f(2), f(3), f(4), f(5), f(6), f(7)))
    }

    suspend fun search(query: String): List<Food> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        if (q.all { it.isDigit() } && q.length in 8..14) return@withContext fetch("https://world.openfoodfacts.org/api/v2/product/$q.json?fields=$FIELDS")
        val esselunga = norm(q).contains("esselunga")
        val term = q.replace(Regex("(?i)esselunga"), " ").replace(Regex("\\s+"), " ").trim()
        val words = norm(term).split(" ").filter { it.isNotBlank() }
        val local = if (esselunga) emptyList() else GENERIC.filter { f -> val n = norm(f.name); words.isNotEmpty() && words.all { n.contains(it) } }
        val online = if (esselunga) list(term, "&tagtype_0=brands&tag_contains_0=contains&tag_0=esselunga")
        else list(term, "&tagtype_0=countries&tag_contains_0=contains&tag_0=italy").let { if (it.size >= 6) it else it + list(term, "") }
        (local + online).distinctBy { norm(it.name) + "|" + norm(it.brand) }.take(30)
    }
}
