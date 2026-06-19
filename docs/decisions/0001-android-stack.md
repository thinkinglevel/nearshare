# 0001 - Android Stack

Status: Accepted
Date: 2026-06-09

## Context

NearShare needs an Android application for nearby local-link file sharing with Windows PCs. The Android app is expected to provide:

- Android share-sheet entry point for sending one or more files to a paired PC;
- paired PC/device selector, including the top send-screen dropdown;
- QR scanner and manual pairing entry;
- send progress notification;
- manual receive mode;
- optional always-on receive foreground service;
- receive folder/settings behavior that follows Android storage rules.

The product must remain lightweight, local-link first, and avoid cloud accounts, telemetry, unnecessary background behavior, and invasive permissions.

## Options Considered

### Native Kotlin / Android SDK

Pros:

- best fit for share sheet, content URIs, foreground services, notifications, Wi-Fi/network APIs, storage access framework, and Android policy changes;
- lowest framework overhead;
- most direct control over background execution and receive-mode UX;
- mature testing, Gradle, and Android Studio ecosystem.

Cons:

- Android-only codebase;
- UI and app architecture must be maintained separately from Windows.

### Flutter

Pros:

- productive UI toolkit;
- potential for shared UI patterns across platforms.

Cons:

- platform-channel work still required for share sheet, foreground service behavior, storage, Wi-Fi, and notifications;
- extra runtime/framework weight for a local utility app;
- less direct for Android policy-sensitive behavior.

### React Native

Pros:

- JavaScript ecosystem and fast UI iteration.

Cons:

- native-module work still required for core Android integrations;
- more dependency/runtime surface than needed;
- weaker fit for long-term background/foreground-service reliability.

## Decision

Use native Kotlin / Android SDK for the Android implementation.

The Android implementation should prioritize platform-native APIs for:

- share-target Activity integration;
- content URI streaming;
- foreground service and notification behavior;
- QR scanning/manual pairing;
- storage access and receive-location behavior;
- local network reachability handling.

## Consequences

- Android app code lives under `apps/android/`.
- The Android app and Windows app will have separate native UI implementations.
- Any shared protocol contract should live under `shared/protocol/` as docs, schemas, fixtures, and test vectors before introducing shared runtime code.
- Always-on receive remains opt-in and visible through a foreground notification.
- Platform-specific Android behavior should not be hidden behind a cross-platform abstraction too early.

## Follow-up Work

- Completed for 1.0: Android app implementation exists under `apps/android/`.
- Completed for 1.0: pairing crypto and transfer protocol are documented and implemented through local HTTPS, pinned receiver identity, and HMAC-signed requests.
- Completed for 1.0: Android receive storage and receive-mode policies exist for manual receive and always-on receive.
- Continue keeping Android and Windows UI implementations independent while aligning behavior through protocol docs and tests.
