# AudiRead Sync (desktop)

Lines up an audiobook with its ebook so AudiRead turns pages at exactly the moment the narrator gets there. Everything runs on your own computer, with no uploads.

**How it works:** the app listens to the audiobook with Whisper speech recognition (via [faster-whisper](https://github.com/SYSTRAN/faster-whisper)), then matches what it heard against the ebook's text. It works the way a diff tool lines up two versions of a file, so recognition mistakes, intros, credits and skipped passages don't throw it off. The result is a small `.audiread-sync.json` file with a sync point every few seconds.

## Install (Linux)

```bash
cd desktop/audiread-sync
./install.sh
```

This creates a private Python environment, an `audiread-sync` command and an **AudiRead Sync** entry in your app menu. The window needs Tk (`sudo pacman -S tk` on Arch/CachyOS). If [uv](https://github.com/astral-sh/uv) is installed, it's used to get a compatible Python automatically.

The first run downloads the speech model (about 150 MB for the default).

## Use

1. Open **AudiRead Sync**.
2. Choose the ebook (EPUB, MOBI/AZW3, FB2, TXT or HTML) and add the audiobook folder or files.
3. Press **Create sync file**. The file is saved next to the ebook.
4. Copy `Book.audiread-sync.json` to your phone (KDE Connect "Send file" works well).
5. In AudiRead, open the book and choose **⋮ → Import sync file**. You can also do this from the ⋮ menu while reading.

Command line:

```bash
audiread-sync --ebook "Book.epub" --audio "Audiobook folder/" [--model small.en] [--out file.json]
```

## Speed

Speech recognition runs on the CPU, which works with any graphics card, including AMD. With the default model, a modern desktop CPU processes roughly an hour of audio in a few minutes. Finished files are cached, so if you cancel and start again, it picks up where it stopped. For non-English books, choose **Other languages**.

## Tips

- Use the same ebook file you imported into AudiRead. The sync file stores short word snippets that AudiRead looks up in its own copy of the text.
- Abridged audiobooks still sync; the skipped parts are bridged over.
- Re-importing a sync file replaces the previous one. Pins you add later by hand still take priority.
