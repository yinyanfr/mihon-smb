#!/usr/bin/env bash
set -euo pipefail

root="${1:-SMB-Test}"
png_base64="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
marker=".smb-library-test-data"

case "/$root/" in
  "//"|"/./"|"/../"|/*/../*|/*/./*)
    printf 'Refusing unsafe output path: %s\n' "$root" >&2
    exit 1
    ;;
esac

if [[ "$root" = /* ]]; then
  printf 'Use a relative output path, not an absolute path: %s\n' "$root" >&2
  exit 1
fi

if [[ -e "$root" && ! -f "$root/$marker" ]]; then
  printf 'Refusing to replace unmarked directory: %s\n' "$root" >&2
  exit 1
fi

make_png() {
  mkdir -p "$(dirname "$1")"
  printf '%s' "$png_base64" | base64 --decode > "$1"
}

mkdir -p "$root"
touch "$root/$marker"
rm -rf -- \
  "$root/Folder With Direct Images" \
  "$root/Folder With Chapter Directories" \
  "$root/Folder With Archives" \
  "$root/Mixed Non Manga Folder" \
  "$root/Special Characters 漫画 [作者] #1"

make_png "$root/Folder With Direct Images/1.jpg"
make_png "$root/Folder With Direct Images/2.jpg"
make_png "$root/Folder With Direct Images/10.jpg"

make_png "$root/Folder With Chapter Directories/第1话/001.jpg"
make_png "$root/Folder With Chapter Directories/第1话/002.jpg"
make_png "$root/Folder With Chapter Directories/第10话/001.jpg"
make_png "$root/Folder With Chapter Directories/第10话/002.jpg"

mkdir -p "$root/Folder With Archives/chapter 1"
make_png "$root/Folder With Archives/chapter 1/001.png"
make_png "$root/Folder With Archives/chapter 1/002.png"
(cd "$root/Folder With Archives/chapter 1" && zip -q "../chapter 1.cbz" 001.png 002.png)

mkdir -p "$root/Folder With Archives/chapter 10"
make_png "$root/Folder With Archives/chapter 10/001.png"
make_png "$root/Folder With Archives/chapter 10/002.png"
(cd "$root/Folder With Archives/chapter 10" && zip -q "../chapter 10.zip" 001.png 002.png)

mkdir -p "$root/Mixed Non Manga Folder"
printf 'notes\n' > "$root/Mixed Non Manga Folder/notes.txt"
printf 'not a video\n' > "$root/Mixed Non Manga Folder/video.mp4"

mkdir -p "$root/Special Characters 漫画 [作者] #1"
make_png "$root/Special Characters 漫画 [作者] #1/001.png"

rm -rf -- "$root/Folder With Archives/chapter 1" "$root/Folder With Archives/chapter 10"
printf 'Created %s\n' "$root"
