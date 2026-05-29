import json
import os
import queue
import shutil
import socket
import sys
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

try:
    from tkinterdnd2 import DND_FILES, TkinterDnD
except ImportError:
    DND_FILES = None
    TkinterDnD = None


APP_DIR = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
RESOURCE_DIR = Path(getattr(sys, "_MEIPASS", APP_DIR))
DEFAULT_PORT = 9021
SOCKET_TIMEOUT_SECONDS = 15
APP_NAME = "Blurfer"
SETTINGS_FILE_NAME = "settings.json"


def resource_path(*parts):
    return RESOURCE_DIR.joinpath(*parts)


def get_config_dir():
    if sys.platform.startswith("win"):
        base = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
        return base / APP_NAME

    base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return base / "blurfer"


DEFAULT_PAYLOAD_DIR = ""
CONFIG_PATH = get_config_dir() / SETTINGS_FILE_NAME
APP_WINDOW_BASE = TkinterDnD.Tk if TkinterDnD else tk.Tk


def send_payload(file_path, host, port=DEFAULT_PORT):
    path = Path(file_path)
    data = path.read_bytes()

    with socket.create_connection((host, port), timeout=SOCKET_TIMEOUT_SECONDS) as sock:
        sock.sendall(data)

    return len(data)


def format_size(num_bytes):
    units = ("B", "KB", "MB", "GB")
    size = float(num_bytes)
    for unit in units:
        if size < 1024 or unit == units[-1]:
            if unit == "B":
                return f"{int(size)} {unit}"
            return f"{size:.1f} {unit}"
        size /= 1024
    return f"{num_bytes} B"


