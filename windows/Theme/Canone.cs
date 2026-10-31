using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;

namespace Ponte;

/// <summary>Colori di Canone letti dal codice. L'indicizzatore di Application.Resources non guarda dentro i temi, qui sì.</summary>
public static class Canone
{
    public static bool IsDark => Application.Current.RequestedTheme == ApplicationTheme.Dark;

    public static Brush Get(string key, bool? dark = null)
    {
        try
        {
            var theme = (dark ?? IsDark) ? "Dark" : "Light";
            if (Find(Application.Current.Resources, theme, key) is Brush b) return b;
        }
        catch (Exception) { }
        return new SolidColorBrush(Microsoft.UI.Colors.Transparent);
    }

    public static Windows.UI.Color Color(string key, bool? dark = null) => (Get(key, dark) as SolidColorBrush)?.Color ?? Microsoft.UI.Colors.Transparent;

    private static object? Find(ResourceDictionary d, string theme, string key)
    {
        if (d.ThemeDictionaries.TryGetValue(theme, out var t) && t is ResourceDictionary td && td.TryGetValue(key, out var v)) return v;
        if (d.TryGetValue(key, out var plain)) return plain;
        foreach (var m in d.MergedDictionaries)
        {
            var r = Find(m, theme, key);
            if (r != null) return r;
        }
        return null;
    }
}
