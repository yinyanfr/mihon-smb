# Mihon SMB Library

<p align="center">
  <img src="src/all/smblibrary/artwork/smb-library-logo.png" alt="SMB Library logo" width="220">
</p>

<p align="center">
  <a href="https://github.com/yinyanfr/mihon-smb/actions/workflows/smblibrary_release.yml"><img src="https://github.com/yinyanfr/mihon-smb/actions/workflows/smblibrary_release.yml/badge.svg" alt="Release SMB Library"></a>
  <a href="https://github.com/yinyanfr/mihon-smb/releases/latest"><img src="https://img.shields.io/github/v/release/yinyanfr/mihon-smb?filter=smblibrary-v*&label=release" alt="Latest release"></a>
</p>

SMB Library is a Mihon/Tachiyomi-compatible source extension that reads a manga library directly from an SMB2/SMB3 share. It does not require Komga, Kavita, WebDAV, a custom HTTP service, or any server-side component on the NAS.

The extension lives in [`src/all/smblibrary`](src/all/smblibrary).

## Features

- Connects to SMB2/SMB3 shares with host, port, share, root path, username, password, and optional domain settings.
- Lists every direct child folder under the configured root as a manga.
- Detects direct image folders, `.zip` files, `.cbz` files, and images stored directly in a manga folder as chapters.
- Reads folder images as SMB streams without loading a complete chapter into memory.
- Downloads ZIP/CBZ archives to the extension's private cache for random-access reading.
- Uses local first-page images as covers, including streaming the first readable ZIP/CBZ image without downloading the complete archive.
- Supports natural sorting and manga sorting by name or SMB modification time in either direction. The default is newest first.
- Keeps SMB credentials in Android preferences and never places them in manga, chapter, page, or thumbnail URLs.

Supported images: `.jpg`, `.jpeg`, `.png`, `.webp`, `.gif`.

Supported archives: `.zip`, `.cbz`.

## Library Layout

Each direct folder under the configured root appears as one manga:

```text
Manga Root/
├── Manga With Direct Images/
│   ├── 1.jpg
│   ├── 2.jpg
│   └── 10.jpg
├── Manga With Folders/
│   ├── Chapter 1/
│   │   ├── 001.jpg
│   │   └── 002.jpg
│   └── Chapter 10/
│       ├── 001.jpg
│       └── 002.jpg
└── Manga With Archives/
    ├── Chapter 1.cbz
    └── Chapter 10.zip
```

Images directly inside a manga folder are grouped into one virtual chapter named `本卷`. Unsupported files are ignored.

## SMB Settings

For a remote path such as `//nas/shared/dl`:

| Setting | Value |
| --- | --- |
| Host | `nas` |
| Port | `445` |
| Share | `shared` |
| Root path | `dl` |

The root path is relative to the selected share and may be empty. Do not include `smb://` in the host field. Empty username and password values can be used with NAS shares that explicitly allow anonymous access.

## Archive Cache

ZIP/CBZ files are cached under the extension's private Android cache directory. Cache keys include the SMB namespace, relative path, file size, and modification time. Downloads use temporary files, verify the remote fingerprint, and are renamed only after completion.

The cache targets a 512 MiB LRU limit. The archive currently being opened is protected from cleanup, and one archive larger than the limit is allowed to remain readable.

## Build

This repository retains the Keiyoushi extension build infrastructure and history. Android Studio's bundled JBR is recommended on macOS:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew src:all:smblibrary:testDebugUnitTest
./gradlew src:all:smblibrary:spotlessCheck
./gradlew src:all:smblibrary:assembleDebug
```

Debug APKs are written under:

```text
src/all/smblibrary/build/outputs/apk/debug/
```

## Automated Releases

Every push to `main` runs [Release SMB Library](.github/workflows/smblibrary_release.yml). The workflow increments the extension version code, runs unit tests and formatting checks, builds a signed release APK, commits the version increment, and publishes a GitHub Release with a SHA-256 checksum and recent SMB Library changes.

Release signing uses the `SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, and `KEY_PASSWORD` GitHub Actions secrets. Signing material is never stored in Git.

The helper script below creates tiny, copyright-free SMB test fixtures:

```bash
src/all/smblibrary/tools/create-smb-test-data.sh
```

## Security

- SMB credentials come only from the extension's Android preferences.
- Password input uses Android's password field type.
- Credentials are not encoded into Mihon URLs or written to project logs.
- Internal paths are relative, reversible, and checked for traversal.
- ZIP entries are filtered without extracting archives into arbitrary directories.
- The extension is read-only and does not modify NAS content.
- Local signing keys, signing environment files, SDK paths, Gradle caches, APKs, and build outputs are ignored by Git.

## Current Limitations

RAR/CBR, PDF, EPUB, 7z, nested archives, online metadata scraping, arbitrary-depth browsing, NAS discovery, SMB1, and NAS write operations are not supported.

## Upstream And License

This project is based on the [Keiyoushi extensions-source](https://github.com/keiyoushi/extensions-source) build system and preserves its Git history. SMB access is provided by [SMBJ](https://github.com/hierynomus/smbj).

The repository is licensed under the [Apache License 2.0](LICENSE). This project is not affiliated with Mihon, Tachiyomi, Keiyoushi, or any content provider.
