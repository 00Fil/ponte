using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Ponte;

/// <summary>Le immagini scelte per i dispositivi, ovunque compare un dispositivo.</summary>
public sealed partial class MainWindow
{
    /// <summary>L'immagine del dispositivo dentro un cerchio di lato [size], altrimenti l'icona data.</summary>
    private static FrameworkElement DeviceFace(string? id, double size, double inset, FrameworkElement fallback) =>
        DevicePics.Get(id) is { } pic
            ? new Image { Source = pic, Stretch = Stretch.Uniform, Width = size - inset * 2, Height = size - inset * 2, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center }
            : Center(fallback);

    private async Task PickDevicePicAsync(string id)
    {
        var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.PicturesLibrary };
        foreach (var ext in new[] { ".png", ".jpg", ".jpeg", ".webp", ".bmp" }) picker.FileTypeFilter.Add(ext);
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(this));
        var file = await picker.PickSingleFileAsync();
        if (file == null) return;
        if (await DevicePics.Save(id, file)) DevicePicChanged();
        else await new ContentDialog { XamlRoot = Root.XamlRoot, Title = "Immagine non leggibile", CloseButtonText = "Chiudi" }.ShowAsync();
    }

    private void DevicePicChanged()
    {
        if (_devOpen)
        {
            FillDevices();
            if (_morph != null) _morph.Height = MorphHeight();
        }
        _homeHold = DateTime.MinValue;
        Refresh();
    }
}
