# SMB Library

SMB Library is a Mihon/Tachiyomi-compatible source extension that reads manga directly from an SMB2/SMB3 share.

## Architecture

- `SmbLibrary.kt`: source entry point, preferences, Mihon model mapping.
- `SmbRepository.kt`: SMB connection, directory enumeration, metadata and remote streams.
- `ContentDetector.kt`: image/archive detection and ZIP entry filtering.
- `ArchiveCache.kt`: sequential ZIP/CBZ download, fingerprint validation, local reading and LRU cleanup.
- `CoverProvider.kt`: read-only Android content provider for the bundled placeholder image.
- `PathCodec.kt`: reversible internal identifiers without credentials.
- `PageResponseFactory.kt`: OkHttp responses backed by SMB or local ZIP streams.

## Supported Formats

- Image folders: `.jpg`, `.jpeg`, `.png`, `.webp`, `.gif`
- Archives: `.zip`, `.cbz`

RAR/CBR, PDF, EPUB, 7z, nested archives, metadata scraping and cover generation are intentionally out of scope for the first phase.

## Mapping Rules

- Direct folders under the configured SMB root are listed as manga.
- Loose `.zip`, `.cbz`, and `.pdf` files in the SMB root are grouped into a virtual manga named `others`; its modification time is the newest matching file.
- Folders without supported manga content remain visible and open with an empty chapter list.
- Manga can be sorted by natural name or SMB last-modified time, in ascending or descending order. The default is last modified descending (newest first).
- Direct image subfolders under a manga folder become chapters.
- `.zip` and `.cbz` files directly under a manga folder become chapters.
- Root-level ZIP/CBZ files become chapters inside `others`. Root-level PDFs affect the virtual manga timestamp but remain hidden until PDF rendering is implemented.
- Images directly under a manga folder become one virtual chapter named `本卷`.
- Non-image files and unsupported archive formats are ignored.

## Covers

Every manga uses the same bundled SMB Library placeholder cover. A fixed, versioned `content://` URI serves the PNG through Android's `ContentResolver`, avoiding network access and unsupported cross-package `android.resource://` lookups. Browsing performs only the root directory listing and does not open child folders, images, or archives to derive thumbnails.

## Build

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew src:all:smblibrary:assembleDebug
```

## ZIP/CBZ Cache

The first open downloads the complete ZIP/CBZ to the app's private cache using one sequential SMB stream. Page enumeration waits for that transfer, so the reader never stops after a few pages to perform another SMB random read. Unchanged archives are reused by a fingerprint of SMB location, relative path, size, and modification time.

Downloads use temporary files, storage sync, atomic replacement, and one striped lock per archive key. LRU cleanup defaults to 2048 MiB and can be set from 128 to 32768 MiB with `Archive cache size (MiB)`. Archives larger than the configured limit remain usable; older cache entries are removed first.

## SMB Settings

- Host
- Port, default `445`
- Share
- Root path
- Username
- Password
- Domain, optional
- Connection timeout
- Archive cache size in MiB, default `2048`

Credentials are read only from Android preferences. Passwords are password-input fields and are never logged or encoded into manga/chapter/page URLs.

## Security Notes

Internal IDs encode only relative paths and fingerprints. Decoded paths reject absolute paths, empty path elements and `..` traversal. The exported cover provider serves only one bundled PNG and rejects writes and unknown paths. The extension does not write to the NAS.

## Future Work

PDF, EPUB and RAR/CBR support should be added behind `ContentDetector` and page-provider style helpers. ZIP cache behavior remains isolated in `ArchiveCache`.

The extension uses library version `1.4` to retain compatibility with Tachiyomi forks that do not support the `1.6` source API.
