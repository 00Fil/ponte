namespace Ponte;

public sealed class FinanceEntry
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public long Ts { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    /// <summary>Importo con segno, espresso in centesimi.</summary>
    public long Cents { get; set; }
    public string Kind { get; set; } = "expense";
    public string Category { get; set; } = "Altro";
    public string Note { get; set; } = "";
}

public static class FinanceLedger
{
    public static long Balance(IEnumerable<FinanceEntry> entries) => entries.Sum(e => e.Cents);

    public static void SetBalance(Settings settings, long cents)
    {
        var current = Balance(settings.FinanceEntries);
        var delta = cents - current;
        if (delta == 0 && settings.FinanceEntries.Count > 0) return;
        settings.FinanceEntries.Add(new FinanceEntry
        {
            Cents = delta,
            Kind = settings.FinanceEntries.Count == 0 ? "opening" : "adjustment",
            Category = "Saldo",
            Note = "Saldo aggiornato",
        });
        settings.Save();
    }

    public static void Add(Settings settings, string kind, long cents, string category, string note)
    {
        if (kind is not ("income" or "expense") || cents <= 0) return;
        settings.FinanceEntries.Add(new FinanceEntry
        {
            Cents = kind == "income" ? cents : -cents,
            Kind = kind,
            Category = category,
            Note = note.Trim(),
        });
        settings.Save();
    }

    public static void Delete(Settings settings, string id)
    {
        settings.FinanceEntries.RemoveAll(e => e.Id == id);
        settings.Save();
    }
}