class BlurferApp(APP_WINDOW_BASE):
    def __init__(self):
        super().__init__()

        self.title("Blurfer")
        self._set_window_icon()
        self.minsize(1080, 620)
        self.geometry("1180x700")

        self.settings = self._load_settings()

        self.payload_dir = tk.StringVar(value=self.settings.get("payload_dir", DEFAULT_PAYLOAD_DIR))
        self.host = tk.StringVar(value=self.settings.get("host", ""))
        self.port = tk.StringVar(value=self.settings.get("port", str(DEFAULT_PORT)))
        self.selected_port = tk.StringVar(value=self.settings.get("port", str(DEFAULT_PORT)))
        self.selected_delay = tk.StringVar(value="0")
        self.status = tk.StringVar(value="Ready")
        self.payload_count = tk.StringVar(value="0 payloads")

        self.payload_files = []
        self.worker = None
        self.stop_event = threading.Event()
        self.events = queue.Queue()

        self._configure_style()
        self._build_ui()
        self._setup_drag_and_drop()
        self.refresh_payloads()
        self.after(100, self._process_events)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    def _configure_style(self):
        style = ttk.Style(self)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass

        self.colors = {
            "bg": "#f5f7fb",
            "panel": "#ffffff",
            "text": "#172033",
            "muted": "#687386",
            "accent": "#2563eb",
            "accent_dark": "#1d4ed8",
            "accent_soft": "#eef4ff",
            "border": "#d9dee8",
            "row_alt": "#f8fafc",
            "success": "#15803d",
            "error": "#b42318",
        }

        self.configure(bg=self.colors["bg"])
        style.configure(".", font=("Segoe UI", 10))
        style.configure("TFrame", background=self.colors["bg"])
        style.configure("Panel.TFrame", background=self.colors["panel"], relief="flat")
        style.configure("Soft.TFrame", background=self.colors["accent_soft"])
        style.configure("TLabel", background=self.colors["bg"], foreground=self.colors["text"])
        style.configure("Panel.TLabel", background=self.colors["panel"], foreground=self.colors["text"])
        style.configure("Muted.TLabel", background=self.colors["bg"], foreground=self.colors["muted"])
        style.configure("PanelMuted.TLabel", background=self.colors["panel"], foreground=self.colors["muted"])
        style.configure("Count.TLabel", background=self.colors["panel"], foreground=self.colors["muted"], font=("Segoe UI", 9))
        style.configure("Header.TLabel", background=self.colors["bg"], foreground=self.colors["text"], font=("Segoe UI", 20, "bold"))
        style.configure("Section.TLabel", background=self.colors["panel"], foreground=self.colors["text"], font=("Segoe UI", 12, "bold"))
        style.configure("TButton", padding=(10, 6))
        style.configure("Small.TButton", padding=(8, 5))
        style.configure("Accent.TButton", padding=(12, 7))
        style.map(
            "Accent.TButton",
            foreground=[("disabled", "#a9b2c2"), ("!disabled", "#ffffff")],
            background=[("active", self.colors["accent_dark"]), ("!disabled", self.colors["accent"])],
        )
        style.configure("TEntry", padding=(6, 4))
        style.configure("TSpinbox", padding=(6, 4))
        style.configure(
            "Treeview",
            rowheight=28,
            fieldbackground=self.colors["panel"],
            background=self.colors["panel"],
            borderwidth=0,
            relief="flat",
        )
        style.configure("Treeview.Heading", font=("Segoe UI", 9, "bold"), padding=(6, 6))

    def _set_window_icon(self):
        ico_path = resource_path("assets", "blurfer.ico")
        png_path = resource_path("assets", "blurfer_icon_256.png")

        try:
            if ico_path.exists():
                self.iconbitmap(str(ico_path))
            elif png_path.exists():
                self._window_icon = tk.PhotoImage(file=str(png_path))
                self.iconphoto(True, self._window_icon)
        except tk.TclError:
            pass

    def _load_settings(self):
        try:
            with CONFIG_PATH.open("r", encoding="utf-8") as settings_file:
                settings = json.load(settings_file)
        except (OSError, json.JSONDecodeError):
            return {}

        return settings if isinstance(settings, dict) else {}

    def _save_settings(self):
        payload_orders = self.settings.get("payload_orders", {})
        if not isinstance(payload_orders, dict):
            payload_orders = {}

        payload_dir_path = self._payload_dir_path()
        if hasattr(self, "tree") and payload_dir_path is not None:
            folder_key = str(payload_dir_path)
            payload_orders[folder_key] = [path.name for path in self._ordered_payload_files()]

        payload_delays = self.settings.get("payload_delays", {})
        if not isinstance(payload_delays, dict):
            payload_delays = {}

        if hasattr(self, "tree") and payload_dir_path is not None:
            payload_delays[folder_key] = {
                Path(item).name: self._get_tree_delay(item) for item in self.tree.get_children()
            }

        payload_ports = self.settings.get("payload_ports", {})
        if not isinstance(payload_ports, dict):
            payload_ports = {}

        if hasattr(self, "tree") and payload_dir_path is not None:
            payload_ports[folder_key] = {
                Path(item).name: self._get_tree_port(item) for item in self.tree.get_children()
            }

        settings = {
            "payload_dir": self.payload_dir.get().strip(),
            "host": self.host.get().strip(),
            "port": self.port.get().strip(),
            "payload_orders": payload_orders,
            "payload_delays": payload_delays,
            "payload_ports": payload_ports,
        }

        try:
            CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
            CONFIG_PATH.write_text(json.dumps(settings, indent=2), encoding="utf-8")
            self.settings = settings
        except OSError as exc:
            self._log(f"Could not save settings: {exc}")

    def _on_close(self):
        self._save_settings()
        self.destroy()

    def _build_ui(self):
        root = ttk.Frame(self, padding=(18, 16, 18, 16))
        root.pack(fill="both", expand=True)
        root.columnconfigure(0, weight=1)
        root.rowconfigure(2, weight=1)

        title_row = ttk.Frame(root)
        title_row.grid(row=0, column=0, sticky="ew")
        title_row.columnconfigure(0, weight=1)

        ttk.Label(title_row, text="Blurfer", style="Header.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Label(
            title_row,
            text="Send one payload, selected payloads, or a full folder queue.",
            style="Muted.TLabel",
        ).grid(row=1, column=0, sticky="w", pady=(2, 0))

        settings = ttk.Frame(root, style="Panel.TFrame", padding=(16, 14))
        settings.grid(row=1, column=0, sticky="ew", pady=(14, 12))
        settings.columnconfigure(0, weight=0)
        settings.columnconfigure(1, weight=0)
        settings.columnconfigure(2, weight=1)
        settings.columnconfigure(3, weight=0)
        settings.columnconfigure(4, weight=0)

        ttk.Label(settings, text="Host", style="Panel.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Entry(settings, textvariable=self.host, width=24).grid(row=1, column=0, sticky="ew", padx=(0, 12), pady=(6, 0))

        ttk.Label(settings, text="Default port", style="Panel.TLabel").grid(row=0, column=1, sticky="w")
        ttk.Spinbox(settings, from_=1, to=65535, textvariable=self.port, width=8).grid(
            row=1,
            column=1,
            sticky="w",
            padx=(0, 12),
            pady=(6, 0),
        )

        ttk.Label(settings, text="Payload folder", style="Panel.TLabel").grid(row=0, column=2, columnspan=3, sticky="w")
        ttk.Entry(settings, textvariable=self.payload_dir).grid(
            row=1,
            column=2,
            columnspan=1,
            sticky="ew",
            padx=(0, 8),
            pady=(6, 0),
        )
        ttk.Button(settings, text="Choose", style="Small.TButton", command=self.browse_payload_dir).grid(row=1, column=3, sticky="e", pady=(6, 0))
        ttk.Button(settings, text="Refresh", style="Small.TButton", command=self.refresh_payloads).grid(row=1, column=4, sticky="e", padx=(8, 0), pady=(6, 0))

        main = ttk.Frame(root)
        main.grid(row=2, column=0, sticky="nsew")
        main.columnconfigure(0, weight=5)
        main.columnconfigure(1, weight=3)
        main.rowconfigure(0, weight=1)

        payload_panel = ttk.Frame(main, style="Panel.TFrame", padding=(16, 14))
        payload_panel.grid(row=0, column=0, sticky="nsew", padx=(0, 10))
        payload_panel.columnconfigure(0, weight=1)
        payload_panel.rowconfigure(1, weight=1)

        payload_header = ttk.Frame(payload_panel, style="Panel.TFrame")
        payload_header.grid(row=0, column=0, sticky="ew", pady=(0, 8))
        payload_header.columnconfigure(0, weight=1)
        ttk.Label(payload_header, text="Payloads", style="Section.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Label(payload_header, textvariable=self.payload_count, style="Count.TLabel").grid(row=0, column=1, sticky="e")

        table_frame = ttk.Frame(payload_panel, style="Panel.TFrame")
        table_frame.grid(row=1, column=0, sticky="nsew")
        table_frame.columnconfigure(0, weight=1)
        table_frame.rowconfigure(0, weight=1)

        columns = ("name", "size", "modified", "port", "delay", "status")
        self.tree = ttk.Treeview(table_frame, columns=columns, show="headings", selectmode="extended")
        self.tree.heading("name", text="Name")
        self.tree.heading("size", text="Size")
        self.tree.heading("modified", text="Modified")
        self.tree.heading("port", text="Port")
        self.tree.heading("delay", text="Delay")
        self.tree.heading("status", text="Status")
        self.tree.column("name", minwidth=130, width=190, anchor="w", stretch=True)
        self.tree.column("size", minwidth=68, width=76, anchor="e", stretch=False)
        self.tree.column("modified", minwidth=110, width=122, anchor="center", stretch=False)
        self.tree.column("port", minwidth=68, width=76, anchor="center", stretch=False)
        self.tree.column("delay", minwidth=62, width=70, anchor="center", stretch=False)
        self.tree.column("status", minwidth=76, width=84, anchor="center", stretch=False)
        self.tree.grid(row=0, column=0, sticky="nsew")
        self.tree.bind("<<TreeviewSelect>>", self._sync_selected_payload_controls)
        self.tree.tag_configure("evenrow", background=self.colors["panel"])
        self.tree.tag_configure("oddrow", background=self.colors["row_alt"])

        scrollbar = ttk.Scrollbar(table_frame, orient="vertical", command=self.tree.yview)
        scrollbar.grid(row=0, column=1, sticky="ns")
        self.tree.configure(yscrollcommand=scrollbar.set)

        actions = ttk.Frame(payload_panel, style="Panel.TFrame")
        actions.grid(row=2, column=0, sticky="ew", pady=(10, 0))
        actions.columnconfigure(3, weight=1)

        order_actions = ttk.Frame(actions, style="Panel.TFrame")
        order_actions.grid(row=0, column=0, sticky="w")
        ttk.Button(order_actions, text="Up", style="Small.TButton", command=self.move_selected_up, width=7).grid(row=0, column=0, sticky="w")
        ttk.Button(order_actions, text="Down", style="Small.TButton", command=self.move_selected_down, width=7).grid(row=0, column=1, sticky="w", padx=(6, 0))

        port_actions = ttk.Frame(actions, style="Panel.TFrame")
        port_actions.grid(row=0, column=1, sticky="w", padx=(14, 0))
        ttk.Label(port_actions, text="Port", style="PanelMuted.TLabel").grid(row=0, column=0, sticky="w", padx=(0, 6))
        ttk.Spinbox(port_actions, from_=1, to=65535, textvariable=self.selected_port, width=8).grid(row=0, column=1, sticky="w")
        ttk.Button(port_actions, text="Set", style="Small.TButton", command=self.apply_selected_port, width=6).grid(row=0, column=2, sticky="w", padx=(6, 0))

        delay_actions = ttk.Frame(actions, style="Panel.TFrame")
        delay_actions.grid(row=0, column=2, sticky="w", padx=(12, 0))
        ttk.Label(delay_actions, text="Delay", style="PanelMuted.TLabel").grid(row=0, column=0, sticky="w", padx=(0, 6))
        ttk.Spinbox(delay_actions, from_=0, to=3600, increment=0.5, textvariable=self.selected_delay, width=8).grid(row=0, column=1, sticky="w")
        ttk.Button(delay_actions, text="Set", style="Small.TButton", command=self.apply_selected_delay, width=6).grid(row=0, column=2, sticky="w", padx=(6, 0))

        inject_actions = ttk.Frame(actions, style="Panel.TFrame")
        inject_actions.grid(row=1, column=0, columnspan=4, sticky="e", pady=(9, 0))
        ttk.Button(inject_actions, text="Inject Selected", style="Accent.TButton", command=self.inject_selected).grid(row=0, column=1, sticky="e")
        ttk.Button(inject_actions, text="Inject All", style="Accent.TButton", command=self.inject_all).grid(row=0, column=2, sticky="e", padx=(8, 0))
        self.stop_button = ttk.Button(inject_actions, text="Stop", style="Small.TButton", command=self.stop_queue, state="disabled")
        self.stop_button.grid(row=0, column=3, sticky="e", padx=(8, 0))

        log_panel = ttk.Frame(main, style="Panel.TFrame", padding=(16, 14))
        log_panel.grid(row=0, column=1, sticky="nsew")
        log_panel.columnconfigure(0, weight=1)
        log_panel.rowconfigure(1, weight=1)

        ttk.Label(log_panel, text="Activity", style="Section.TLabel").grid(row=0, column=0, sticky="w", pady=(0, 8))
        self.log = tk.Text(
            log_panel,
            height=12,
            width=44,
            wrap="word",
            borderwidth=0,
            relief="flat",
            bg="#f8fafc",
            fg=self.colors["text"],
            insertbackground=self.colors["text"],
            font=("Consolas", 10),
            padx=10,
            pady=10,
        )
        self.log.grid(row=1, column=0, sticky="nsew")
        self.log.configure(state="disabled")

        progress_row = ttk.Frame(log_panel, style="Panel.TFrame")
        progress_row.grid(row=2, column=0, sticky="ew", pady=(12, 0))
        progress_row.columnconfigure(0, weight=1)
        self.progress = ttk.Progressbar(progress_row, mode="determinate")
        self.progress.grid(row=0, column=0, sticky="ew", padx=(0, 10))
        ttk.Label(progress_row, textvariable=self.status, style="PanelMuted.TLabel").grid(row=0, column=1, sticky="e")

        self._log("Choose a payload folder, enter the target host/IP, then inject.")

    def _setup_drag_and_drop(self):
        if DND_FILES is None:
            self._log("File drag and drop is not available in this Python environment.")
            return

        for widget in (self, self.tree):
            widget.drop_target_register(DND_FILES)
            widget.dnd_bind("<<Drop>>", self._handle_payload_drop)

    def _handle_payload_drop(self, event):
        dropped_files = self._parse_dropped_files(event.data)
        if not dropped_files:
            return "break"

        copied, skipped, failed = self._copy_dropped_files_to_payload_folder(dropped_files)
        if copied:
            self.refresh_payloads()

        if not copied and not skipped and not failed:
            return "break"

        summary = [f"Copied {copied} payload{'s' if copied != 1 else ''}"]
        if skipped:
            summary.append(f"skipped {skipped}")
        if failed:
            summary.append(f"failed {failed}")
        self._log(", ".join(summary) + ".")
        return "break"

    def _parse_dropped_files(self, raw_data):
        try:
            items = self.tk.splitlist(raw_data)
        except tk.TclError:
            items = raw_data.split()
        return [Path(item) for item in items]

    def _copy_dropped_files_to_payload_folder(self, dropped_files):
        payload_dir = self._payload_dir_path()
        if payload_dir is None:
            self._log("Choose a payload folder before dropping files.")
            return 0, 0, 0

        if not payload_dir.is_dir():
            self._log(f"Payload folder not found: {payload_dir}")
            return 0, 0, 0

        copied = 0
        skipped = 0
        failed = 0

        for source in dropped_files:
            try:
                if not source.is_file():
                    skipped += 1
                    self._log(f"Skipped {source.name}: not a file.")
                    continue

                destination = self._unique_destination_path(payload_dir / source.name)
                if self._same_file(source, destination):
                    skipped += 1
                    self._log(f"Skipped {source.name}: already in payload folder.")
                    continue

                shutil.copy2(source, destination)
                copied += 1
                self._log(f"Copied {source.name} to payload folder.")
            except OSError as exc:
                failed += 1
                self._log(f"Could not copy {source.name}: {exc}")

        return copied, skipped, failed

    def _unique_destination_path(self, destination):
        if not destination.exists():
            return destination

        parent = destination.parent
        stem = destination.stem
        suffix = destination.suffix
        counter = 1

        while True:
            candidate = parent / f"{stem} ({counter}){suffix}"
            if not candidate.exists():
                return candidate
            counter += 1

    def _same_file(self, source, destination):
        try:
            return source.resolve() == destination.resolve()
        except OSError:
            return False

    def browse_payload_dir(self):
        initial_dir = self.payload_dir.get().strip()
        if not initial_dir or not Path(initial_dir).expanduser().is_dir():
            initial_dir = str(Path.home())

        selected = filedialog.askdirectory(initialdir=initial_dir)
        if selected:
            self.payload_dir.set(selected)
            self.refresh_payloads()

    def refresh_payloads(self):
        path = self._payload_dir_path()
        for item in self.tree.get_children():
            self.tree.delete(item)

        if path is None:
            self.payload_files = []
            self.status.set("Choose folder")
            self.payload_count.set("0 payloads")
            self._log("Choose a payload folder to load payloads.")
            self._save_settings()
            return

        if not path.is_dir():
            self.payload_files = []
            self.status.set("Folder missing")
            self.payload_count.set("0 payloads")
            self._log(f"Payload folder not found: {path}")
            self._save_settings()
            return

        discovered = sorted((p for p in path.iterdir() if self._is_payload_file(p)), key=lambda item: item.name.lower())
        self.payload_files = self._apply_saved_payload_order(path, discovered)

        for index, file_path in enumerate(self.payload_files):
            stat = file_path.stat()
            modified = time.strftime("%Y-%m-%d %H:%M", time.localtime(stat.st_mtime))
            port = self._payload_port_for_file(path, file_path)
            delay = self._payload_delay_for_file(path, file_path)
            self.tree.insert(
                "",
                "end",
                iid=str(file_path),
                values=(file_path.name, format_size(stat.st_size), modified, port, delay, "Ready"),
                tags=("evenrow" if index % 2 == 0 else "oddrow",),
            )

        count = len(self.payload_files)
        self.status.set(f"{count} payload{'s' if count != 1 else ''}")
        self.payload_count.set(f"{count} payload{'s' if count != 1 else ''}")
        if count == 0:
            self._log(f"No payloads found in {path}")
        else:
            self._log(f"Loaded {count} payload{'s' if count != 1 else ''} from {path}")
        self._save_settings()

    def inject_selected(self):
        selected_ids = set(self.tree.selection())
        selected = [Path(item) for item in self.tree.get_children() if item in selected_ids]
        if not selected:
            messagebox.showinfo("No payload selected", "Select one or more payloads first.")
            return
        self._start_injection(selected)

    def inject_all(self):
        if self._payload_dir_path() is None:
            messagebox.showinfo("No payload folder", "Choose a payload folder first.")
            return

        if not self.payload_files:
            messagebox.showinfo("No payloads found", "Add payload files to the selected folder first.")
            return
        self._start_injection(self._ordered_payload_files())

    def move_selected_up(self):
        self._move_selected(-1)

    def move_selected_down(self):
        self._move_selected(1)

    def apply_selected_delay(self):
        if self.worker and self.worker.is_alive():
            messagebox.showinfo("Injection in progress", "Wait for the current queue to finish before changing delays.")
            return

        try:
            delay = self._clean_delay_value(self.selected_delay.get(), "Selected delay")
        except ValueError as exc:
            messagebox.showerror("Invalid delay", str(exc))
            return

        selected = self.tree.selection()
        if not selected:
            messagebox.showinfo("No payload selected", "Select one or more payloads first.")
            return

        for item in selected:
            self._set_tree_delay(item, delay)

        self._save_settings()

    def apply_selected_port(self):
        if self.worker and self.worker.is_alive():
            messagebox.showinfo("Injection in progress", "Wait for the current queue to finish before changing ports.")
            return

        try:
            port = self._clean_port_value(self.selected_port.get(), "Selected port")
        except ValueError as exc:
            messagebox.showerror("Invalid port", str(exc))
            return

        selected = self.tree.selection()
        if not selected:
            messagebox.showinfo("No payload selected", "Select one or more payloads first.")
            return

        for item in selected:
            self._set_tree_port(item, port)

        self._save_settings()

    def stop_queue(self):
        if self.worker and self.worker.is_alive():
            self.stop_event.set()
            self.status.set("Stopping...")
            self._log("Stop requested. The current payload will finish before the queue stops.")

    def _start_injection(self, files):
        if self.worker and self.worker.is_alive():
            messagebox.showinfo("Injection in progress", "Wait for the current queue to finish or press Stop.")
            return

        try:
            host = self._clean_host()
            queue_items = self._build_injection_queue(files)
        except ValueError as exc:
            messagebox.showerror("Invalid settings", str(exc))
            return

        self._save_settings()

        for file_path in files:
            self._set_tree_status(file_path, "Queued")

        self.progress.configure(maximum=len(files), value=0)
        self.status.set("Starting...")
        self.stop_event.clear()
        self.stop_button.configure(state="normal")
        self.worker = threading.Thread(
            target=self._inject_worker,
            args=(queue_items, host),
            daemon=True,
        )
        self.worker.start()

    def _inject_worker(self, queue_items, host):
        completed = 0
        total = len(queue_items)

        for index, (file_path, port, delay) in enumerate(queue_items):
            if self.stop_event.is_set():
                self.events.put(("log", "Queue stopped."))
                break

            if delay > 0:
                self.events.put(("status", file_path, "Waiting"))
                self.events.put(("message", f"Waiting {delay:g} seconds before {file_path.name}..."))
                self._sleep_with_stop(delay)
                if self.stop_event.is_set():
                    self.events.put(("log", "Queue stopped."))
                    break

            self.events.put(("status", file_path, "Sending"))
            self.events.put(("message", f"Sending {file_path.name} to {host}:{port}"))

            try:
                sent = send_payload(file_path, host, port)
            except Exception as exc:
                self.events.put(("status", file_path, "Failed"))
                self.events.put(("message", f"Failed {file_path.name}: {exc}"))
            else:
                completed += 1
                self.events.put(("status", file_path, "Sent"))
                self.events.put(("message", f"Sent {format_size(sent)} from {file_path.name}"))

            self.events.put(("progress", index + 1, total))

        self.events.put(("done", completed, total))

    def _sleep_with_stop(self, seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline and not self.stop_event.is_set():
            time.sleep(min(0.1, deadline - time.monotonic()))

    def _process_events(self):
        try:
            while True:
                event = self.events.get_nowait()
                kind = event[0]

                if kind == "status":
                    _, file_path, status = event
                    self._set_tree_status(file_path, status)
                    self.status.set(status)
                elif kind == "message":
                    self._log(event[1])
                elif kind == "progress":
                    _, current, total = event
                    self.progress.configure(maximum=total, value=current)
                    self.status.set(f"{current}/{total}")
                elif kind == "done":
                    _, completed, total = event
                    self.stop_button.configure(state="disabled")
                    self.status.set(f"Done: {completed}/{total} sent")
                    self._log(f"Queue finished. {completed}/{total} payloads sent.")
        except queue.Empty:
            pass

        self.after(100, self._process_events)

    def _payload_dir_path(self):
        raw_path = self.payload_dir.get().strip()
        if not raw_path:
            return None
        return Path(raw_path).expanduser()

    def _apply_saved_payload_order(self, folder_path, files):
        order = self.settings.get("payload_orders", {}).get(str(folder_path), [])
        remaining = {file_path.name: file_path for file_path in files}
        ordered = []

        for name in order:
            file_path = remaining.pop(name, None)
            if file_path is not None:
                ordered.append(file_path)

        ordered.extend(sorted(remaining.values(), key=lambda item: item.name.lower()))
        return ordered

    def _ordered_payload_files(self):
        return [Path(item) for item in self.tree.get_children()]

    def _payload_port_for_file(self, folder_path, file_path):
        folder_ports = self.settings.get("payload_ports", {}).get(str(folder_path), {})
        if isinstance(folder_ports, dict) and file_path.name in folder_ports:
            return str(folder_ports[file_path.name])
        return self._default_port_text()

    def _default_port_text(self):
        return self.port.get().strip() or str(self.settings.get("port", DEFAULT_PORT))

    def _payload_delay_for_file(self, folder_path, file_path):
        folder_delays = self.settings.get("payload_delays", {}).get(str(folder_path), {})
        if isinstance(folder_delays, dict) and file_path.name in folder_delays:
            return str(folder_delays[file_path.name])
        return str(self.settings.get("delay", "0"))

    def _build_injection_queue(self, files):
        queue_items = []
        for file_path in files:
            port = self._clean_port_value(self._get_tree_port(str(file_path)), f"{file_path.name} port")
            delay = self._clean_delay_value(self._get_tree_delay(str(file_path)), file_path.name)
            queue_items.append((file_path, port, delay))
        return queue_items

    def _sync_selected_payload_controls(self, _event=None):
        selected = self.tree.selection()
        if len(selected) == 1:
            self.selected_port.set(self._get_tree_port(selected[0]))
            self.selected_delay.set(self._get_tree_delay(selected[0]))

    def _get_tree_port(self, item_id):
        if not hasattr(self, "tree") or not self.tree.exists(item_id):
            return self._default_port_text()

        values = list(self.tree.item(item_id, "values"))
        return str(values[3]) if len(values) > 3 and str(values[3]).strip() else self._default_port_text()

    def _get_tree_delay(self, item_id):
        if not hasattr(self, "tree") or not self.tree.exists(item_id):
            return "0"

        values = list(self.tree.item(item_id, "values"))
        return str(values[4]) if len(values) > 4 and str(values[4]).strip() else "0"

    def _set_tree_port(self, item_id, port):
        if self.tree.exists(item_id):
            values = list(self.tree.item(item_id, "values"))
            values[3] = str(port)
            self.tree.item(item_id, values=values)

    def _set_tree_delay(self, item_id, delay):
        if self.tree.exists(item_id):
            values = list(self.tree.item(item_id, "values"))
            values[4] = f"{delay:g}"
            self.tree.item(item_id, values=values)

    def _move_selected(self, direction):
        if self.worker and self.worker.is_alive():
            messagebox.showinfo("Injection in progress", "Wait for the current queue to finish before changing the order.")
            return

        selected = list(self.tree.selection())
        if not selected:
            messagebox.showinfo("No payload selected", "Select one or more payloads first.")
            return

        children = list(self.tree.get_children())
        selected_set = set(selected)

        if direction < 0:
            for index in range(1, len(children)):
                if children[index] in selected_set and children[index - 1] not in selected_set:
                    children[index - 1], children[index] = children[index], children[index - 1]
        else:
            for index in range(len(children) - 2, -1, -1):
                if children[index] in selected_set and children[index + 1] not in selected_set:
                    children[index + 1], children[index] = children[index], children[index + 1]

        for index, item in enumerate(children):
            self.tree.move(item, "", index)

        self.tree.selection_set(selected)
        self.payload_files = self._ordered_payload_files()
        self._save_settings()

    def _is_payload_file(self, path):
        return path.is_file() and path.name != ".gitkeep"

    def _clean_host(self):
        host = self.host.get().strip()
        if not host:
            raise ValueError("Enter the target host/IP address.")
        return host

    def _clean_port(self):
        return self._clean_port_value(self.port.get(), "Default port")

    def _clean_port_value(self, raw_port, label):
        try:
            port = int(str(raw_port).strip())
        except ValueError as exc:
            raise ValueError(f"{label} must be a number.") from exc

        if not 1 <= port <= 65535:
            raise ValueError(f"{label} must be between 1 and 65535.")
        return port

    def _clean_delay_value(self, raw_delay, label):
        try:
            delay = float(str(raw_delay).strip() or "0")
        except ValueError as exc:
            raise ValueError(f"{label} delay must be a number of seconds.") from exc

        if delay < 0:
            raise ValueError(f"{label} delay cannot be negative.")
        return delay

    def _set_tree_status(self, file_path, status):
        item_id = str(file_path)
        if self.tree.exists(item_id):
            values = list(self.tree.item(item_id, "values"))
            values[5] = status
            self.tree.item(item_id, values=values)

    def _log(self, message):
        timestamp = time.strftime("%H:%M:%S")
        self.log.configure(state="normal")
        self.log.insert("end", f"[{timestamp}] {message}\n")
        self.log.see("end")
        self.log.configure(state="disabled")


if __name__ == "__main__":
    app = BlurferApp()
    app.mainloop()
