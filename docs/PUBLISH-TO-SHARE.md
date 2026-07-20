# How to publish Kong to the share (V:\Encompass\Kong)

This guide is for **you, the maintainer** — the person who puts a new Kong
installer where coworkers can download it. Follow it top to bottom; no prior
knowledge assumed.

**What "publishing to the share" means:** copying the finished Kong installer
(`Kong-<version>.msi`) into the network folder `V:\Encompass\Kong`, plus keeping
a `Kong-latest.msi` copy there so people always have one link to click. Coworkers
then open that folder and double-click the file. That's the whole job.

There are two ways to do it. **Path A is the easy one** and the one to use after
a normal release. Path B is for when you built the installer on your own machine.

---

## Before you start (one-time setup)

You only do these once, ever. Skip any that are already true.

### 1. Make sure the V: drive works
- Open **File Explorer** (the yellow folder icon on your taskbar).
- In the left sidebar, click **This PC**.
- Look for a drive called **`V:`** (it may say "Encompass" or a server name).
  - **If you see it:** good, you're done with this step.
  - **If you don't see it:** the drive isn't mapped. Ask IT for the network path
    to the `Encompass` folder, or, if you know it, map it:
    *This PC → (top menu) ⋯ → Map network drive → Drive: `V:` → Folder:
    `\\yourserver\Encompass` → Finish.*

### 2. Install the GitHub CLI (only needed for Path A)
This is a small free tool named `gh` that downloads the installer from GitHub for
you.
- Open **PowerShell** (press the **Windows key**, type `powershell`, press Enter).
- Paste this and press Enter:
  ```powershell
  winget install --id GitHub.cli
  ```
- When it finishes, **close that PowerShell window and open a new one** (so it
  picks up the new tool).

### 3. Sign the GitHub CLI in to your account (only needed for Path A)
- In PowerShell, paste and press Enter:
  ```powershell
  gh auth login
  ```
- Answer the prompts by pressing Enter on the highlighted default each time:
  - **What account?** → `GitHub.com`
  - **Protocol?** → `HTTPS`
  - **Authenticate Git with your GitHub credentials?** → `Yes`
  - **How to log in?** → `Login with a web browser`
- It shows a one-time code and opens your browser. Type/paste the code, click
  **Authorize**, then return to PowerShell. You should see "Logged in as …".
- You never have to do this again on this machine.

---

## Path A — publish the installer that GitHub already built (recommended)

Use this right after you cut a release (your `cpt`). Cutting a release makes
GitHub automatically build the `.msi`; this path just fetches that exact file and
drops it on the share.

1. **Wait for the build to finish (~5 minutes after `cpt`).**
   Check it's done: open
   <https://github.com/cg-fnba/Kong/releases/latest> in your browser and confirm
   there's a file named `Kong-<version>.msi` under **Assets**. If it's not there
   yet, wait a minute and refresh.

2. **Open PowerShell in the Kong project folder.**
   - Open **File Explorer**, go to `C:\dev\jira-manager`.
   - Click once in the **address bar** (where the folder path is shown), type
     `powershell`, and press **Enter**. A blue PowerShell window opens already
     pointed at the right folder.

3. **Run the publish command.** Paste this exactly and press Enter:
   ```powershell
   powershell -ExecutionPolicy Bypass -File packaging\publish-to-share.ps1
   ```
   That's it — no version number to type. The script downloads the latest
   `Kong-<version>.msi` from GitHub and copies it (and a `Kong-latest.msi`) to
   `V:\Encompass\Kong`.

4. **Confirm it worked.** You'll see green text like:
   ```
   Published:
     V:\Encompass\Kong\Kong-1.2.3.msi
     V:\Encompass\Kong\Kong-latest.msi  (stable 'latest' link for coworkers)
   ```
   Done. Coworkers can now install from the share.

---

## Path B — publish an installer you built on your own machine

Use this only if you built the `.msi` yourself (with `packaging\package.ps1`)
instead of letting GitHub build it. You do **not** need the GitHub CLI for this.

1. **Build the installer first** (if you haven't already). In a PowerShell window
   opened in `C:\dev\jira-manager` (see Path A step 2 for how):
   ```powershell
   powershell -ExecutionPolicy Bypass -File packaging\package.ps1
   ```
   This creates a file under the `dist` folder, e.g. `dist\Kong-1.2.3.msi`.
   *(Building needs a JDK 21 and the WiX Toolset installed — see
   `docs/INSTALL.md` → "For maintainers".)*

2. **Publish that file to the share.** Replace the version with your real one:
   ```powershell
   powershell -ExecutionPolicy Bypass -File packaging\publish-to-share.ps1 -Msi dist\Kong-1.2.3.msi
   ```
   *(Not sure of the exact filename? Run `dir dist` to see what's there.)*

3. **Confirm it worked** — same green "Published:" message as Path A.

---

## Checking the result yourself

Open **File Explorer** and go to `V:\Encompass\Kong`. You should see:
- `Kong-<version>.msi` — the specific version you just published, and
- `Kong-latest.msi` — a copy of the same file, always pointing at the newest one.

That `Kong-latest.msi` is what the end-user instructions (`docs/INSTALL.md`) tell
coworkers to download, so refreshing it is how everyone gets the update.

---

## If something goes wrong

- **"running scripts is disabled on this system"** — you left off the safety
  prefix. Always start the command with
  `powershell -ExecutionPolicy Bypass -File ...` exactly as shown above.

- **"Share path not reachable: V:\Encompass\Kong"** — your `V:` drive isn't
  mapped or you don't have access. Redo *Before you start → step 1*, or check with
  IT that you can open the folder in File Explorer.

- **"GitHub CLI (gh) not found"** (Path A) — you skipped the one-time setup. Do
  *Before you start → steps 2 and 3*, then open a fresh PowerShell window.

- **"No Kong-*.msi asset found in the latest release"** (Path A) — the GitHub
  build hasn't finished or failed. Check the Releases page (Path A step 1); if the
  file never appears, look at the **Actions** tab on GitHub for a red ✗ build.

- **"Installer not found: dist\Kong-….msi"** (Path B) — the filename you typed
  doesn't match. Run `dir dist` to see the real name and use that.

- **You published the wrong version** — just re-run the correct command; it
  overwrites `Kong-latest.msi`. Delete any stray wrong-version file from
  `V:\Encompass\Kong` in File Explorer if you like.
