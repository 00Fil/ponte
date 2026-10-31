using System.Globalization;
using System.Numerics;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;
using Windows.Storage.Streams;
using Windows.System;

namespace Ponte;

/// <summary>Visualizzatore di foto dentro la finestra: frecce o tasti per scorrere, Esc per chiudere, Ctrl + rotella o doppio clic per ingrandire.</summary>
public sealed partial class MainWindow
{
    private List<FileEntry> _viewList = new();
    private int _viewIndex;

    private void InitViewerKeys()
    {
        void Add(VirtualKey key, Action act)
        {
            var a = new KeyboardAccelerator { Key = key };
            a.Invoked += (_, args) =>
            {
                if (Viewer.Visibility != Visibility.Visible) return;
                args.Handled = true;
                act();
            };
            Root.KeyboardAccelerators.Add(a);
        }
        Add(VirtualKey.Escape, CloseViewer);
        Add(VirtualKey.Left, () => StepViewer(-1));
        Add(VirtualKey.Right, () => StepViewer(1));
    }

    private void OpenViewer(FileEntry e)
    {
        _viewList = _r.Files.Items.Where(f => (_filter.Length == 0 || f.Kind == _filter) && f.Mime.StartsWith("image/")).ToList();
        _viewIndex = Math.Max(0, _viewList.FindIndex(f => f.Fid == e.Fid));
        Viewer.Visibility = Visibility.Visible;
        ShowViewerImage(false);
        FadeTo(Viewer, 1, 260);
        // L'immagine entra ingrandendosi appena, con la stessa curva del resto dell'app.
        var v = ElementCompositionPreview.GetElementVisual(ViewerBox);
        v.CenterPoint = new Vector3((float)ViewerBox.ActualWidth / 2, (float)ViewerBox.ActualHeight / 2, 0);
        var c = v.Compositor;
        var s = c.CreateVector3KeyFrameAnimation();
        s.InsertKeyFrame(0f, new Vector3(0.94f, 0.94f, 1f));
        s.InsertKeyFrame(1f, Vector3.One, c.CreateCubicBezierEasingFunction(new Vector2(0.2f, 0f), new Vector2(0f, 1f)));
        s.Duration = TimeSpan.FromMilliseconds(340);
        v.StartAnimation("Scale", s);
    }

    private void ShowViewerImage(bool animate)
    {
        if (_viewList.Count == 0) { CloseViewer(); return; }
        var e = _viewList[_viewIndex];
        try { ViewerImg.Source = new BitmapImage(new Uri(e.Path)); } catch (Exception) { ViewerImg.Source = null; }
        ViewerScroll.ChangeView(0, 0, 1, true);
        ViewerName.Text = e.Name;
        var when = DateTimeOffset.FromUnixTimeMilliseconds(e.Ts).ToLocalTime().ToString("g", CultureInfo.CurrentCulture);
        ViewerInfo.Text = $"{_viewIndex + 1} di {_viewList.Count} · {FormatSize(e.Size)} · {when}";
        ViewerPrev.IsEnabled = _viewIndex > 0;
        ViewerNext.IsEnabled = _viewIndex < _viewList.Count - 1;
        ViewerPrev.Opacity = ViewerPrev.IsEnabled ? 1 : 0.35;
        ViewerNext.Opacity = ViewerNext.IsEnabled ? 1 : 0.35;
        if (animate) Enter(ViewerBox, 0, 0, 260);
    }

    private void StepViewer(int d)
    {
        var n = _viewIndex + d;
        if (n < 0 || n >= _viewList.Count) return;
        _viewIndex = n;
        ShowViewerImage(true);
    }

    private async void CloseViewer()
    {
        if (Viewer.Visibility != Visibility.Visible) return;
        FadeTo(Viewer, 0, 200);
        await Task.Delay(210);
        Viewer.Visibility = Visibility.Collapsed;
        ViewerImg.Source = null;
    }

    private void OnViewerClose(object s, RoutedEventArgs e) => CloseViewer();
    private void OnViewerPrev(object s, RoutedEventArgs e) => StepViewer(-1);
    private void OnViewerNext(object s, RoutedEventArgs e) => StepViewer(1);
    private void OnViewerBackdrop(object s, TappedRoutedEventArgs e) => CloseViewer();

    private void OnViewerSize(object s, SizeChangedEventArgs e)
    {
        ViewerBox.Width = ViewerScroll.ViewportWidth > 0 ? ViewerScroll.ViewportWidth : e.NewSize.Width;
        ViewerBox.Height = ViewerScroll.ViewportHeight > 0 ? ViewerScroll.ViewportHeight : e.NewSize.Height;
    }

    private void OnViewerDouble(object s, DoubleTappedRoutedEventArgs e)
    {
        var zoomed = ViewerScroll.ZoomFactor > 1.05f;
        ViewerScroll.ChangeView(null, null, zoomed ? 1f : 2.5f);
    }

    private void OnViewerOpen(object s, RoutedEventArgs e)
    {
        if (_viewList.Count > 0) _r.Files.Open(_viewList[_viewIndex]);
    }

    private async void OnViewerCopy(object s, RoutedEventArgs e)
    {
        if (_viewList.Count == 0) return;
        try
        {
            var f = await StorageFile.GetFileFromPathAsync(_viewList[_viewIndex].Path);
            var dp = new DataPackage();
            dp.SetBitmap(RandomAccessStreamReference.CreateFromFile(f));
            Clipboard.SetContent(dp);
        }
        catch (Exception) { }
    }
}
