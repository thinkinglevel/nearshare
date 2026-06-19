using System.Windows;
using System.Windows.Media;
using MediaBrush = System.Windows.Media.Brush;
using MediaColor = System.Windows.Media.Color;

namespace PCMobileLink.Windows.App;

public enum ThemedConfirmationKind
{
    Information,
    Warning,
    Danger
}

public sealed record ThemedConfirmationOptions
{
    public required string Title { get; init; }

    public required string Subtitle { get; init; }

    public required string Message { get; init; }

    public string ConfirmText { get; init; } = "Continue";

    public string CancelText { get; init; } = "Cancel";

    public ThemedConfirmationKind Kind { get; init; } = ThemedConfirmationKind.Information;
}

public partial class ThemedConfirmationDialog : Window
{
    public ThemedConfirmationDialog(ThemedConfirmationOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);

        InitializeComponent();
        TitleTextBlock.Text = options.Title;
        SubtitleTextBlock.Text = options.Subtitle;
        MessageTextBlock.Text = options.Message;
        ConfirmButton.Content = options.ConfirmText;
        CancelButton.Content = options.CancelText;
        ApplyKind(options.Kind);
    }

    public static bool Confirm(Window owner, ThemedConfirmationOptions options)
    {
        ThemedConfirmationDialog dialog = new(options)
        {
            Owner = owner
        };

        return dialog.ShowDialog() == true;
    }

    private void ApplyKind(ThemedConfirmationKind kind)
    {
        switch (kind)
        {
            case ThemedConfirmationKind.Danger:
                IconCircle.Background = new SolidColorBrush(MediaColor.FromRgb(0xFE, 0xF2, 0xF2));
                IconTextBlock.Text = "!";
                IconTextBlock.Foreground = new SolidColorBrush(MediaColor.FromRgb(0xB9, 0x1C, 0x1C));
                ConfirmButton.Style = (Style)FindResource("DialogDangerButton");
                break;
            case ThemedConfirmationKind.Warning:
                IconCircle.Background = new SolidColorBrush(MediaColor.FromRgb(0xFF, 0xFB, 0xEB));
                IconTextBlock.Text = "!";
                IconTextBlock.Foreground = new SolidColorBrush(MediaColor.FromRgb(0xD9, 0x77, 0x06));
                break;
            default:
                IconCircle.Background = new SolidColorBrush(MediaColor.FromRgb(0xEF, 0xF6, 0xFF));
                IconTextBlock.Text = "i";
                IconTextBlock.Foreground = (MediaBrush)FindResource("PrimaryBrush");
                break;
        }
    }

    private void ConfirmButton_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = true;
    }

    private void CancelButton_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
    }
}
