using System.Net;
using System.Security.Cryptography;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Infrastructure.Pairing;

public sealed class LocalPairingServer : IAsyncDisposable
{
    private const int ResumableChunkSizeBytes = 256 * 1024;

    private readonly WebApplication _app;
    private readonly LocalPairingCertificate _certificate;
    private readonly List<PendingPairingRequest> _pendingRequests;
    private readonly object _pendingRequestsLock;

    private LocalPairingServer(
        WebApplication app,
        LocalPairingCertificate certificate,
        PairingOffer offer,
        List<PendingPairingRequest> pendingRequests,
        object pendingRequestsLock)
    {
        _app = app;
        _certificate = certificate;
        Offer = offer;
        _pendingRequests = pendingRequests;
        _pendingRequestsLock = pendingRequestsLock;
    }

    public PairingOffer Offer { get; }

    public string CertificateFingerprint => _certificate.Sha256Fingerprint;

    public IReadOnlyList<PendingPairingRequest> PendingRequests
    {
        get
        {
            lock (_pendingRequestsLock)
            {
                return _pendingRequests.ToArray();
            }
        }
    }

    public PairedDeviceRecord ApproveRequest(Guid requestId)
    {
        lock (_pendingRequestsLock)
        {
            PendingPairingRequest request = _pendingRequests.FirstOrDefault(candidate => candidate.RequestId == requestId)
                ?? throw new InvalidOperationException("Pairing request was not found.");

            if (request.ApprovedDevice is not null)
            {
                return request.ApprovedDevice;
            }

            if (string.Equals(request.Status, "rejected", StringComparison.Ordinal))
            {
                throw new InvalidOperationException("Rejected pairing requests cannot be approved.");
            }

            DateTimeOffset now = DateTimeOffset.UtcNow;
            PairedDeviceRecord pairedDevice = new()
            {
                DeviceId = Guid.NewGuid(),
                DeviceName = request.DeviceName,
                DevicePublicKey = request.DevicePublicKey,
                SharedSecret = GenerateSharedSecret(),
                PairedAt = now,
                LastSeenAt = now,
                ReceiveEndpoints = request.ReceiveEndpoints,
                ReceiveTlsCertificateSha256 = request.ReceiveTlsCertificateSha256
            };

            request.Status = "approved";
            request.ApprovedDevice = pairedDevice;
            return pairedDevice;
        }
    }

    public bool RejectRequest(Guid requestId)
    {
        lock (_pendingRequestsLock)
        {
            PendingPairingRequest? request = _pendingRequests.FirstOrDefault(candidate => candidate.RequestId == requestId);
            if (request is null)
            {
                return false;
            }

            if (string.Equals(request.Status, "approved", StringComparison.Ordinal))
            {
                return false;
            }

            request.Status = "rejected";
            return true;
        }
    }

