import json
import os
import queue
import shutil
import socket
import sys
import threading
import time
import urllib.request
import urllib.error
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import customtkinter as ctk

try:
    from tkinterdnd2 import DND_FILES, TkinterDnD
except ImportError:
    DND_FILES = None
    TkinterDnD = None

# Configure CustomTkinter
ctk.set_appearance_mode("dark")
ctk.set_default_color_theme("blue")

APP_DIR = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
RESOURCE_DIR = Path(getattr(sys, "_MEIPASS", APP_DIR))
DEFAULT_PORT = 9021
SOCKET_TIMEOUT_SECONDS = 15
APP_NAME = "Blurfer"
SETTINGS_FILE_NAME = "settings.json"

DEFAULT_REPOS = [
    "ItsBlurf/BFpilot",
    "drakmor/ShadowMountPlus",
    "EchoStretch/kstuff-lite",
    "juma-sayeh/PS5-Game-Compressor",
    "seregonwar/zftpd"
]

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

def fetch_github_releases(repo_slug):
    url = f"https://api.github.com/repos/{repo_slug}/releases"
    headers = {
        "User-Agent": "Blurfer-App"
    }
    token = os.environ.get("GITHUB_PERSONAL_ACCESS_TOKEN") or os.environ.get("GITHUB_TOKEN")
    
    # Try with token first if configured
    if token:
        headers_with_token = headers.copy()
        headers_with_token["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(url, headers=headers_with_token)
        try:
            with urllib.request.urlopen(req, timeout=12) as response:
                data = json.loads(response.read().decode())
                return data, None
        except urllib.error.HTTPError as e:
            if e.code == 401:
                # Token is unauthorized/invalid, fall through to anonymous retry
                pass
            else:
                if e.code == 404:
                    return None, "Repository not found (404)."
                elif e.code == 403 and "rate limit" in e.reason.lower():
                    return None, "GitHub API rate limit exceeded. Clear or set GITHUB_PERSONAL_ACCESS_TOKEN."
                return None, f"GitHub API HTTP Error {e.code}: {e.reason}"
        except Exception:
            # Fall through on connection error with token to try anonymously
            pass

    # Fallback to anonymous request
    req_anon = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req_anon, timeout=12) as response:
            data = json.loads(response.read().decode())
            return data, None
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None, "Repository not found (404)."
        elif e.code == 403 and "rate limit" in e.reason.lower():
            return None, "GitHub API rate limit exceeded. Please configure a GitHub Token."
        return None, f"GitHub API HTTP Error {e.code}: {e.reason}"
    except urllib.error.URLError as e:
        return None, f"Network connection error: {e.reason}"
    except Exception as e:
        return None, f"Unexpected error: {str(e)}"

# Choose base class depending on TkinterDnD availability
APP_BASE_CLASS = ctk.CTk
if TkinterDnD:
    class BlurferAppBase(ctk.CTk, TkinterDnD.DnDWrapper):
        def __init__(self):
            super().__init__()
            try:
                self.TkdndVersion = TkinterDnD._require(self)
            except Exception as e:
                print(f"Failed to initialize TkinterDnD: {e}")
                self.TkdndVersion = None
else:
    class BlurferAppBase(ctk.CTk):
        def __init__(self):
            super().__init__()

