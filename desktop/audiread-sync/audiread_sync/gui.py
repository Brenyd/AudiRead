"""Small desktop window for building sync files."""

from __future__ import annotations

import os
import queue
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from .sync import AUDIO_EXT, build, collect_audio, default_output

MODELS = [
    ("Fast — English (recommended)", "base.en"),
    ("Fastest — English", "tiny.en"),
    ("More accurate — English (slower)", "small.en"),
    ("Other languages", "small"),
]


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        root.title("AudiRead Sync")
        root.minsize(620, 560)
        self.q: queue.Queue = queue.Queue()
        self.worker: threading.Thread | None = None
        self.stop = False
        self.audio: list[str] = []

        pad = {"padx": 12, "pady": 4}
        frm = ttk.Frame(root, padding=8)
        frm.pack(fill="both", expand=True)
        frm.columnconfigure(1, weight=1)

        ttk.Label(frm, text="Line up an audiobook with its ebook so AudiRead turns the pages at exactly the right time.",
                  wraplength=580).grid(row=0, column=0, columnspan=3, sticky="w", **pad)

        ttk.Label(frm, text="Ebook").grid(row=1, column=0, sticky="w", **pad)
        self.ebook = tk.StringVar()
        ttk.Entry(frm, textvariable=self.ebook).grid(row=1, column=1, sticky="ew", **pad)
        ttk.Button(frm, text="Choose…", command=self.pick_ebook).grid(row=1, column=2, **pad)

        ttk.Label(frm, text="Audiobook").grid(row=2, column=0, sticky="nw", **pad)
        box = ttk.Frame(frm)
        box.grid(row=2, column=1, sticky="nsew", **pad)
        box.columnconfigure(0, weight=1)
        box.rowconfigure(0, weight=1)
        self.list = tk.Listbox(box, height=8, activestyle="none")
        self.list.grid(row=0, column=0, sticky="nsew")
        sb = ttk.Scrollbar(box, orient="vertical", command=self.list.yview)
        sb.grid(row=0, column=1, sticky="ns")
        self.list.configure(yscrollcommand=sb.set)
        btns = ttk.Frame(frm)
        btns.grid(row=2, column=2, sticky="n", **pad)
        ttk.Button(btns, text="Add folder…", command=self.pick_folder).pack(fill="x", pady=2)
        ttk.Button(btns, text="Add files…", command=self.pick_files).pack(fill="x", pady=2)
        ttk.Button(btns, text="Clear", command=self.clear_audio).pack(fill="x", pady=2)
        frm.rowconfigure(2, weight=1)

        ttk.Label(frm, text="Accuracy").grid(row=3, column=0, sticky="w", **pad)
        self.model = tk.StringVar(value=MODELS[0][0])
        ttk.Combobox(frm, textvariable=self.model, values=[m[0] for m in MODELS], state="readonly").grid(
            row=3, column=1, sticky="w", **pad)

        ttk.Label(frm, text="Save to").grid(row=4, column=0, sticky="w", **pad)
        self.out = tk.StringVar()
        ttk.Entry(frm, textvariable=self.out).grid(row=4, column=1, sticky="ew", **pad)
        ttk.Button(frm, text="Change…", command=self.pick_out).grid(row=4, column=2, **pad)

        self.start_btn = ttk.Button(frm, text="Create sync file", command=self.start)
        self.start_btn.grid(row=5, column=1, sticky="w", **pad)
        self.cancel_btn = ttk.Button(frm, text="Cancel", command=self.cancel, state="disabled")
        self.cancel_btn.grid(row=5, column=2, **pad)

        self.status = tk.StringVar(value="")
        ttk.Label(frm, textvariable=self.status).grid(row=6, column=0, columnspan=3, sticky="w", **pad)
        self.bar = ttk.Progressbar(frm, maximum=1000)
        self.bar.grid(row=7, column=0, columnspan=3, sticky="ew", **pad)

        self.log_box = tk.Text(frm, height=9, state="disabled", wrap="word")
        self.log_box.grid(row=8, column=0, columnspan=3, sticky="nsew", **pad)
        frm.rowconfigure(8, weight=1)

        root.after(100, self.poll)

    # ------------------------------------------------------------ pickers

    def pick_ebook(self):
        p = filedialog.askopenfilename(title="Choose the ebook", filetypes=[
            ("Ebooks", "*.epub *.kepub *.mobi *.azw3 *.azw *.prc *.fb2 *.zip *.txt *.md *.html *.htm *.xhtml"),
            ("All files", "*")])
        if p:
            self.ebook.set(p)
            if not self.out.get():
                self.out.set(default_output(p))

    def pick_folder(self):
        d = filedialog.askdirectory(title="Choose the audiobook folder")
        if d:
            self.add_audio(collect_audio([d]))

    def pick_files(self):
        ps = filedialog.askopenfilenames(title="Choose audiobook files",
                                         filetypes=[("Audio", " ".join("*" + e for e in AUDIO_EXT)), ("All files", "*")])
        if ps:
            self.add_audio(collect_audio(list(ps)))

    def add_audio(self, files):
        for f in files:
            if f not in self.audio:
                self.audio.append(f)
        self.list.delete(0, "end")
        for f in self.audio:
            self.list.insert("end", os.path.basename(f))

    def clear_audio(self):
        self.audio = []
        self.list.delete(0, "end")

    def pick_out(self):
        p = filedialog.asksaveasfilename(title="Save sync file", defaultextension=".json",
                                         initialfile=os.path.basename(self.out.get() or "book.audiread-sync.json"),
                                         filetypes=[("AudiRead sync", "*.json")])
        if p:
            self.out.set(p)

    # ------------------------------------------------------------ running

    def log(self, msg):
        self.log_box.configure(state="normal")
        self.log_box.insert("end", msg + "\n")
        self.log_box.see("end")
        self.log_box.configure(state="disabled")

    def start(self):
        ebook = self.ebook.get().strip()
        if not ebook or not os.path.exists(ebook):
            messagebox.showerror("AudiRead Sync", "Choose the ebook first.")
            return
        if not self.audio:
            messagebox.showerror("AudiRead Sync", "Add the audiobook files or folder.")
            return
        out = self.out.get().strip() or default_output(ebook)
        self.out.set(out)
        model = dict(MODELS)[self.model.get()]
        self.stop = False
        self.start_btn.configure(state="disabled")
        self.cancel_btn.configure(state="normal")
        self.bar["value"] = 0
        self.status.set("Starting…")

        def work():
            try:
                r = build(ebook, list(self.audio), out, model=model,
                          log=lambda m: self.q.put(("log", m)),
                          progress=lambda s, f: self.q.put(("progress", (s, f))),
                          should_stop=lambda: self.stop)
                self.q.put(("done", r))
            except InterruptedError:
                self.q.put(("cancelled", None))
            except Exception as e:  # noqa: BLE001
                self.q.put(("error", str(e) or e.__class__.__name__))

        self.worker = threading.Thread(target=work, daemon=True)
        self.worker.start()

    def cancel(self):
        self.stop = True
        self.status.set("Stopping…")

    def poll(self):
        try:
            while True:
                kind, val = self.q.get_nowait()
                if kind == "log":
                    self.log(val)
                elif kind == "progress":
                    stage, f = val
                    # listening is ~95% of the work
                    total = f * 0.95 if stage == "transcribe" else 0.95 + f * 0.05
                    self.bar["value"] = int(total * 1000)
                    self.status.set(("Listening to the audiobook" if stage == "transcribe" else "Matching to the text")
                                    + f"… {int(f * 100)}%")
                elif kind == "done":
                    self.finish()
                    self.bar["value"] = 1000
                    self.status.set(f"Done — {val.anchors:,} sync points, {val.coverage:.0%} of the book covered.")
                    msg = (f"Saved:\n{val.path}\n\nCopy this file to your phone, open the book in AudiRead, "
                           "then ⋮ → Import sync file.")
                    if val.warnings:
                        msg += "\n\n" + "\n".join(val.warnings)
                    messagebox.showinfo("AudiRead Sync", msg)
                elif kind == "cancelled":
                    self.finish()
                    self.status.set("Cancelled. Finished files are remembered, so starting again resumes quickly.")
                elif kind == "error":
                    self.finish()
                    self.status.set("Failed")
                    self.log("Error: " + val)
                    messagebox.showerror("AudiRead Sync", val)
        except queue.Empty:
            pass
        self.root.after(100, self.poll)

    def finish(self):
        self.start_btn.configure(state="normal")
        self.cancel_btn.configure(state="disabled")


def run():
    root = tk.Tk()
    try:
        ttk.Style().theme_use("clam")
    except tk.TclError:
        pass
    App(root)
    root.mainloop()
