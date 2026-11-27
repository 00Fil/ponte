using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics.Imaging;
using Windows.Storage;

namespace Ponte;

/// <summary>
/// Immagine scelta per ogni dispositivo (un PNG, anche un render 3D con sfondo trasparente), come su Android (DevicePics.kt).
/// Resta su questo PC, accanto all'id del dispositivo.
/// </summary>
public static class DevicePics
{
    private const double Side = 768;
    private static readonly string Dir = Path.Combine(Settings.Dir, "devpics");
    private static readonly Dictionary<string, BitmapImage?> Mem = new();

    private static string File_(string id) => Path.Combine(Dir, new string(id.Where(c => char.IsLetterOrDigit(c) || c == '-').ToArray()) + ".png");

    /// <summary>L'immagine del dispositivo, se ne ha una. Solo dal thread dell'interfaccia.</summary>
    public static BitmapImage? Get(string? id)
    {
        if (string.IsNullOrEmpty(id)) return null;
        if (Mem.TryGetValue(id, out var cached)) return cached;
        BitmapImage? bmp = null;
        try
        {
            var path = File_(id);
            // IgnoreImageCache: dopo un cambio il file ha lo stesso nome
            if (File.Exists(path)) bmp = new BitmapImage { CreateOptions = BitmapCreateOptions.IgnoreImageCache, UriSource = new Uri(path) };
        }
        catch (Exception) { }
        Mem[id] = bmp;
        return bmp;
    }

    /// <summary>Legge l'immagine scelta, la riduce (lato lungo 768) e la salva come PNG: la trasparenza si conserva.</summary>
    public static async Task<bool> Save(string id, StorageFile source)
    {
        try
        {
            using var input = await source.OpenAsync(FileAccessMode.Read);
            var decoder = await BitmapDecoder.CreateAsync(input);
            var k = Math.Min(1.0, Side / Math.Max(decoder.PixelWidth, decoder.PixelHeight));
            var transform = new BitmapTransform
            {
                ScaledWidth = (uint)Math.Max(1, Math.Round(decoder.PixelWidth * k)),
                ScaledHeight = (uint)Math.Max(1, Math.Round(decoder.PixelHeight * k)),
                InterpolationMode = BitmapInterpolationMode.Fant,
            };
            using var pixels = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, transform,
                ExifOrientationMode.RespectExifOrientation, ColorManagementMode.ColorManageToSRgb);
            Directory.CreateDirectory(Dir);
            var folder = await StorageFolder.GetFolderFromPathAsync(Dir);
            var target = File_(id);
            var temp = await folder.CreateFileAsync(Path.GetFileName(target) + ".tmp", CreationCollisionOption.ReplaceExisting);
            using (var output = await temp.OpenAsync(FileAccessMode.ReadWrite))
            {
                var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, output);
                encoder.SetSoftwareBitmap(pixels);
                await encoder.FlushAsync();
            }
            File.Move(temp.Path, target, true);
            Mem.Remove(id);
            return true;
        }
        catch (Exception e) { Crash.Log(e, "Immagine dispositivo"); return false; }
    }

    public static void Remove(string id)
    {
        try { File.Delete(File_(id)); } catch (Exception) { }
        Mem.Remove(id);
    }
}
