using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Core.Tests;

public sealed class SafeFileNameTests
{
    [Theory]
    [InlineData("report.pdf", "report.pdf")]
    [InlineData("..\\evil/CON:name?.txt", ".._evil_CON_name_.txt")]
    [InlineData("photo<2026>|final*.jpg", "photo_2026__final_.jpg")]
    public void SanitizeForWindowsReceiveFolder_ReplacesUnsafeCharacters(string input, string expected)
    {
        string sanitized = SafeFileName.SanitizeForWindowsReceiveFolder(input);

        Assert.Equal(expected, sanitized);
        Assert.DoesNotContain('/', sanitized);
        Assert.DoesNotContain('\\', sanitized);
    }

    [Theory]
    [InlineData("CON", "_CON")]
    [InlineData("con.txt", "_con.txt")]
    [InlineData("LPT1.log", "_LPT1.log")]
    [InlineData("nul", "_nul")]
    public void SanitizeForWindowsReceiveFolder_PrefixesReservedWindowsDeviceNames(string input, string expected)
    {
        Assert.Equal(expected, SafeFileName.SanitizeForWindowsReceiveFolder(input));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("...")]
    public void SanitizeForWindowsReceiveFolder_FallsBackWhenNameIsEmptyAfterSanitizing(string? input)
    {
        Assert.Equal("unnamed", SafeFileName.SanitizeForWindowsReceiveFolder(input));
    }
}