class BlurferApp(BlurferAppBase):
    def __init__(self):
        super().__init__()

        self.title("Blurfer")
        self._set_window_icon()
        self.minsize(1220, 680)
        self.geometry("1280x760")

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

        # Repositories list loading
        self.repositories = self.settings.get("repositories", DEFAULT_REPOS)
        if not isinstance(self.repositories, list):
            self.repositories = DEFAULT_REPOS.copy()
        else:
            self.repositories = list(self.repositories)
        self.selected_repo = self.repositories[0] if self.repositories else ""
        self.is_downloading = False

        self._configure_treeview_style()
        self._build_ui()
        self._setup_drag_and_drop()
        
        self.refresh_payloads()
        
        # Load the default repository data
        if self.selected_repo:
            self._on_repo_clicked(self.selected_repo)
            
        self.select_tab("inject")
        self.after(100, self._process_events)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    def _configure_treeview_style(self):
        style = ttk.Style(self)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass

        # Dark mode-friendly Treeview styling
        style.configure(
            "Treeview",
            background="#2d3748",       # Gray 700
            foreground="#f7fafc",       # Gray 50
            fieldbackground="#2d3748",
            rowheight=30,
            borderwidth=0,
            relief="flat",
            font=("Segoe UI", 10)
        )
        style.map(
            "Treeview",
            background=[("selected", "#1f538d")], # CustomTkinter Blue Accent
            foreground=[("selected", "#ffffff")]
        )
        style.configure(
            "Treeview.Heading",
            background="#1a202c",       # Gray 800
            foreground="#a0aec0",       # Gray 400
            font=("Segoe UI", 9, "bold"),
            relief="flat"
        )
        style.map(
            "Treeview.Heading",
            background=[("active", "#2d3748")]
        )

        self.colors = {
            "success": "#22c55e",
            "error": "#ef4444",
            "accent": "#1f538d",
            "text_muted": "#a0aec0"
        }

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
            "repositories": self.repositories,
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
        # Configure layout grids
        self.grid_columnconfigure(0, weight=0) # Sidebar
        self.grid_columnconfigure(1, weight=1) # Main View
        self.grid_rowconfigure(0, weight=1)

        # ----------------- SIDEBAR -----------------
        self.sidebar_frame = ctk.CTkFrame(self, width=220, corner_radius=0)
        self.sidebar_frame.grid(row=0, column=0, sticky="nsew")
        self.sidebar_frame.grid_rowconfigure(5, weight=1) # spacer

        self.title_label = ctk.CTkLabel(self.sidebar_frame, text="Blurfer", font=ctk.CTkFont(size=26, weight="bold"))
        self.title_label.grid(row=0, column=0, padx=20, pady=(20, 2))

        self.subtitle_label = ctk.CTkLabel(self.sidebar_frame, text="PS5 Payload Tool", font=ctk.CTkFont(size=12), text_color="gray")
        self.subtitle_label.grid(row=1, column=0, padx=20, pady=(0, 20))

        # Sidebar navigation buttons
        self.nav_inject_btn = ctk.CTkButton(
            self.sidebar_frame, 
            text="Inject Payloads", 
            fg_color="transparent", 
            text_color=("gray10", "gray90"),
            hover_color=("gray70", "gray30"), 
            anchor="w",
            command=lambda: self.select_tab("inject")
        )
        self.nav_inject_btn.grid(row=2, column=0, padx=20, pady=5, sticky="ew")

        self.nav_download_btn = ctk.CTkButton(
            self.sidebar_frame, 
            text="Download Hub", 
            fg_color="transparent", 
            text_color=("gray10", "gray90"),
            hover_color=("gray70", "gray30"), 
            anchor="w",
            command=lambda: self.select_tab("download")
        )
        self.nav_download_btn.grid(row=3, column=0, padx=20, pady=5, sticky="ew")

        # Global PS5 Target settings at bottom of sidebar
        self.settings_sep = ctk.CTkLabel(self.sidebar_frame, text="TARGET PS5 SETTINGS", font=ctk.CTkFont(size=11, weight="bold"), text_color="gray")
        self.settings_sep.grid(row=6, column=0, padx=20, pady=(20, 5), sticky="w")

        self.host_label = ctk.CTkLabel(self.sidebar_frame, text="Host IP", font=ctk.CTkFont(size=11))
        self.host_label.grid(row=7, column=0, padx=20, pady=(5, 0), sticky="w")
        self.host_entry = ctk.CTkEntry(self.sidebar_frame, placeholder_text="e.g. 192.168.1.50", textvariable=self.host)
        self.host_entry.grid(row=8, column=0, padx=20, pady=(2, 10), sticky="ew")

        self.port_label = ctk.CTkLabel(self.sidebar_frame, text="Default Port", font=ctk.CTkFont(size=11))
        self.port_label.grid(row=9, column=0, padx=20, pady=(5, 0), sticky="w")
        self.port_entry = ctk.CTkEntry(self.sidebar_frame, textvariable=self.port)
        self.port_entry.grid(row=10, column=0, padx=20, pady=(2, 20), sticky="ew")

        self.version_label = ctk.CTkLabel(self.sidebar_frame, text="v1.2.0 (CustomTkinter)", font=ctk.CTkFont(size=10), text_color="gray50")
        self.version_label.grid(row=11, column=0, padx=20, pady=(0, 10), sticky="s")

        # ----------------- MAIN VIEW -----------------
        self.main_view_frame = ctk.CTkFrame(self, corner_radius=0, fg_color="transparent")
        self.main_view_frame.grid(row=0, column=1, sticky="nsew", padx=15, pady=15)
        self.main_view_frame.grid_columnconfigure(0, weight=1)
        self.main_view_frame.grid_rowconfigure(0, weight=1)

        # ----------------- INJECT TAB -----------------
        self.inject_tab = ctk.CTkFrame(self.main_view_frame, fg_color="transparent")
        self.inject_tab.grid_columnconfigure(0, weight=5)
        self.inject_tab.grid_columnconfigure(1, weight=3)
        self.inject_tab.grid_rowconfigure(1, weight=1)

        # Folder Selection bar
        folder_frame = ctk.CTkFrame(self.inject_tab, fg_color="transparent")
        folder_frame.grid(row=0, column=0, columnspan=2, sticky="ew", pady=(0, 12))
        folder_frame.grid_columnconfigure(1, weight=1)

        folder_label = ctk.CTkLabel(folder_frame, text="Payload Folder:")
        folder_label.grid(row=0, column=0, padx=(0, 10), sticky="w")

        folder_entry = ctk.CTkEntry(folder_frame, textvariable=self.payload_dir)
        folder_entry.grid(row=0, column=1, sticky="ew", padx=(0, 10))

        choose_btn = ctk.CTkButton(folder_frame, text="Choose", width=80, command=self.browse_payload_dir)
        choose_btn.grid(row=0, column=2, padx=(0, 10))

        refresh_btn = ctk.CTkButton(folder_frame, text="Refresh", width=80, command=self.refresh_payloads)
        refresh_btn.grid(row=0, column=3)

        # Left Panel (Table + Reordering)
        payloads_panel = ctk.CTkFrame(self.inject_tab)
        payloads_panel.grid(row=1, column=0, sticky="nsew", padx=(0, 10))
        payloads_panel.grid_columnconfigure(0, weight=1)
        payloads_panel.grid_rowconfigure(1, weight=1)

        payloads_header = ctk.CTkFrame(payloads_panel, fg_color="transparent")
        payloads_header.grid(row=0, column=0, sticky="ew", padx=12, pady=8)
        payloads_title = ctk.CTkLabel(payloads_header, text="Payload Queue", font=ctk.CTkFont(size=14, weight="bold"))
        payloads_title.pack(side="left")
        payloads_count_lbl = ctk.CTkLabel(payloads_header, textvariable=self.payload_count, text_color="gray")
        payloads_count_lbl.pack(side="right")

        # Payloads Table (Treeview)
        tree_frame = ctk.CTkFrame(payloads_panel, fg_color="transparent")
        tree_frame.grid(row=1, column=0, sticky="nsew", padx=12, pady=2)
        tree_frame.grid_columnconfigure(0, weight=1)
        tree_frame.grid_rowconfigure(0, weight=1)

        columns = ("name", "size", "modified", "port", "delay", "status")
        self.tree = ttk.Treeview(tree_frame, columns=columns, show="headings", selectmode="extended")
        self.tree.heading("name", text="Name")
        self.tree.heading("size", text="Size")
        self.tree.heading("modified", text="Modified")
        self.tree.heading("port", text="Port")
        self.tree.heading("delay", text="Delay")
        self.tree.heading("status", text="Status")
        self.tree.column("name", minwidth=110, width=140, anchor="w", stretch=True)
        self.tree.column("size", minwidth=55, width=65, anchor="e", stretch=False)
        self.tree.column("modified", minwidth=95, width=110, anchor="center", stretch=False)
        self.tree.column("port", minwidth=55, width=65, anchor="center", stretch=False)
        self.tree.column("delay", minwidth=45, width=55, anchor="center", stretch=False)
        self.tree.column("status", minwidth=65, width=75, anchor="center", stretch=False)
        self.tree.grid(row=0, column=0, sticky="nsew")
        self.tree.bind("<<TreeviewSelect>>", self._sync_selected_payload_controls)
        self.tree.bind("<Delete>", self.delete_selected_payloads)
        self.tree.tag_configure("evenrow", background="#2d3748")
        self.tree.tag_configure("oddrow", background="#242c3d")

        scrollbar = ttk.Scrollbar(tree_frame, orient="vertical", command=self.tree.yview)
        scrollbar.grid(row=0, column=1, sticky="ns")
        self.tree.configure(yscrollcommand=scrollbar.set)

        # Override & Reorder buttons frame
        controls_frame = ctk.CTkFrame(payloads_panel, fg_color="transparent")
        controls_frame.grid(row=2, column=0, sticky="ew", padx=12, pady=12)

        up_btn = ctk.CTkButton(controls_frame, text="Up", width=50, command=self.move_selected_up)
        up_btn.grid(row=0, column=0, padx=2)
        down_btn = ctk.CTkButton(controls_frame, text="Down", width=50, command=self.move_selected_down)
        down_btn.grid(row=0, column=1, padx=2)
        del_btn = ctk.CTkButton(controls_frame, text="Delete", width=60, fg_color="#991b1b", hover_color="#7f1d1d", command=self.delete_selected_payloads)
        del_btn.grid(row=0, column=2, padx=(2, 20))

        port_lbl = ctk.CTkLabel(controls_frame, text="Port:")
        port_lbl.grid(row=0, column=3, padx=2)
        port_val_entry = ctk.CTkEntry(controls_frame, textvariable=self.selected_port, width=60)
        port_val_entry.grid(row=0, column=4, padx=2)
        port_set_btn = ctk.CTkButton(controls_frame, text="Set", width=40, command=self.apply_selected_port)
        port_set_btn.grid(row=0, column=5, padx=(2, 20))

        delay_lbl = ctk.CTkLabel(controls_frame, text="Delay:")
        delay_lbl.grid(row=0, column=6, padx=2)
        delay_val_entry = ctk.CTkEntry(controls_frame, textvariable=self.selected_delay, width=50)
        delay_val_entry.grid(row=0, column=7, padx=2)
        delay_set_btn = ctk.CTkButton(controls_frame, text="Set", width=40, command=self.apply_selected_delay)
        delay_set_btn.grid(row=0, column=8, padx=2)

        # Right Panel (Log + Main Actions)
        right_panel = ctk.CTkFrame(self.inject_tab)
        right_panel.grid(row=1, column=1, sticky="nsew")
        right_panel.grid_columnconfigure(0, weight=1)
        right_panel.grid_rowconfigure(1, weight=1)

        log_title = ctk.CTkLabel(right_panel, text="Activity Log", font=ctk.CTkFont(size=14, weight="bold"))
        log_title.grid(row=0, column=0, sticky="w", padx=12, pady=8)

        self.log = ctk.CTkTextbox(right_panel, font=("Consolas", 12))
        self.log.grid(row=1, column=0, sticky="nsew", padx=12, pady=8)
        self.log.configure(state="disabled")

        actions_panel = ctk.CTkFrame(right_panel, fg_color="transparent")
        actions_panel.grid(row=2, column=0, sticky="ew", padx=12, pady=12)
        actions_panel.grid_columnconfigure(0, weight=1)
        actions_panel.grid_columnconfigure(1, weight=1)
        actions_panel.grid_columnconfigure(2, weight=1)

        self.progress = ctk.CTkProgressBar(actions_panel, mode="determinate")
        self.progress.grid(row=0, column=0, columnspan=3, sticky="ew", pady=(0, 6))
        self.progress.set(0.0)

        status_lbl = ctk.CTkLabel(actions_panel, textvariable=self.status, font=ctk.CTkFont(size=11), anchor="w")
        status_lbl.grid(row=1, column=0, columnspan=3, sticky="w", pady=(0, 10))

        inject_sel_btn = ctk.CTkButton(actions_panel, text="Inject Selected", command=self.inject_selected)
        inject_sel_btn.grid(row=2, column=0, padx=2, sticky="ew")

        inject_all_btn = ctk.CTkButton(actions_panel, text="Inject All", command=self.inject_all)
        inject_all_btn.grid(row=2, column=1, padx=2, sticky="ew")

        self.stop_button = ctk.CTkButton(actions_panel, text="Stop", fg_color="#991b1b", hover_color="#7f1d1d", state="disabled", command=self.stop_queue)
        self.stop_button.grid(row=2, column=2, padx=2, sticky="ew")

        # ----------------- DOWNLOAD TAB -----------------
        self.download_tab = ctk.CTkFrame(self.main_view_frame, fg_color="transparent")
        self.download_tab.grid_columnconfigure(0, weight=2) # Repo list
        self.download_tab.grid_columnconfigure(1, weight=4) # Details
        self.download_tab.grid_rowconfigure(0, weight=1)

        # Repositories list panel
        repos_panel = ctk.CTkFrame(self.download_tab)
        repos_panel.grid(row=0, column=0, sticky="nsew", padx=(0, 10))
        repos_panel.grid_columnconfigure(0, weight=1)
        repos_panel.grid_rowconfigure(1, weight=1)

        repos_title = ctk.CTkLabel(repos_panel, text="Homebrew Repos", font=ctk.CTkFont(size=14, weight="bold"))
        repos_title.grid(row=0, column=0, padx=12, pady=8, sticky="w")

        self.repo_list_frame = ctk.CTkScrollableFrame(repos_panel, label_text="Repositories")
        self.repo_list_frame.grid(row=1, column=0, sticky="nsew", padx=12, pady=2)

        repos_btns = ctk.CTkFrame(repos_panel, fg_color="transparent")
        repos_btns.grid(row=2, column=0, sticky="ew", padx=12, pady=12)
        repos_btns.grid_columnconfigure(0, weight=1)
        repos_btns.grid_columnconfigure(1, weight=1)

        add_repo_btn = ctk.CTkButton(repos_btns, text="Add Repo", command=self._add_repo_dialog)
        add_repo_btn.grid(row=0, column=0, padx=2, sticky="ew")

        remove_repo_btn = ctk.CTkButton(repos_btns, text="Remove Repo", fg_color="#991b1b", hover_color="#7f1d1d", command=self._remove_repo)
        remove_repo_btn.grid(row=0, column=1, padx=2, sticky="ew")

        # Details Panel
        details_panel = ctk.CTkFrame(self.download_tab)
        details_panel.grid(row=0, column=1, sticky="nsew")
        details_panel.grid_columnconfigure(0, weight=1)
        details_panel.grid_rowconfigure(1, weight=1) # split frame expands

        repo_header = ctk.CTkFrame(details_panel, fg_color="transparent")
        repo_header.grid(row=0, column=0, sticky="ew", padx=15, pady=(15, 5))

        self.repo_title_lbl = ctk.CTkLabel(repo_header, text="Select a Repository", font=ctk.CTkFont(size=16, weight="bold"))
        self.repo_title_lbl.pack(side="left")

        self.release_label = ctk.CTkLabel(repo_header, text="Version:")
        self.release_label.pack(side="right", padx=(10, 5))

        self.release_combo = ctk.CTkOptionMenu(repo_header, values=[], command=self._on_release_selected_combo, width=130)
        self.release_combo.pack(side="right")

        # Split frame for Notes & Assets
        split_frame = ctk.CTkFrame(details_panel, fg_color="transparent")
        split_frame.grid(row=1, column=0, sticky="nsew", padx=15, pady=5)
        split_frame.grid_columnconfigure(0, weight=1)
        split_frame.grid_columnconfigure(1, weight=1)
        split_frame.grid_rowconfigure(0, weight=1)

        # Notes Frame
        notes_frame = ctk.CTkFrame(split_frame)
        notes_frame.grid(row=0, column=0, sticky="nsew", padx=(0, 6))
        notes_frame.grid_columnconfigure(0, weight=1)
        notes_frame.grid_rowconfigure(1, weight=1)

        notes_title = ctk.CTkLabel(notes_frame, text="Release Notes", font=ctk.CTkFont(size=12, weight="bold"))
        notes_title.grid(row=0, column=0, padx=10, pady=6, sticky="w")

        self.release_notes = ctk.CTkTextbox(notes_frame, font=("Segoe UI", 12))
        self.release_notes.grid(row=1, column=0, sticky="nsew", padx=10, pady=(0, 10))
        self.release_notes.configure(state="disabled")

        # Assets Frame
        assets_frame = ctk.CTkFrame(split_frame)
        assets_frame.grid(row=0, column=1, sticky="nsew", padx=(6, 0))
        assets_frame.grid_columnconfigure(0, weight=1)
        assets_frame.grid_rowconfigure(1, weight=1)

        assets_title = ctk.CTkLabel(assets_frame, text="Release Assets (.elf/.bin/.js)", font=ctk.CTkFont(size=12, weight="bold"))
        assets_title.grid(row=0, column=0, padx=10, pady=6, sticky="w")

        assets_tree_frame = ctk.CTkFrame(assets_frame, fg_color="transparent")
        assets_tree_frame.grid(row=1, column=0, sticky="nsew", padx=10, pady=(0, 10))
        assets_tree_frame.grid_columnconfigure(0, weight=1)
        assets_tree_frame.grid_rowconfigure(0, weight=1)

        self.assets_tree = ttk.Treeview(assets_tree_frame, columns=("name", "size"), show="headings", selectmode="browse")
        self.assets_tree.heading("name", text="Asset Name")
        self.assets_tree.heading("size", text="Size")
        self.assets_tree.column("name", minwidth=130, width=170, anchor="w", stretch=True)
        self.assets_tree.column("size", minwidth=60, width=80, anchor="e", stretch=False)
        self.assets_tree.grid(row=0, column=0, sticky="nsew")
        self.assets_tree.tag_configure("evenrow", background="#2d3748")
        self.assets_tree.tag_configure("oddrow", background="#242c3d")

        assets_scroll = ttk.Scrollbar(assets_tree_frame, orient="vertical", command=self.assets_tree.yview)
        assets_scroll.grid(row=0, column=1, sticky="ns")
        self.assets_tree.configure(yscrollcommand=assets_scroll.set)

        # Download Action panel
        download_actions = ctk.CTkFrame(details_panel, fg_color="transparent")
        download_actions.grid(row=2, column=0, sticky="ew", padx=15, pady=15)
        download_actions.grid_columnconfigure(0, weight=1)

        self.download_progress = ctk.CTkProgressBar(download_actions, mode="determinate")
        self.download_progress.grid(row=0, column=0, sticky="ew", pady=(0, 6))
        self.download_progress.set(0.0)

        bottom_row = ctk.CTkFrame(download_actions, fg_color="transparent")
        bottom_row.grid(row=1, column=0, sticky="ew")
        bottom_row.grid_columnconfigure(0, weight=1)

        self.download_status_lbl = ctk.CTkLabel(bottom_row, text="Idle", text_color="gray", anchor="w")
        self.download_status_lbl.grid(row=0, column=0, sticky="w")

        self.download_btn = ctk.CTkButton(bottom_row, text="Download Asset", command=self._download_selected_asset)
        self.download_btn.grid(row=0, column=1, sticky="e")

    def select_tab(self, name):
        if name == "inject":
            self.inject_tab.grid(row=0, column=0, sticky="nsew")
            self.download_tab.grid_forget()
            self.nav_inject_btn.configure(fg_color="#1f538d", text_color="#ffffff")
            self.nav_download_btn.configure(fg_color="transparent", text_color=("gray10", "gray90"))
        else:
            self.download_tab.grid(row=0, column=0, sticky="nsew")
            self.inject_tab.grid_forget()
            self.nav_download_btn.configure(fg_color="#1f538d", text_color="#ffffff")
            self.nav_inject_btn.configure(fg_color="transparent", text_color=("gray10", "gray90"))

    def _setup_drag_and_drop(self):
        if DND_FILES is None:
            self._log("File drag and drop is not available in this Python environment.")
            return

        for widget in (self, self.tree):
            try:
                widget.drop_target_register(DND_FILES)
                widget.dnd_bind("<<Drop>>", self._handle_payload_drop)
            except Exception as e:
                self._log(f"Could not bind drag-and-drop: {e}")

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

        selected = filedialog.askdirectory(parent=self, initialdir=str(Path(initial_dir).expanduser()))
        if selected:
            self.payload_dir.set(str(selected))
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

        try:
            discovered = self._discover_payload_files(path)
        except OSError as exc:
            self.payload_files = []
            self.status.set("Folder unavailable")
            self.payload_count.set("0 payloads")
            self._log(f"Could not read payload folder: {exc}")
            self._save_settings()
            return

        self.payload_files = self._apply_saved_payload_order(path, discovered)

        visible_payloads = []
        for file_path in self.payload_files:
            try:
                stat = file_path.stat()
            except OSError:
                continue
            index = len(visible_payloads)
            visible_payloads.append(file_path)
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

        self.payload_files = visible_payloads
        count = len(self.payload_files)
        self.status.set(f"{count} payload{'s' if count != 1 else ''}")
        self.payload_count.set(f"{count} payload{'s' if count != 1 else ''}")
        if count == 0:
            self._log(f"No payloads found in {path}")
        else:
            self._log(f"Loaded {count} payload{'s' if count != 1 else ''} from {path}")
        self._save_settings()

    def delete_selected_payloads(self, _event=None):
        if self.worker and self.worker.is_alive():
            messagebox.showinfo("Injection in progress", "Wait for the current queue to finish before deleting payloads.")
            return "break"

        selected = [Path(item) for item in self.tree.selection()]
        if not selected:
            messagebox.showinfo("No payload selected", "Select one or more payloads first.")
            return "break"

        if len(selected) == 1:
            prompt = f"Permanently delete {selected[0].name}?"
        else:
            prompt = f"Permanently delete these {len(selected)} selected payloads?"

        if not messagebox.askyesno("Delete payloads", prompt + "\n\nThis cannot be undone.", icon="warning"):
            return "break"

        deleted = []
        failed = []
        payload_dir = self._payload_dir_path()
        for file_path in selected:
            try:
                if payload_dir is None or file_path.parent != payload_dir:
                    raise OSError("file is outside the selected payload folder")
                file_path.unlink()
                deleted.append(file_path.name)
            except OSError as exc:
                failed.append((file_path.name, str(exc)))

        if deleted:
            self._log(f"Deleted {len(deleted)} payload{'s' if len(deleted) != 1 else ''}: {', '.join(deleted)}")
        for name, error in failed:
            self._log(f"Could not delete {name}: {error}")

        self.refresh_payloads()
        if failed:
            messagebox.showerror(
                "Some payloads were not deleted",
                "\n".join(f"{name}: {error}" for name, error in failed),
            )
        return "break"

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

        self.progress.set(0.0)
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
                    self.progress.set(current / total if total > 0 else 0)
                    self.status.set(f"{current}/{total}")
                elif kind == "done":
                    _, completed, total = event
                    self.stop_button.configure(state="disabled")
                    self.status.set(f"Done: {completed}/{total} sent")
                    self._log(f"Queue finished. {completed}/{total} payloads sent.")
                elif kind == "gh_releases":
                    _, repo, releases, err = event
                    if repo == self.selected_repo:
                        if err:
                            self.release_combo.configure(values=[])
                            self.release_combo.set("Error")
                            self.release_notes.configure(state="normal")
                            self.release_notes.delete("1.0", "end")
                            self.release_notes.insert("end", f"Failed to fetch releases:\n{err}")
                            self.release_notes.configure(state="disabled")
                        else:
                            self.fetched_releases = releases
                            if not releases:
                                self.release_combo.configure(values=[])
                                self.release_combo.set("No Releases")
                                self.release_notes.configure(state="normal")
                                self.release_notes.delete("1.0", "end")
                                self.release_notes.insert("end", "This repository has no releases.")
                                self.release_notes.configure(state="disabled")
                            else:
                                tags = [r.get("tag_name") for r in releases]
                                self.release_combo.configure(values=tags)
                                self.release_combo.set(tags[0])
                                self._on_release_selected(tags[0])
                elif kind == "gh_download_progress":
                    _, bytes_read, total_size = event
                    if total_size > 0:
                        pct = bytes_read / total_size
                        self.download_progress.set(pct)
                        self.download_status_lbl.configure(
                            text=f"Downloading: {format_size(bytes_read)} / {format_size(total_size)} ({int(pct*100)}%)",
                            text_color=("gray10", "gray90")
                        )
                    else:
                        self.download_status_lbl.configure(
                            text=f"Downloading: {format_size(bytes_read)} (unknown total size)",
                            text_color=("gray10", "gray90")
                        )
                elif kind == "gh_download_done":
                    _, success, detail = event
                    self.is_downloading = False
                    self.download_btn.configure(state="normal")
                    if success:
                        self.download_progress.set(1.0)
                        self.download_status_lbl.configure(text=f"Finished! Saved {detail}", text_color="green")
                        self._log(f"Successfully downloaded {detail} from GitHub to payload folder.")
                        self.refresh_payloads()
                    else:
                        self.download_progress.set(0.0)
                        self.download_status_lbl.configure(text=f"Failed: {detail}", text_color="red")
                        self._log(f"Download failed: {detail}")
                        messagebox.showerror("Download Failed", f"Failed to download asset:\n{detail}")
        except queue.Empty:
            pass

        self.after(100, self._process_events)

    def _payload_dir_path(self):
        raw_path = self.payload_dir.get().strip()
        if not raw_path:
            return None
        return Path(raw_path).expanduser()

    def _normalized_existing_directory(self, path):
        try:
            normalized = Path(path).expanduser().resolve()
            return normalized if normalized.is_dir() else None
        except (OSError, RuntimeError):
            return None

    def _discover_payload_files(self, directory):
        return sorted(
            (path for path in directory.iterdir() if self._is_payload_file(path)),
            key=lambda path: path.name.lower(),
        )

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
        return path.is_file() and path.suffix.lower() in (".elf", ".bin", ".js") and not path.name.startswith(".")

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

    # ----------------- REPOSITORY ACTION HANDLERS -----------------
    def _update_repo_list_ui(self):
        for widget in self.repo_list_frame.winfo_children():
            widget.destroy()

        for repo in self.repositories:
            is_selected = (repo == self.selected_repo)
            btn = ctk.CTkButton(
                self.repo_list_frame,
                text=repo,
                anchor="w",
                fg_color="#1f538d" if is_selected else "transparent",
                text_color="#ffffff" if is_selected else ("gray10", "gray90"),
                hover_color=("#e2e8f0", "#334155") if not is_selected else "#1f538d",
                command=lambda r=repo: self._on_repo_clicked(r)
            )
            btn.pack(fill="x", pady=2, padx=5)

    def _on_repo_clicked(self, repo):
        if self.is_downloading:
            messagebox.showinfo("Download in progress", "Please wait for the current download to finish first.")
            return

        self.selected_repo = repo
        self._update_repo_list_ui()
        self.repo_title_lbl.configure(text=repo)

        # Clear combo & details
        self.release_combo.configure(values=[])
        self.release_combo.set("Loading...")
        self.release_notes.configure(state="normal")
        self.release_notes.delete("1.0", "end")
        self.release_notes.insert("end", "Loading release details from GitHub...")
        self.release_notes.configure(state="disabled")

        for item in self.assets_tree.get_children():
            self.assets_tree.delete(item)

        threading.Thread(target=self._fetch_releases_worker, args=(repo,), daemon=True).start()

    def _fetch_releases_worker(self, repo):
        releases, err = fetch_github_releases(repo)
        self.events.put(("gh_releases", repo, releases, err))

    def _on_release_selected_combo(self, val):
        self._on_release_selected(val)

    def _on_release_selected(self, tag_name):
        if not hasattr(self, "fetched_releases") or not self.fetched_releases:
            return

        selected_release = None
        for r in self.fetched_releases:
            if r.get("tag_name") == tag_name:
                selected_release = r
                break

        if not selected_release:
            return

        body = selected_release.get("body") or "No description provided."
        self.release_notes.configure(state="normal")
        self.release_notes.delete("1.0", "end")
        self.release_notes.insert("end", body)
        self.release_notes.configure(state="disabled")

        # Clear & load assets
        for item in self.assets_tree.get_children():
            self.assets_tree.delete(item)

        assets = selected_release.get("assets", [])
        filtered_assets = []
        for asset in assets:
            name = asset.get("name") or ""
            if any(name.lower().endswith(ext) for ext in (".elf", ".bin", ".js")):
                filtered_assets.append(asset)

        for index, asset in enumerate(filtered_assets):
            name = asset.get("name")
            size = format_size(asset.get("size", 0))
            url = asset.get("browser_download_url")
            self.assets_tree.insert(
                "",
                "end",
                iid=url,
                values=(name, size),
                tags=("evenrow" if index % 2 == 0 else "oddrow",)
            )

    def _download_selected_asset(self):
        if self.is_downloading:
            return

        selected = self.assets_tree.selection()
        if not selected:
            messagebox.showinfo("Select Asset", "Please select an asset from the table to download.")
            return

        download_url = selected[0]
        values = self.assets_tree.item(download_url, "values")
        asset_name = values[0]

        payload_dir = self._payload_dir_path()
        if payload_dir is None:
            messagebox.showerror("Payload Folder Missing", "Please choose a payload folder in the 'Inject Payloads' tab first.")
            return

        if not payload_dir.is_dir():
            messagebox.showerror("Payload Folder Missing", f"The configured payload folder does not exist:\n{payload_dir}")
            return

        dest_path = payload_dir / asset_name
        if dest_path.exists():
            if not messagebox.askyesno("Overwrite File", f"The file '{asset_name}' already exists in your payload folder.\nDo you want to overwrite it?"):
                return

        self.is_downloading = True
        self.download_btn.configure(state="disabled")
        self.download_status_lbl.configure(text="Connecting...", text_color="gray")
        self.download_progress.set(0.0)

        self.download_stop_event = threading.Event()
        threading.Thread(
            target=self._download_worker,
            args=(download_url, dest_path, self.download_stop_event),
            daemon=True
        ).start()

    def _download_worker(self, url, dest_path, stop_event):
        headers = {
            "User-Agent": "Blurfer-Downloader"
        }
        token = os.environ.get("GITHUB_PERSONAL_ACCESS_TOKEN") or os.environ.get("GITHUB_TOKEN")
        
        # Only send token if it's pointing to api.github.com
        if token and "api.github.com" in url:
            headers["Authorization"] = f"Bearer {token}"

        req = urllib.request.Request(url, headers=headers)
        try:
            response = urllib.request.urlopen(req, timeout=15)
        except urllib.error.HTTPError as e:
            if e.code == 401 and token:
                # Token is unauthorized/invalid, fall through to anonymous retry
                headers_anon = {
                    "User-Agent": "Blurfer-Downloader"
                }
                req_anon = urllib.request.Request(url, headers=headers_anon)
                try:
                    response = urllib.request.urlopen(req_anon, timeout=15)
                except Exception as ex:
                    self.events.put(("gh_download_done", False, str(ex)))
                    return
            else:
                self.events.put(("gh_download_done", False, f"HTTP Error {e.code}: {e.reason}"))
                return
        except Exception as e:
            self.events.put(("gh_download_done", False, str(e)))
            return

        try:
            with response:
                total_size = int(response.info().get('Content-Length', 0))
                bytes_read = 0

                temp_path = dest_path.with_suffix(dest_path.suffix + ".tmp")
                last_update_time = 0

                with open(temp_path, "wb") as f:
                    while True:
                        if stop_event.is_set():
                            break
                        chunk = response.read(16384)
                        if not chunk:
                            break
                        f.write(chunk)
                        bytes_read += len(chunk)

                        now = time.monotonic()
                        if now - last_update_time > 0.1:
                            self.events.put(("gh_download_progress", bytes_read, total_size))
                            last_update_time = now

                if stop_event.is_set():
                    if temp_path.exists():
                        temp_path.unlink()
                    self.events.put(("gh_download_done", False, "Download cancelled."))
                    return

                if dest_path.exists():
                    dest_path.unlink()
                shutil.move(str(temp_path), str(dest_path))
                self.events.put(("gh_download_done", True, dest_path.name))
        except Exception as e:
            self.events.put(("gh_download_done", False, str(e)))

    def _add_repo_dialog(self):
        if self.is_downloading:
            messagebox.showinfo("Download in progress", "Please wait for the current download to finish first.")
            return

        dialog = ctk.CTkInputDialog(text="Enter repository slug (owner/repo) or URL:", title="Add Repository")
        input_str = dialog.get_input()
        if not input_str:
            return

        input_str = input_str.strip()
        if not input_str:
            return

        repo_slug = None
        if "github.com/" in input_str:
            parts = input_str.split("github.com/")[1].split("/")
            if len(parts) >= 2:
                repo_slug = f"{parts[0]}/{parts[1]}"
        else:
            parts = input_str.split("/")
            if len(parts) == 2 and parts[0] and parts[1]:
                repo_slug = input_str

        if not repo_slug:
            messagebox.showerror("Invalid Format", "Please enter a valid slug 'owner/repo' or a GitHub repository link.")
            return

        exists = any(r.lower() == repo_slug.lower() for r in self.repositories)
        if exists:
            messagebox.showinfo("Duplicate", f"Repository '{repo_slug}' is already in your list.")
            return

        self.repositories.append(repo_slug)
        self.selected_repo = repo_slug
        self._update_repo_list_ui()
        self._save_settings()
        self._on_repo_clicked(repo_slug)

    def _remove_repo(self):
        if self.is_downloading:
            messagebox.showinfo("Download in progress", "Please wait for the current download to finish first.")
            return

        if not self.selected_repo:
            return

        if len(self.repositories) <= 1:
            messagebox.showinfo("Cannot Remove", "You must keep at least one repository in the list.")
            return

        if not messagebox.askyesno("Remove Repository", f"Are you sure you want to remove '{self.selected_repo}' from your list?"):
            return

        self.repositories.remove(self.selected_repo)
        next_repo = self.repositories[0]
        self.selected_repo = next_repo
        self._update_repo_list_ui()
        self._save_settings()
        self._on_repo_clicked(next_repo)


if __name__ == "__main__":
    app = BlurferApp()
    app.mainloop()
