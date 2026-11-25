using System.Globalization;
using System.Text;
using System.Text.Json.Nodes;

namespace Ponte;

/// <summary>Ricerca di alimenti: tabelle CREA (900 alimenti italiani, offline) e Open Food Facts (per nome, marchio Esselunga o codice a barre).</summary>
public static class Foods
{
    private static readonly HttpClient Http = CreateHttp();
    private static List<Food>? _crea;

    private static HttpClient CreateHttp()
    {
        var h = new HttpClient { Timeout = TimeSpan.FromSeconds(12) };
        h.DefaultRequestHeaders.UserAgent.ParseAdd("Relay/1.0 (app personale)");
        return h;
    }

    private static List<Food> All()
    {
        if (_crea != null) return _crea;
        var list = new List<Food>();
        try
        {
            foreach (var l in File.ReadLines(Path.Combine(AppContext.BaseDirectory, "Assets", "crea.txt")))
            {
                var x = l.Split('|');
                if (x.Length < 9) continue;
                double V(int i) => double.Parse(x[i], CultureInfo.InvariantCulture);
                list.Add(new Food("", x[0], "CREA", int.Parse(x[8], CultureInfo.InvariantCulture), new Nut(V(1), V(2), V(3), V(4), V(5), V(6), V(7))));
            }
        }
        catch (Exception) { }
        return _crea = list;
    }

    public static Food? Crea(string name) => All().FirstOrDefault(f => f.Name == name);

    private static string Norm(string t) =>
        new string(t.ToLowerInvariant().Normalize(NormalizationForm.FormD).Where(c => CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark).ToArray()).Trim();

    private static Food? Parse(JsonNode? p)
    {
        if (p is not JsonObject o || o["nutriments"] is not JsonObject n) return null;
        var name = (o["product_name"]?.ToString() ?? "").Trim();
        if (name.Length == 0) return null;
        double V(string k) => n[k] is JsonValue v && v.TryGetValue<double>(out var d) ? d : n[k] is JsonValue w && double.TryParse(w.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var e) ? e : 0;
        var kcal = n["energy-kcal_100g"] != null ? V("energy-kcal_100g") : V("energy_100g") / 4.184;
        if (kcal <= 0 && V("proteins_100g") + V("carbohydrates_100g") + V("fat_100g") <= 0) return null;
        var serving = double.TryParse(o["serving_quantity"]?.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var sv) && sv >= 5 && sv <= 1000 ? (int)sv : 100;
        var brand = (o["brands"]?.ToString() ?? "").Split(',')[0].Trim();
        return new Food("", name, brand, serving, new Nut(kcal, V("proteins_100g"), V("carbohydrates_100g"), V("sugars_100g"), V("fat_100g"), V("fiber_100g"), V("salt_100g")));
    }

    private static async Task<List<Food>> Fetch(string url)
    {
        try
        {
            var o = JsonNode.Parse(await Http.GetStringAsync(url)) as JsonObject;
            if (o == null) return new();
            if (o["product"] is JsonObject one) return Parse(one) is { } f ? new() { f } : new();
            return (o["products"] as JsonArray)?.Select(Parse).OfType<Food>().ToList() ?? new();
        }
        catch (Exception) { return new(); }
    }

    private const string Fields = "product_name,brands,nutriments,serving_quantity";

    private static Task<List<Food>> List(string term, string filter) =>
        Fetch($"https://it.openfoodfacts.org/cgi/search.pl?search_terms={Uri.EscapeDataString(term)}&search_simple=1&action=process&json=1&page_size=24&sort_by=unique_scans_n&fields={Fields}{filter}");

    public static async Task<List<Food>> Search(string query)
    {
        var q = query.Trim();
        if (q.Length == 0) return new();
        if (q.All(char.IsDigit) && q.Length is >= 8 and <= 14) return await Fetch($"https://world.openfoodfacts.org/api/v2/product/{q}.json?fields={Fields}");
        var ess = Norm(q).Contains("esselunga");
        var term = System.Text.RegularExpressions.Regex.Replace(System.Text.RegularExpressions.Regex.Replace(q, "(?i)esselunga", " "), @"\s+", " ").Trim();
        var words = Norm(term).Split(' ', StringSplitOptions.RemoveEmptyEntries);
        var local = ess || words.Length == 0 ? new List<Food>()
            : All().Where(f => { var n = Norm(f.Name); return words.All(n.Contains); })
                .OrderBy(f => { var n = Norm(f.Name); return (n.StartsWith(words[0]) ? 0 : 1) * 1000 + n.Length; }).Take(12).ToList();
        List<Food> online;
        if (ess) online = await List(term, "&tagtype_0=brands&tag_contains_0=contains&tag_0=esselunga");
        else
        {
            online = await List(term, "&tagtype_0=countries&tag_contains_0=contains&tag_0=italy");
            if (online.Count < 6) online = online.Concat(await List(term, "")).ToList();
        }
        return local.Concat(online).GroupBy(f => Norm(f.Name) + "|" + Norm(f.Brand)).Select(g => g.First()).Take(30).ToList();
    }
}
