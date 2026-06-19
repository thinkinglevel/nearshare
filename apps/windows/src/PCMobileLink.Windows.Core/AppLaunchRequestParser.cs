namespace PCMobileLink.Windows.Core;

public static class AppLaunchRequestParser
{
    public static AppLaunchRequest Parse(IReadOnlyList<string> args)
    {
        ArgumentNullException.ThrowIfNull(args);

        if (args.Count == 0)
        {
            return new AppLaunchRequest
            {
                Mode = LaunchMode.Dashboard
            };
        }

        string command = args[0];

        if (string.Equals(command, "--background", StringComparison.OrdinalIgnoreCase))
        {
            return new AppLaunchRequest
            {
                Mode = LaunchMode.Background
            };
        }

        if (string.Equals(command, "send", StringComparison.OrdinalIgnoreCase))
        {
            return ParseSendRequest(args.Skip(1).ToArray());
        }

        return new AppLaunchRequest
        {
            Mode = LaunchMode.Dashboard,
            ErrorMessage = $"Unknown command: {command}"
        };
    }

    private static AppLaunchRequest ParseSendRequest(IReadOnlyList<string> args)
    {
        Guid? targetDeviceId = null;
        List<string> paths = [];
        string? errorMessage = null;

        for (int index = 0; index < args.Count; index++)
        {
            string argument = args[index];
            if (string.Equals(argument, "--device", StringComparison.OrdinalIgnoreCase))
            {
                if (index + 1 >= args.Count || !Guid.TryParse(args[index + 1], out Guid parsedDeviceId))
                {
                    errorMessage = "The selected NearShare device ID is invalid.";
                    if (index + 1 < args.Count)
                    {
                        index++;
                    }
                    continue;
                }

                targetDeviceId = parsedDeviceId;
                index++;
                continue;
            }

            paths.Add(argument);
        }

        if (paths.Count == 0)
        {
            errorMessage ??= "No files were provided to send.";
        }

        return new AppLaunchRequest
        {
            Mode = LaunchMode.Send,
            Paths = paths,
            TargetDeviceId = targetDeviceId,
            ErrorMessage = errorMessage
        };
    }
}
