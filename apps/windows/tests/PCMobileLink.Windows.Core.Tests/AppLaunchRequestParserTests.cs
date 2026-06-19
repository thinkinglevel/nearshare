using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Core.Tests;

public sealed class AppLaunchRequestParserTests
{
    [Fact]
    public void Parse_WithoutArguments_OpensDashboard()
    {
        AppLaunchRequest request = AppLaunchRequestParser.Parse([]);

        Assert.Equal(LaunchMode.Dashboard, request.Mode);
        Assert.Empty(request.Paths);
        Assert.False(request.HasError);
    }

    [Fact]
    public void Parse_SendWithPaths_OpensSendQueue()
    {
        AppLaunchRequest request = AppLaunchRequestParser.Parse([
            "send",
            @"C:\Users\AKSH\Pictures\photo.jpg",
            @"D:\Downloads\notes.pdf"
        ]);

        Assert.Equal(LaunchMode.Send, request.Mode);
        Assert.Null(request.TargetDeviceId);
        Assert.Equal([
            @"C:\Users\AKSH\Pictures\photo.jpg",
            @"D:\Downloads\notes.pdf"
        ], request.Paths);
        Assert.False(request.HasError);
    }

    [Fact]
    public void Parse_SendWithDeviceOption_StartsDeviceSpecificSend()
    {
        AppLaunchRequest request = AppLaunchRequestParser.Parse([
            "send",
            "--device",
            "8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b",
            @"C:\Users\AKSH\Pictures\photo.jpg",
            @"D:\Downloads\notes.pdf"
        ]);

        Assert.Equal(LaunchMode.Send, request.Mode);
        Assert.Equal(Guid.Parse("8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b"), request.TargetDeviceId);
        Assert.Equal([
            @"C:\Users\AKSH\Pictures\photo.jpg",
            @"D:\Downloads\notes.pdf"
        ], request.Paths);
        Assert.False(request.HasError);
    }

    [Fact]
    public void Parse_SendWithDeviceOptionButNoPaths_ReturnsUserFacingError()
    {
        AppLaunchRequest request = AppLaunchRequestParser.Parse([
            "send",
            "--device",
            "8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b"
        ]);

        Assert.Equal(LaunchMode.Send, request.Mode);
        Assert.Equal(Guid.Parse("8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b"), request.TargetDeviceId);
        Assert.Empty(request.Paths);
        Assert.True(request.HasError);
        Assert.Equal("No files were provided to send.", request.ErrorMessage);
    }

    [Fact]
    public void Parse_SendWithInvalidDeviceOption_ReturnsUserFacingError()
    {
        AppLaunchRequest request = AppLaunchRequestParser.Parse([
            "send",
            "--device",
            "not-a-guid",
            @"D:\Downloads\notes.pdf"
        ]);

        Assert.Equal(LaunchMode.Send, request.Mode);
        Assert.Null(request.TargetDeviceId);
        Assert.Equal([@"D:\Downloads\notes.pdf"], request.Paths);
        Assert.True(request.HasError);
        Assert.Equal("The selected NearShare device ID is invalid.", request.ErrorMessage);
    }

    [Fact]
    public void Parse_SendCommand_IsCaseInsensitive()
    {
        AppLaunchRequest request = AppLaunchRequestParser.Parse(["SEND", @"D:\Downloads\notes.pdf"]);

        Assert.Equal(LaunchMode.Send, request.Mode);
        Assert.Equal([@"D:\Downloads\notes.pdf"], request.Paths);
    }

    [Fact]
    public void Parse_SendWithoutPaths_ReturnsSendRequestWithUserFacingError()
    {
        AppLaunchRequest request = AppLaunchRequestParser.Parse(["send"]);

        Assert.Equal(LaunchMode.Send, request.Mode);
        Assert.Empty(request.Paths);
        Assert.True(request.HasError);
        Assert.Equal("No files were provided to send.", request.ErrorMessage);
    }

    [Fact]
    public void Parse_BackgroundCommand_StartsHiddenToTray()
    {
        AppLaunchRequest request = AppLaunchRequestParser.Parse(["--background"]);

        Assert.Equal(LaunchMode.Background, request.Mode);
        Assert.Empty(request.Paths);
        Assert.False(request.HasError);
    }

    [Fact]
    public void Parse_UnknownCommand_ReturnsDashboardWithUserFacingError()
    {
        AppLaunchRequest request = AppLaunchRequestParser.Parse(["pair"]);

        Assert.Equal(LaunchMode.Dashboard, request.Mode);
        Assert.Empty(request.Paths);
        Assert.True(request.HasError);
        Assert.Equal("Unknown command: pair", request.ErrorMessage);
    }
}
