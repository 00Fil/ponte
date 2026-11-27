using System.Globalization;
using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Graphics.Imaging;
using Windows.Storage;
using Windows.Storage.Streams;

namespace Ponte;

/// <summary>
/// Le foto del fisico, come su Android (Body.savePhoto): una al giorno, verticale 3:4 (al massimo 1080 × 1440) con la sua miniatura.
/// Restano su questo PC: non si sincronizzano.
/// </summary>
public static class BodyPhotos
{
    private const uint Wide = 1080, High = 1440, ThumbWide = 360, ThumbHigh = 480;
    private static readonly string Dir = Path.Combine(Settings.Dir, "corpo");

    public static string Photo(string day) => Path.Combine(Dir, $"ph_{day}.jpg");
    public static string Thumb(string day) => Path.Combine(Dir, $"th_{day}.jpg");

    /// <summary>I giorni che hanno una foto, dal più vecchio.</summary>
    public static List<string> Days()
    {
        try
        {
            if (!Directory.Exists(Dir)) return new();
            return Directory.EnumerateFiles(Dir, "ph_*.jpg")
                .Select(p => Path.GetFileNameWithoutExtension(p)[3..])
                .Where(d => DateTime.TryParseExact(d, "yyyyMMdd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _))
                .OrderBy(d => d, StringComparer.Ordinal).ToList();
        }
        catch (Exception) { return new(); }
    }

    /// <summary>Taglia al centro in 3:4: sempre la stessa inquadratura. Il risultato è opaco, pronto da mostrare e salvare.</summary>
    public static SoftwareBitmap Frame(SoftwareBitmap source)
    {
        using var bgra = SoftwareBitmap.Convert(source, BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);
        int w = bgra.PixelWidth, h = bgra.PixelHeight;
        var cw = (long)w * 4 > (long)h * 3 ? h * 3 / 4 : w;
        var ch = (long)w * 4 > (long)h * 3 ? h : w * 4 / 3;
        var all = new byte[w * h * 4];
        bgra.CopyToBuffer(all.AsBuffer());
        var cut = new byte[cw * ch * 4];
        int x0 = (w - cw) / 2, y0 = (h - ch) / 2;
        for (var y = 0; y < ch; y++) System.Buffer.BlockCopy(all, ((y0 + y) * w + x0) * 4, cut, y * cw * 4, cw * 4);
        for (var i = 3; i < cut.Length; i += 4) cut[i] = 255;
        var result = new SoftwareBitmap(BitmapPixelFormat.Bgra8, cw, ch, BitmapAlphaMode.Premultiplied);
        result.CopyFromBuffer(cut.AsBuffer());
        return result;
    }

    /// <summary>Un'immagine da file o da scatto: raddrizzata con l'EXIF, ridotta e tagliata.</summary>
    public static async Task<SoftwareBitmap> Read(IRandomAccessStream input)
    {
        var decoder = await BitmapDecoder.CreateAsync(input);
        var cropWide = Math.Min(decoder.OrientedPixelWidth, decoder.OrientedPixelHeight * 3.0 / 4);
        var k = Math.Min(1.0, Wide / Math.Max(1, cropWide));
        var transform = new BitmapTransform
        {
            ScaledWidth = (uint)Math.Max(1, Math.Round(decoder.PixelWidth * k)),
            ScaledHeight = (uint)Math.Max(1, Math.Round(decoder.PixelHeight * k)),
            InterpolationMode = BitmapInterpolationMode.Fant,
        };
        using var pixels = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, transform,
            ExifOrientationMode.RespectExifOrientation, ColorManagementMode.ColorManageToSRgb);
        return Frame(pixels);
    }

    /// <summary>Salva la foto del giorno e la miniatura per il time lapse e la galleria.</summary>
    public static async Task<bool> Save(string day, SoftwareBitmap photo)
    {
        try
        {
            Directory.CreateDirectory(Dir);
            var big = Photo(day) + ".tmp";
            var small = Thumb(day) + ".tmp";
            var shrink = photo.PixelWidth > Wide;
            await Jpeg(big, photo, 0.90f, shrink ? Wide : 0, shrink ? High : 0);
            await Jpeg(small, photo, 0.82f, ThumbWide, ThumbHigh);
            File.Move(small, Thumb(day), true);
            File.Move(big, Photo(day), true);
            return true;
        }
        catch (Exception e) { Crash.Log(e, "Foto del fisico"); return false; }
    }

    private static async Task Jpeg(string path, SoftwareBitmap bitmap, float quality, uint width, uint height)
    {
        using var file = File.Create(path);
        using var stream = file.AsRandomAccessStream();
        var options = new BitmapPropertySet { { "ImageQuality", new BitmapTypedValue(quality, Windows.Foundation.PropertyType.Single) } };
        var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.JpegEncoderId, stream, options);
        encoder.SetSoftwareBitmap(bitmap);
        if (width > 0)
        {
            encoder.BitmapTransform.ScaledWidth = width;
            encoder.BitmapTransform.ScaledHeight = height;
            encoder.BitmapTransform.InterpolationMode = BitmapInterpolationMode.Fant;
        }
        await encoder.FlushAsync();
    }

    public static void Delete(string day)
    {
        try { File.Delete(Photo(day)); File.Delete(Thumb(day)); } catch (Exception e) { Crash.Log(e, "Foto del fisico"); }
    }

    /// <summary>Il giorno in cui è stata scattata una foto importata (EXIF, altrimenti la data del file); mai nel futuro.</summary>
    public static async Task<DateTime> TakenOn(StorageFile file)
    {
        var when = DateTime.MinValue;
        try { when = (await file.Properties.GetImagePropertiesAsync()).DateTaken.LocalDateTime; } catch (Exception) { }
        if (when.Year < 1990) { try { when = File.GetLastWriteTime(file.Path); } catch (Exception) { } }
        return when.Year < 1990 || when > DateTime.Now ? DateTime.Now : when;
    }
}
