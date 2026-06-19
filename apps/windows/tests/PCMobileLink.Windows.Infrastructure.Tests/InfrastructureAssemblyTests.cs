using PCMobileLink.Windows.Infrastructure;

namespace PCMobileLink.Windows.Infrastructure.Tests;

public sealed class InfrastructureAssemblyTests
{
    [Fact]
    public void InfrastructureAssemblyMarker_IdentifiesTheInfrastructureProject()
    {
        Assert.Equal(
            "PCMobileLink.Windows.Infrastructure",
            typeof(InfrastructureAssemblyMarker).Assembly.GetName().Name);
    }
}