    public static async Task<LocalPairingServer> StartAsync(
        LocalPairingServerOptions options,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(options);
        ArgumentException.ThrowIfNullOrWhiteSpace(options.PcName);

        if (options.ListenPort is < 0 or > 65535)
        {
            throw new ArgumentOutOfRangeException(nameof(options.ListenPort), options.ListenPort, "Listen port must be between 0 and 65535.");
        }

        LocalPairingCertificateStore certificateStore = string.IsNullOrWhiteSpace(options.CertificatePath)
            ? LocalPairingCertificateStore.CreateDefault()
            : new LocalPairingCertificateStore(options.CertificatePath);
        LocalPairingCertificate certificate = certificateStore.LoadOrCreate(options.PcName);
        int listenPort = options.ListenPort == 0
            ? ReserveAvailableTcpPort(options.ListenAddress)
            : options.ListenPort;

        string[] qrHosts = options.QrEndpointHosts.Count == 0
            ? [GetQrHostFromListenAddress(options.ListenAddress)]
            : options.QrEndpointHosts.ToArray();
        PairedDeviceStore pairedDeviceStore = string.IsNullOrWhiteSpace(options.PairedDevicesPath)
            ? PairedDeviceStore.CreateDefault()
            : new PairedDeviceStore(options.PairedDevicesPath);
        string receiveFolderPath = string.IsNullOrWhiteSpace(options.ReceiveFolderPath)
            ? DefaultReceiveFolder.GetCurrentUserDownloadsPath()
            : options.ReceiveFolderPath;
        string transferTempFolderPath = string.IsNullOrWhiteSpace(options.TransferTempFolderPath)
            ? Path.Combine(receiveFolderPath, ".nearshare-transfer-temp")
            : options.TransferTempFolderPath;

        PairingOffer offer = PairingOfferFactory.Create(
            pcName: options.PcName,
            endpoints: qrHosts.Select(host => new PairingEndpointCandidate(host, listenPort)).ToArray(),
            tlsCertificateSha256: certificate.Sha256Fingerprint,
            now: DateTimeOffset.UtcNow);

        List<PendingPairingRequest> pendingRequests = [];
        object pendingRequestsLock = new();

        WebApplicationBuilder builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            Args = []
        });
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(kestrel =>
        {
            kestrel.AddServerHeader = false;
            kestrel.Listen(options.ListenAddress, listenPort, listenOptions =>
            {
                listenOptions.Protocols = HttpProtocols.Http1;
                listenOptions.UseHttps(certificate.Certificate);
            });
        });

        WebApplication app = builder.Build();

        app.MapGet("/nearshare/pairing/offers/{offerId:guid}", (Guid offerId) =>
        {
            if (offerId != offer.Payload.OfferId)
            {
                return Results.NotFound();
            }

            if (IsExpired(offer.Payload))
            {
                return Results.StatusCode(StatusCodes.Status410Gone);
            }

            return Results.Json(new PairingOfferStatusResponse
            {
                OfferId = offer.Payload.OfferId,
                PcName = offer.Payload.PcName,
                Status = "ready",
                ExpiresAtUnixTimeSeconds = offer.Payload.ExpiresAtUnixTimeSeconds
            });
        });

        app.MapPost("/nearshare/pairing/requests", (PairingRequestPayload request) =>
        {
            if (request.OfferId != offer.Payload.OfferId)
            {
                return Results.NotFound();
            }

            if (IsExpired(offer.Payload))
            {
                return Results.StatusCode(StatusCodes.Status410Gone);
            }

            if (!string.Equals(request.PairingToken, offer.Payload.PairingToken, StringComparison.Ordinal))
            {
                return Results.Unauthorized();
            }

            if (string.IsNullOrWhiteSpace(request.DeviceName) || string.IsNullOrWhiteSpace(request.DevicePublicKey))
            {
                return Results.BadRequest();
            }

            PendingPairingRequest pendingRequest = new()
            {
                RequestId = Guid.NewGuid(),
                DeviceName = request.DeviceName,
                DevicePublicKey = request.DevicePublicKey,
                ReceiveEndpoints = request.ReceiveEndpoints,
                ReceiveTlsCertificateSha256 = request.ReceiveTlsCertificateSha256,
                ReceivedAt = DateTimeOffset.UtcNow
            };

            lock (pendingRequestsLock)
            {
                pendingRequests.Add(pendingRequest);
            }

            return Results.Accepted(value: new PairingRequestReceipt
            {
                RequestId = pendingRequest.RequestId,
                Status = "pending_confirmation",
                Message = "Pairing request is waiting for confirmation on Windows."
            });
        });

        app.MapGet("/nearshare/pairing/requests/{requestId:guid}", (Guid requestId) =>
        {
            PendingPairingRequest? request;
            lock (pendingRequestsLock)
            {
                request = pendingRequests.FirstOrDefault(candidate => candidate.RequestId == requestId);
            }

            if (request is null)
            {
                return Results.NotFound();
            }

            return Results.Json(CreateRequestResultResponse(request));
        });

        app.MapGet("/nearshare/paired-devices/{deviceId:guid}/reachability", (HttpContext context, Guid deviceId) =>
        {
            PairedDeviceRecord? pairedDevice = pairedDeviceStore.FindByDeviceId(deviceId);
            if (pairedDevice is null)
            {
                return Results.NotFound();
            }

            if (!IsAuthenticatedPairedDeviceRequest(context, pairedDevice, body: []))
            {
                return Results.Unauthorized();
            }

            return Results.Json(new PairedDeviceReachabilityResponse
            {
                Status = "reachable",
                DeviceId = deviceId,
                ServerTimeUnixSeconds = DateTimeOffset.UtcNow.ToUnixTimeSeconds()
            });
        });

        app.MapPost("/nearshare/paired-devices/{deviceId:guid}/transfers/files", async (HttpContext context, Guid deviceId) =>
        {
            PairedDeviceRecord? pairedDevice = pairedDeviceStore.FindByDeviceId(deviceId);
            if (pairedDevice is null)
            {
                return Results.NotFound();
            }

            string? originalFileName = context.Request.Headers["X-NearShare-File-Name"].FirstOrDefault();
            if (string.IsNullOrWhiteSpace(originalFileName))
            {
                return Results.BadRequest("Missing X-NearShare-File-Name header.");
            }

            if (!long.TryParse(
                    context.Request.Headers["X-NearShare-File-Size"].FirstOrDefault(),
                    System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture,
                    out long declaredSizeBytes)
                || declaredSizeBytes < 0)
            {
                return Results.BadRequest("Missing or invalid X-NearShare-File-Size header.");
            }

            SpoolingResult spooledFile = await SpoolRequestBodyAsync(
                context.Request.Body,
                transferTempFolderPath,
                context.RequestAborted).ConfigureAwait(false);

            try
            {
                if (spooledFile.SizeBytes != declaredSizeBytes)
                {
                    DeleteFileIfExists(spooledFile.TempFilePath);
                    return Results.BadRequest("File size does not match X-NearShare-File-Size.");
                }

                if (!IsAuthenticatedPairedDeviceRequest(context, pairedDevice, spooledFile.Sha256Base64Url))
                {
                    DeleteFileIfExists(spooledFile.TempFilePath);
                    return Results.Unauthorized();
                }

                FileTransferReceiveResponse response = await SaveReceivedFileAsync(
                    receiveFolderPath,
                    pairedDevice.DeviceId,
                    originalFileName,
                    spooledFile,
                    context.RequestAborted).ConfigureAwait(false);
                return Results.Json(response);
            }
            catch
            {
                DeleteFileIfExists(spooledFile.TempFilePath);
                throw;
            }
        });

        app.MapPost("/nearshare/paired-devices/{deviceId:guid}/transfer-sessions", async (HttpContext context, Guid deviceId) =>
        {
            PairedDeviceRecord? pairedDevice = pairedDeviceStore.FindByDeviceId(deviceId);
            if (pairedDevice is null)
            {
                return Results.NotFound();
            }

            byte[] requestBody = await ReadRequestBodyAsync(context.Request.Body, context.RequestAborted).ConfigureAwait(false);
            if (!IsAuthenticatedPairedDeviceRequest(context, pairedDevice, requestBody))
            {
                return Results.Unauthorized();
            }

            TransferSessionCreateRequest? request;
            try
            {
                request = System.Text.Json.JsonSerializer.Deserialize<TransferSessionCreateRequest>(
                    requestBody,
                    new System.Text.Json.JsonSerializerOptions(System.Text.Json.JsonSerializerDefaults.Web));
            }
            catch (System.Text.Json.JsonException)
            {
                return Results.BadRequest("Invalid transfer session request body.");
            }

            if (request is null
                || string.IsNullOrWhiteSpace(request.ClientSessionId)
                || string.IsNullOrWhiteSpace(request.FileName)
                || request.FileSizeBytes < 0
                || string.IsNullOrWhiteSpace(request.Sha256)
                || request.FileIndex < 1
                || request.TotalFiles < 1
                || request.FileIndex > request.TotalFiles)
            {
                return Results.BadRequest("Invalid transfer session metadata.");
            }

            TransferSessionManifest manifest = CreateOrResumeTransferSession(
                transferTempFolderPath,
                pairedDevice.DeviceId,
                request);
            return Results.Json(CreateSessionStatusResponse(manifest));
        });

        app.MapGet("/nearshare/paired-devices/{deviceId:guid}/transfer-sessions/{sessionId:guid}", (HttpContext context, Guid deviceId, Guid sessionId) =>
        {
            PairedDeviceRecord? pairedDevice = pairedDeviceStore.FindByDeviceId(deviceId);
            if (pairedDevice is null)
            {
                return Results.NotFound();
            }

            if (!IsAuthenticatedPairedDeviceRequest(context, pairedDevice, body: []))
            {
                return Results.Unauthorized();
            }

            TransferSessionManifest? manifest = LoadTransferSession(transferTempFolderPath, sessionId);
            if (manifest is null || manifest.DeviceId != pairedDevice.DeviceId)
            {
                return Results.NotFound();
            }

            return Results.Json(CreateSessionStatusResponse(manifest));
        });

        app.MapPut("/nearshare/paired-devices/{deviceId:guid}/transfer-sessions/{sessionId:guid}/chunks", async (HttpContext context, Guid deviceId, Guid sessionId) =>
        {
            PairedDeviceRecord? pairedDevice = pairedDeviceStore.FindByDeviceId(deviceId);
            if (pairedDevice is null)
            {
                return Results.NotFound();
            }

            TransferSessionManifest? manifest = LoadTransferSession(transferTempFolderPath, sessionId);
            if (manifest is null || manifest.DeviceId != pairedDevice.DeviceId)
            {
                return Results.NotFound();
            }

            if (!long.TryParse(
                    context.Request.Headers["X-NearShare-Chunk-Offset"].FirstOrDefault(),
                    System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture,
                    out long chunkOffset)
                || chunkOffset < 0)
            {
                return Results.BadRequest("Missing or invalid X-NearShare-Chunk-Offset header.");
            }

            if (!long.TryParse(
                    context.Request.Headers["X-NearShare-Chunk-Size"].FirstOrDefault(),
                    System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture,
                    out long declaredChunkSize)
                || declaredChunkSize < 0
                || declaredChunkSize > ResumableChunkSizeBytes)
            {
                return Results.BadRequest("Missing or invalid X-NearShare-Chunk-Size header.");
            }

            byte[] chunk = await ReadRequestBodyAsync(context.Request.Body, context.RequestAborted).ConfigureAwait(false);
            if (chunk.LongLength != declaredChunkSize)
            {
                return Results.BadRequest("Chunk size does not match X-NearShare-Chunk-Size.");
            }

            if (!IsAuthenticatedPairedDeviceRequest(context, pairedDevice, chunk))
            {
                return Results.Unauthorized();
            }

            if (chunkOffset != manifest.OffsetBytes || chunkOffset + chunk.LongLength > manifest.FileSizeBytes)
            {
                return Results.BadRequest("Chunk offset does not match the current transfer session offset.");
            }

            TransferChunkResponse response = await AppendChunkAsync(
                transferTempFolderPath,
                receiveFolderPath,
                pairedDevice,
                manifest,
                chunk,
                options.TransferProgressChanged,
                context.RequestAborted).ConfigureAwait(false);
            return Results.Json(response);
        });

        app.MapDelete("/nearshare/paired-devices/{deviceId:guid}/transfer-sessions/{sessionId:guid}", (HttpContext context, Guid deviceId, Guid sessionId) =>
        {
            PairedDeviceRecord? pairedDevice = pairedDeviceStore.FindByDeviceId(deviceId);
            if (pairedDevice is null)
            {
                return Results.NotFound();
            }

            if (!IsAuthenticatedPairedDeviceRequest(context, pairedDevice, body: []))
            {
                return Results.Unauthorized();
            }

            TransferSessionManifest? manifest = LoadTransferSession(transferTempFolderPath, sessionId);
            if (manifest is null || manifest.DeviceId != pairedDevice.DeviceId)
            {
                return Results.NotFound();
            }

            DeleteTransferSession(transferTempFolderPath, manifest);
            return Results.Json(new TransferCancelResponse
            {
                Status = "cancelled",
                SessionId = sessionId
            });
        });

        try
        {
            await app.StartAsync(cancellationToken).ConfigureAwait(false);
            return new LocalPairingServer(app, certificate, offer, pendingRequests, pendingRequestsLock);
        }
        catch
        {
            await app.DisposeAsync().ConfigureAwait(false);
            certificate.Dispose();
            throw;
        }
    }

    public async ValueTask DisposeAsync()
    {
        await _app.DisposeAsync().ConfigureAwait(false);
        _certificate.Dispose();
    }

    private static bool IsExpired(PairingPayload payload)
    {
        return DateTimeOffset.UtcNow.ToUnixTimeSeconds() > payload.ExpiresAtUnixTimeSeconds;
    }

    private static bool IsAuthenticatedPairedDeviceRequest(HttpContext context, PairedDeviceRecord pairedDevice, byte[] body)
    {
        string bodyHash = PairedDeviceRequestSignature.CreateBodyHash(body);
        return IsAuthenticatedPairedDeviceRequest(context, pairedDevice, bodyHash);
    }

    private static bool IsAuthenticatedPairedDeviceRequest(HttpContext context, PairedDeviceRecord pairedDevice, string bodySha256Base64Url)
    {
        if (!Guid.TryParse(context.Request.Headers["X-NearShare-Device-Id"].FirstOrDefault(), out Guid headerDeviceId)
            || headerDeviceId != pairedDevice.DeviceId)
        {
            return false;
        }

        if (!long.TryParse(
                context.Request.Headers["X-NearShare-Timestamp"].FirstOrDefault(),
                System.Globalization.NumberStyles.Integer,
                System.Globalization.CultureInfo.InvariantCulture,
                out long timestampUnixTimeSeconds))
        {
            return false;
        }

        string? nonce = context.Request.Headers["X-NearShare-Nonce"].FirstOrDefault();
        string? signature = context.Request.Headers["X-NearShare-Signature"].FirstOrDefault();
        if (string.IsNullOrWhiteSpace(nonce) || string.IsNullOrWhiteSpace(signature))
        {
            return false;
        }

        return PairedDeviceRequestSignature.VerifyBodyHash(
            sharedSecret: pairedDevice.SharedSecret,
            method: context.Request.Method,
            pathAndQuery: context.Request.Path + context.Request.QueryString,
            timestampUnixTimeSeconds: timestampUnixTimeSeconds,
            nonce: nonce,
            bodySha256Base64Url: bodySha256Base64Url,
            signature: signature,
            now: DateTimeOffset.UtcNow,
            allowedClockSkew: TimeSpan.FromMinutes(5));
    }

    private static async Task<byte[]> ReadRequestBodyAsync(Stream requestBody, CancellationToken cancellationToken)
    {
        using MemoryStream memoryStream = new();
        await requestBody.CopyToAsync(memoryStream, cancellationToken).ConfigureAwait(false);
        return memoryStream.ToArray();
    }

    private static TransferSessionManifest CreateOrResumeTransferSession(
        string transferTempFolderPath,
        Guid deviceId,
        TransferSessionCreateRequest request)
    {
        Directory.CreateDirectory(GetTransferSessionsFolder(transferTempFolderPath));
        TransferSessionManifest? existing = FindTransferSessionByClientSessionId(
            transferTempFolderPath,
            deviceId,
            request.ClientSessionId);
        if (existing is not null)
        {
            if (!string.Equals(existing.OriginalFileName, request.FileName, StringComparison.Ordinal)
                || existing.FileSizeBytes != request.FileSizeBytes
                || !string.Equals(existing.Sha256, request.Sha256, StringComparison.Ordinal)
                || existing.FileIndex != request.FileIndex
                || existing.TotalFiles != request.TotalFiles)
            {
                throw new InvalidOperationException("Client session ID already exists with different file metadata.");
            }

            return existing with
            {
                OffsetBytes = GetTransferSessionTempLength(transferTempFolderPath, existing.SessionId),
                UpdatedAt = DateTimeOffset.UtcNow
            };
        }

        TransferSessionManifest manifest = new(
            SessionId: Guid.NewGuid(),
            DeviceId: deviceId,
            ClientSessionId: request.ClientSessionId,
            OriginalFileName: request.FileName,
            FileSizeBytes: request.FileSizeBytes,
            Sha256: request.Sha256,
            ContentType: string.IsNullOrWhiteSpace(request.ContentType) ? "application/octet-stream" : request.ContentType,
            FileIndex: request.FileIndex,
            TotalFiles: request.TotalFiles,
            OffsetBytes: 0,
            CreatedAt: DateTimeOffset.UtcNow,
            UpdatedAt: DateTimeOffset.UtcNow);
        SaveTransferSession(transferTempFolderPath, manifest);
        return manifest;
    }

    private static TransferSessionStatusResponse CreateSessionStatusResponse(TransferSessionManifest manifest)
    {
        return new TransferSessionStatusResponse
        {
            Status = "ready",
            SessionId = manifest.SessionId,
            OffsetBytes = manifest.OffsetBytes,
            ChunkSizeBytes = ResumableChunkSizeBytes,
            FileSizeBytes = manifest.FileSizeBytes,
            OriginalFileName = manifest.OriginalFileName
        };
    }

    private static async Task<TransferChunkResponse> AppendChunkAsync(
        string transferTempFolderPath,
        string receiveFolderPath,
        PairedDeviceRecord pairedDevice,
        TransferSessionManifest manifest,
        byte[] chunk,
        Action<ReceiveTransferProgressUpdate>? transferProgressChanged,
        CancellationToken cancellationToken)
    {
        string tempFilePath = GetTransferSessionTempPath(transferTempFolderPath, manifest.SessionId);
        Directory.CreateDirectory(Path.GetDirectoryName(tempFilePath)!);
        await using (FileStream output = new(
                         tempFilePath,
                         FileMode.Append,
                         FileAccess.Write,
                         FileShare.None,
                         bufferSize: 1024 * 128,
                         useAsync: true))
        {
            await output.WriteAsync(chunk.AsMemory(0, chunk.Length), cancellationToken).ConfigureAwait(false);
        }

        long offsetBytes = manifest.OffsetBytes + chunk.LongLength;
        TransferSessionManifest updated = manifest with
        {
            OffsetBytes = offsetBytes,
            UpdatedAt = DateTimeOffset.UtcNow
        };
        SaveTransferSession(transferTempFolderPath, updated);

        if (offsetBytes < updated.FileSizeBytes)
        {
            transferProgressChanged?.Invoke(CreateReceiveTransferProgressUpdate(
                pairedDevice,
                updated,
                status: "in_progress"));

            return new TransferChunkResponse
            {
                Status = "in_progress",
                SessionId = updated.SessionId,
                OffsetBytes = offsetBytes,
                FileSizeBytes = updated.FileSizeBytes
            };
        }

        string actualSha256 = await ComputeFileSha256Async(tempFilePath, cancellationToken).ConfigureAwait(false);
        if (!string.Equals(actualSha256, updated.Sha256, StringComparison.Ordinal))
        {
            throw new InvalidOperationException("Completed transfer hash did not match the transfer session metadata.");
        }

        string savedFileName = await SaveCompletedTransferSessionFileAsync(
            receiveFolderPath,
            updated.OriginalFileName,
            tempFilePath,
            cancellationToken).ConfigureAwait(false);
        DeleteTransferSession(transferTempFolderPath, updated);
        transferProgressChanged?.Invoke(CreateReceiveTransferProgressUpdate(
            pairedDevice,
            updated,
            status: "completed"));

        return new TransferChunkResponse
        {
            Status = "completed",
            SessionId = updated.SessionId,
            OffsetBytes = offsetBytes,
            FileSizeBytes = updated.FileSizeBytes,
            SavedFileName = savedFileName,
            Sha256 = actualSha256
        };
    }

    private static ReceiveTransferProgressUpdate CreateReceiveTransferProgressUpdate(
        PairedDeviceRecord pairedDevice,
        TransferSessionManifest manifest,
        string status)
    {
        int percentComplete = manifest.FileSizeBytes == 0
            ? 100
            : (int)Math.Clamp((manifest.OffsetBytes * 100L) / manifest.FileSizeBytes, 0L, 100L);

        return new ReceiveTransferProgressUpdate
        {
            DeviceId = pairedDevice.DeviceId,
            DeviceName = pairedDevice.DeviceName,
            SessionId = manifest.SessionId,
            FileName = manifest.OriginalFileName,
            FileIndex = manifest.FileIndex,
            TotalFiles = manifest.TotalFiles,
            ReceivedBytes = manifest.OffsetBytes,
            TotalBytes = manifest.FileSizeBytes,
            PercentComplete = percentComplete,
            Status = status
        };
    }

    private static async Task<string> SaveCompletedTransferSessionFileAsync(
        string receiveFolderPath,
        string originalFileName,
        string tempFilePath,
        CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(receiveFolderPath);
        string savedFileName = GetCollisionSafeFileName(receiveFolderPath, SafeFileName.SanitizeForWindowsReceiveFolder(originalFileName));
        string destinationPath = Path.Combine(receiveFolderPath, savedFileName);
        await using FileStream source = new(tempFilePath, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 128, useAsync: true);
        await using FileStream destination = new(destinationPath, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1024 * 128, useAsync: true);
        await source.CopyToAsync(destination, cancellationToken).ConfigureAwait(false);
        return savedFileName;
    }

    private static async Task<string> ComputeFileSha256Async(string path, CancellationToken cancellationToken)
    {
        await using FileStream stream = new(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 128, useAsync: true);
        using SHA256 sha256 = SHA256.Create();
        byte[] hash = await sha256.ComputeHashAsync(stream, cancellationToken).ConfigureAwait(false);
        return PairedDeviceRequestSignature.EncodeBase64Url(hash);
    }

    private static TransferSessionManifest? FindTransferSessionByClientSessionId(
        string transferTempFolderPath,
        Guid deviceId,
        string clientSessionId)
    {
        string folder = GetTransferSessionsFolder(transferTempFolderPath);
        if (!Directory.Exists(folder))
        {
            return null;
        }

        foreach (string manifestPath in Directory.EnumerateFiles(folder, "*.json", SearchOption.TopDirectoryOnly))
        {
            TransferSessionManifest? manifest = LoadTransferSessionManifest(manifestPath);
            if (manifest is not null
                && manifest.DeviceId == deviceId
                && string.Equals(manifest.ClientSessionId, clientSessionId, StringComparison.Ordinal))
            {
                return manifest with
                {
                    OffsetBytes = GetTransferSessionTempLength(transferTempFolderPath, manifest.SessionId)
                };
            }
        }

        return null;
    }

    private static TransferSessionManifest? LoadTransferSession(string transferTempFolderPath, Guid sessionId)
    {
        return LoadTransferSessionManifest(GetTransferSessionManifestPath(transferTempFolderPath, sessionId));
    }

    private static TransferSessionManifest? LoadTransferSessionManifest(string manifestPath)
    {
        if (!File.Exists(manifestPath))
        {
            return null;
        }

        try
        {
            string json = File.ReadAllText(manifestPath);
            return System.Text.Json.JsonSerializer.Deserialize<TransferSessionManifest>(
                json,
                new System.Text.Json.JsonSerializerOptions(System.Text.Json.JsonSerializerDefaults.Web));
        }
        catch (IOException)
        {
            return null;
        }
        catch (System.Text.Json.JsonException)
        {
            return null;
        }
    }

    private static void SaveTransferSession(string transferTempFolderPath, TransferSessionManifest manifest)
    {
        string folder = GetTransferSessionsFolder(transferTempFolderPath);
        Directory.CreateDirectory(folder);
        string json = System.Text.Json.JsonSerializer.Serialize(
            manifest,
            new System.Text.Json.JsonSerializerOptions(System.Text.Json.JsonSerializerDefaults.Web));
        File.WriteAllText(GetTransferSessionManifestPath(transferTempFolderPath, manifest.SessionId), json);
    }

    private static void DeleteTransferSession(string transferTempFolderPath, TransferSessionManifest manifest)
    {
        DeleteFileIfExists(GetTransferSessionTempPath(transferTempFolderPath, manifest.SessionId));
        DeleteFileIfExists(GetTransferSessionManifestPath(transferTempFolderPath, manifest.SessionId));
    }

    private static long GetTransferSessionTempLength(string transferTempFolderPath, Guid sessionId)
    {
        string tempFilePath = GetTransferSessionTempPath(transferTempFolderPath, sessionId);
        return File.Exists(tempFilePath) ? new FileInfo(tempFilePath).Length : 0;
    }

    private static string GetTransferSessionsFolder(string transferTempFolderPath)
    {
        return Path.Combine(transferTempFolderPath, "sessions");
    }

    private static string GetTransferSessionManifestPath(string transferTempFolderPath, Guid sessionId)
    {
        return Path.Combine(GetTransferSessionsFolder(transferTempFolderPath), $"{sessionId:N}.json");
    }

    private static string GetTransferSessionTempPath(string transferTempFolderPath, Guid sessionId)
    {
        return Path.Combine(GetTransferSessionsFolder(transferTempFolderPath), $"{sessionId:N}.part");
    }

    private sealed record TransferSessionManifest(
        Guid SessionId,
        Guid DeviceId,
        string ClientSessionId,
        string OriginalFileName,
        long FileSizeBytes,
        string Sha256,
        string ContentType,
        int FileIndex,
        int TotalFiles,
        long OffsetBytes,
        DateTimeOffset CreatedAt,
        DateTimeOffset UpdatedAt);

    private static async Task<SpoolingResult> SpoolRequestBodyAsync(
        Stream requestBody,
        string transferTempFolderPath,
        CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(transferTempFolderPath);
        string tempFilePath = Path.Combine(transferTempFolderPath, $"{Guid.NewGuid():N}.upload");
        long sizeBytes = 0;
        await using FileStream output = new(
            tempFilePath,
            FileMode.CreateNew,
            FileAccess.Write,
            FileShare.None,
            bufferSize: 1024 * 128,
            useAsync: true);
        using SHA256 sha256 = SHA256.Create();
        byte[] buffer = new byte[1024 * 128];
        while (true)
        {
            int read = await requestBody.ReadAsync(buffer.AsMemory(0, buffer.Length), cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }

            await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
            sha256.TransformBlock(buffer, 0, read, null, 0);
            sizeBytes += read;
        }

        sha256.TransformFinalBlock([], 0, 0);
        return new SpoolingResult(tempFilePath, sizeBytes, PairedDeviceRequestSignature.EncodeBase64Url(sha256.Hash ?? []));
    }

    private static async Task<FileTransferReceiveResponse> SaveReceivedFileAsync(
        string receiveFolderPath,
        Guid deviceId,
        string originalFileName,
        SpoolingResult spooledFile,
        CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(receiveFolderPath);
        string savedFileName = GetCollisionSafeFileName(receiveFolderPath, SafeFileName.SanitizeForWindowsReceiveFolder(originalFileName));
        string destinationPath = Path.Combine(receiveFolderPath, savedFileName);
        await using (FileStream source = new(
                         spooledFile.TempFilePath,
                         FileMode.Open,
                         FileAccess.Read,
                         FileShare.Read,
                         bufferSize: 1024 * 128,
                         useAsync: true))
        await using (FileStream destination = new(
                         destinationPath,
                         FileMode.CreateNew,
                         FileAccess.Write,
                         FileShare.None,
                         bufferSize: 1024 * 128,
                         useAsync: true))
        {
            await source.CopyToAsync(destination, cancellationToken).ConfigureAwait(false);
        }

        DeleteFileIfExists(spooledFile.TempFilePath);

        return new FileTransferReceiveResponse
        {
            Status = "received",
            DeviceId = deviceId,
            OriginalFileName = originalFileName,
            SavedFileName = savedFileName,
            SizeBytes = spooledFile.SizeBytes,
            Sha256 = spooledFile.Sha256Base64Url
        };
    }

    private static void DeleteFileIfExists(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (IOException)
        {
            // Best-effort cleanup. A later temp-directory cleanup pass can remove stale files.
        }
        catch (UnauthorizedAccessException)
        {
            // Best-effort cleanup. Do not turn cleanup failures into transfer failures.
        }
    }

    private sealed record SpoolingResult(string TempFilePath, long SizeBytes, string Sha256Base64Url);

    private static string GetCollisionSafeFileName(string receiveFolderPath, string sanitizedFileName)
    {
        string candidate = sanitizedFileName;
        string extension = Path.GetExtension(sanitizedFileName);
        string baseName = Path.GetFileNameWithoutExtension(sanitizedFileName);
        for (int index = 1; File.Exists(Path.Combine(receiveFolderPath, candidate)); index++)
        {
            candidate = $"{baseName} ({index}){extension}";
        }

        return candidate;
    }

    private static PairingRequestResultResponse CreateRequestResultResponse(PendingPairingRequest request)
    {
        if (request.ApprovedDevice is not null)
        {
            return new PairingRequestResultResponse
            {
                RequestId = request.RequestId,
                Status = "approved",
                DeviceId = request.ApprovedDevice.DeviceId,
                DeviceName = request.ApprovedDevice.DeviceName,
                SharedSecret = request.ApprovedDevice.SharedSecret,
                Message = "Pairing was approved on Windows."
            };
        }

        return new PairingRequestResultResponse
        {
            RequestId = request.RequestId,
            Status = request.Status,
            DeviceName = request.DeviceName,
            Message = request.Status switch
            {
                "rejected" => "Pairing was rejected on Windows.",
                _ => "Pairing request is waiting for confirmation on Windows."
            }
        };
    }

    private static string GenerateSharedSecret()
    {
        byte[] bytes = RandomNumberGenerator.GetBytes(32);
        return Convert.ToBase64String(bytes)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');
    }

    private static int ReserveAvailableTcpPort(IPAddress listenAddress)
    {
        using System.Net.Sockets.TcpListener listener = new(listenAddress, 0);
        listener.Start();
        return ((IPEndPoint)listener.LocalEndpoint).Port;
    }

    private static string GetQrHostFromListenAddress(IPAddress listenAddress)
    {
        if (IPAddress.Any.Equals(listenAddress) || IPAddress.IPv6Any.Equals(listenAddress))
        {
            return "127.0.0.1";
        }

        return listenAddress.ToString();
    }
}
