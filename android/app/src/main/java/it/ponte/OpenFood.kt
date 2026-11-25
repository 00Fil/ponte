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

    suspend fun search(query: String): List<Food> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val fields = "product_name,brands,nutriments,serving_quantity"
        val url = if (q.all { it.isDigit() } && q.length in 8..14) "https://world.openfoodfacts.org/api/v2/product/$q.json?fields=$fields"
        else "https://it.openfoodfacts.org/cgi/search.pl?search_terms=${URLEncoder.encode(q, "UTF-8")}&search_simple=1&action=process&json=1&page_size=15&fields=$fields"
        try {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Relay/1.0 (app personale)").build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<Food>()
                val o = JSONObject(resp.body!!.string())
                val one = o.optJSONObject("product")
                if (one != null) return@use listOfNotNull(parse(one))
                val a = o.optJSONArray("products") ?: return@use emptyList<Food>()
                (0 until a.length()).mapNotNull { parse(a.getJSONObject(it)) }
            }
        } catch (_: Exception) { emptyList() }
    }
}
