using System.Media;

namespace PCMobileLink.Windows.App;

internal static class TransferSoundPlayer
{
    public static void PlaySuccess(bool enabled)
    {
        if (enabled)
        {
            SystemSounds.Asterisk.Play();
        }
    }

    public static void PlayFailure(bool enabled)
    {
        if (enabled)
        {
            SystemSounds.Exclamation.Play();
        }
    }
}
