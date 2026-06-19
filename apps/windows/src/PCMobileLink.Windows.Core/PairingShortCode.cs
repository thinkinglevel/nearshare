using System.Security.Cryptography;

namespace PCMobileLink.Windows.Core;

public static class PairingShortCode
{
    private const int CodeLength = 9;
    private const string Alphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    public static string Generate()
    {
        Span<char> characters = stackalloc char[CodeLength];
        byte[] randomBytes = RandomNumberGenerator.GetBytes(CodeLength);
        for (int index = 0; index < CodeLength; index++)
        {
            characters[index] = Alphabet[randomBytes[index] % Alphabet.Length];
        }

        return new string(characters);
    }

    public static string Normalize(string value)
    {
        return new string(value.Where(char.IsLetterOrDigit).Select(char.ToUpperInvariant).ToArray());
    }

    public static bool IsValid(string value)
    {
        string normalized = Normalize(value);
        return normalized.Length == CodeLength && normalized.All(character => Alphabet.Contains(character));
    }

    public static string Format(string value)
    {
        string normalized = Normalize(value);
        return normalized.Length == CodeLength
            ? $"{normalized[..3]}-{normalized.Substring(3, 3)}-{normalized.Substring(6, 3)}"
            : value;
    }
}
