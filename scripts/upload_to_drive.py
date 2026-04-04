#!/usr/bin/env python3
"""
Upload docs/final/docx/*.docx (→ Google Docs) and docs/final/FinalPresentation.pptx
(→ Google Slides) into a Drive folder "Theremin Gloves Team 5 — Submission".

Auth priority:
  1. service_account.json  (repo root)  — headless, no browser needed
  2. credentials.json      (repo root)  — OAuth 2.0, opens browser on first run,
                                          saves token to token.json

Usage:
  python3 scripts/upload_to_drive.py
"""
import os
import sys
import json
import glob

REPO_ROOT  = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCX_DIR   = os.path.join(REPO_ROOT, "docs", "final", "docx")
PPTX_FILE  = os.path.join(REPO_ROOT, "docs", "final", "FinalPresentation.pptx")
SA_FILE    = os.path.join(REPO_ROOT, "service_account.json")
CREDS_FILE = os.path.join(REPO_ROOT, "credentials.json")
TOKEN_FILE = os.path.join(REPO_ROOT, "token.json")
FOLDER_NAME = "Theremin Gloves Team 5 — Submission"

SCOPES = [
    "https://www.googleapis.com/auth/drive.file",
]

# ── Auth ───────────────────────────────────────────────────────────────────────
def get_credentials():
    from google.oauth2 import service_account
    from google_auth_oauthlib.flow import InstalledAppFlow
    from google.auth.transport.requests import Request
    from google.oauth2.credentials import Credentials

    if os.path.exists(SA_FILE):
        print(f"Using service account: {SA_FILE}")
        return service_account.Credentials.from_service_account_file(
            SA_FILE, scopes=SCOPES
        )

    if not os.path.exists(CREDS_FILE):
        print(
            "ERROR: No credentials found.\n"
            "  Place credentials.json (OAuth) or service_account.json at the repo root.\n"
            "  See: https://console.cloud.google.com/apis/credentials"
        )
        sys.exit(1)

    creds = None
    if os.path.exists(TOKEN_FILE):
        creds = Credentials.from_authorized_user_file(TOKEN_FILE, SCOPES)
    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
        else:
            flow = InstalledAppFlow.from_client_secrets_file(CREDS_FILE, SCOPES)
            creds = flow.run_local_server(port=0)
        with open(TOKEN_FILE, "w") as t:
            t.write(creds.to_json())
        print(f"Token saved → {TOKEN_FILE}")
    return creds

# ── Drive helpers ──────────────────────────────────────────────────────────────
def get_or_create_folder(service, name):
    """Return the ID of the Drive folder, creating it if it doesn't exist."""
    query = (
        f"name='{name}' and mimeType='application/vnd.google-apps.folder'"
        " and trashed=false"
    )
    results = service.files().list(q=query, fields="files(id,name)").execute()
    files = results.get("files", [])
    if files:
        folder_id = files[0]["id"]
        print(f"Found existing folder '{name}'  id={folder_id}")
        return folder_id

    meta = {
        "name": name,
        "mimeType": "application/vnd.google-apps.folder",
    }
    folder = service.files().create(body=meta, fields="id").execute()
    folder_id = folder["id"]
    print(f"Created folder '{name}'  id={folder_id}")
    return folder_id

def upload_file(service, local_path, folder_id, upload_mime, target_mime, label):
    """Upload a file, converting to target_mime (Google Docs/Slides)."""
    from googleapiclient.http import MediaFileUpload

    file_name = os.path.basename(local_path)
    # Strip extension for Google Doc name
    display_name = os.path.splitext(file_name)[0].replace("_", " ")

    media = MediaFileUpload(local_path, mimetype=upload_mime, resumable=False)
    meta = {
        "name": display_name,
        "parents": [folder_id],
        "mimeType": target_mime,   # tells Drive to convert on import
    }
    result = service.files().create(
        body=meta, media_body=media, fields="id,webViewLink"
    ).execute()
    file_id  = result["id"]
    link     = result.get("webViewLink", f"https://drive.google.com/file/d/{file_id}")
    print(f"  ✓ {label}  →  {link}")
    return {"name": display_name, "id": file_id, "url": link}

# ── Main ───────────────────────────────────────────────────────────────────────
def main():
    from googleapiclient.discovery import build

    # Validate inputs
    docx_files = sorted(glob.glob(os.path.join(DOCX_DIR, "*.docx")))
    if not docx_files:
        print(f"ERROR: No .docx files found in {DOCX_DIR}")
        sys.exit(1)
    if not os.path.exists(PPTX_FILE):
        print(f"ERROR: {PPTX_FILE} not found — run build_pptx.py first")
        sys.exit(1)

    creds   = get_credentials()
    service = build("drive", "v3", credentials=creds)

    folder_id = get_or_create_folder(service, FOLDER_NAME)

    print(f"\nUploading {len(docx_files)} .docx files as Google Docs…")
    uploaded = []
    for path in docx_files:
        item = upload_file(
            service, path, folder_id,
            upload_mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            target_mime="application/vnd.google-apps.document",
            label=os.path.basename(path),
        )
        uploaded.append(item)

    print("\nUploading FinalPresentation.pptx as Google Slides…")
    item = upload_file(
        service, PPTX_FILE, folder_id,
        upload_mime="application/vnd.openxmlformats-officedocument.presentationml.presentation",
        target_mime="application/vnd.google-apps.presentation",
        label="FinalPresentation.pptx",
    )
    uploaded.append(item)

    # ── Summary ────────────────────────────────────────────────────────────────
    print(f"\n{'─'*70}")
    print(f"Folder: https://drive.google.com/drive/folders/{folder_id}")
    print(f"{'─'*70}")
    for item in uploaded:
        print(f"  {item['name']}")
        print(f"    {item['url']}")
    print(f"\n{len(uploaded)} files uploaded to '{FOLDER_NAME}'")

    # Save URLs to docs/final/drive_urls.json for reference
    urls_file = os.path.join(REPO_ROOT, "docs", "final", "drive_urls.json")
    with open(urls_file, "w") as f:
        json.dump({"folder_id": folder_id, "files": uploaded}, f, indent=2)
    print(f"URLs saved → {urls_file}")

if __name__ == "__main__":
    main()
