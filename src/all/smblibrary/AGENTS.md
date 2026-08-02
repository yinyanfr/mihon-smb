# SMB Library Agent Notes

- Limit code changes to `src/all/smblibrary` plus local development build configuration needed to load this module.
- Build command: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew src:all:smblibrary:assembleDebug`.
- MVP scope is SMB2/SMB3 directory listing, image-folder reading, ZIP/CBZ cache reading, preferences and pure logic tests.
- Do not add server-side components, WebDAV, Komga, Kavita, SMB1, NAS discovery or write operations.
- Do not store or log NAS hostnames, passwords, usernames, tokens, NTLM hashes or real test accounts.
