# SMB Library

SMB Library is a Mihon/Tachiyomi-compatible source extension that reads manga directly from an SMB2/SMB3 share.

## Architecture

- `SmbLibrary.kt`: source entry point, preferences, Mihon model mapping.
- `SmbRepository.kt`: SMB connection, directory enumeration, metadata and remote streams.
- `ContentDetector.kt`: image/archive detection and ZIP entry filtering.
- `ArchiveCache.kt`: private-cache ZIP/CBZ download, fingerprinting and cleanup.
- `PathCodec.kt`: reversible internal identifiers without credentials.
- `PageResponseFactory.kt`: OkHttp responses backed by SMB or cached archive streams.

## Supported Formats

- Image folders: `.jpg`, `.jpeg`, `.png`, `.webp`, `.gif`
- Archives: `.zip`, `.cbz`

RAR/CBR, PDF, EPUB, 7z, nested archives, metadata scraping and cover generation are intentionally out of scope for the first phase.

## Mapping Rules

- Direct folders under the configured SMB root are listed as manga.
- Manga can be sorted by natural name or SMB last-modified time, in ascending or descending order. The default is last modified descending (newest first).
- Direct image subfolders under a manga folder become chapters.
- `.zip` and `.cbz` files directly under a manga folder become chapters.
- Images directly under a manga folder become one virtual chapter named `本卷`.
- Non-image files and unsupported archive formats are ignored.

## Covers

Covers are resolved locally without sending manga titles to an online metadata service. The source uses the first naturally sorted image in the manga root, then the first image from a naturally sorted direct child folder, then the first readable image entry from the first ZIP/CBZ. Archive covers are streamed from SMB and do not require downloading the complete archive. Errors fall back to the built-in SMB Library placeholder.

## Build

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew src:all:smblibrary:assembleDebug
```

## Cache Strategy

ZIP/CBZ archives are cached in the extension private cache directory under `smb-library/archives`.
The cache key includes the remote relative path, size and last-modified time. Downloads use a temporary file, verify the remote fingerprint again, and then atomically rename the result. A simple LRU cleanup targets 512 MiB while preserving the archive currently being opened; one archive larger than the limit is allowed to remain readable.

## SMB Settings

- Host
- Port, default `445`
- Share
- Root path
- Username
- Password
- Domain, optional
- Connection timeout

Credentials are read only from Android preferences. Passwords are password-input fields and are never logged or encoded into manga/chapter/page URLs.

## Security Notes

Internal IDs encode only relative paths and fingerprints. Decoded paths reject absolute paths, empty path elements and `..` traversal. The extension does not write to the NAS.

## Future Work

PDF, EPUB and RAR/CBR support should be added behind `ContentDetector` and page-provider style helpers, with archive/cache behavior kept in `ArchiveCache`.
