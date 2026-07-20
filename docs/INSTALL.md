# Installing Kong

Kong is a small desktop app: it runs on your own PC and opens in your web
browser at `http://localhost:7070`. The installer bundles everything it needs
(including Java) — **you do not need to install Java or anything else first.**

There are two ways to get the installer. Pick whichever your team uses:

- **[Method A — Internal file share](#method-a--internal-file-share)** — download from a network folder. No GitHub account needed.
- **[Method B — GitHub Releases](#method-b--github-releases)** — download from the project's Releases page.

Both end the same way: one file, double-click, paste an API token once. See
**[First-run setup](#first-run-setup)** and **[Everyday use](#everyday-use)** below.

---

## Method A — Internal file share

**Best for:** most people at FNBA. Nothing to sign into — if you can reach the
share, you can install Kong.

1. Open **File Explorer** and paste this path into the address bar, then press Enter:

   ```
   V:\Encompass\Kong
   ```
   *(If your `V:` drive isn't mapped, ask IT for the network path to the
   `Encompass\Kong` folder.)*

2. Double-click **`Kong-latest.msi`** (or the highest-numbered `Kong-1.x.y.msi`).

3. If Windows shows a **"Windows protected your PC"** SmartScreen prompt, click
   **More info → Run anyway**. (This appears for any installer that isn't
   code-signed; it's expected.)

4. The installer runs. Accept the defaults — it installs for your user only and
   adds a **Kong** shortcut to the Start Menu. No admin rights required.

5. Launch **Kong** from the Start Menu. Your browser opens automatically to the
   first-run setup screen. → [First-run setup](#first-run-setup)

**Updating later:** re-open the share, run the newer `Kong-latest.msi`, and it
upgrades in place. Your saved connection is kept.

---

## Method B — GitHub Releases

**Best for:** people who already have access to the Kong GitHub repository.

1. Go to the Releases page:

   ```
   https://github.com/cg-fnba/Kong/releases/latest
   ```
   *(You must be signed in to GitHub and have access to the repo. If you get a
   404, use [Method A](#method-a--internal-file-share) instead, or ask to be
   added to the repo.)*

2. Under **Assets**, click **`Kong-<version>.msi`** to download it.

3. Double-click the downloaded file. If SmartScreen warns you, choose
   **More info → Run anyway** (expected for unsigned installers).

4. Accept the defaults (per-user install, Start-Menu shortcut, no admin needed).

5. Launch **Kong** from the Start Menu — the browser opens to setup.
   → [First-run setup](#first-run-setup)

**Updating later:** download the newer release's `.msi` and run it; it upgrades
in place and keeps your saved connection.

---

## First-run setup

The first time Kong runs, it opens a **Welcome to Kong** screen and asks for
three things:

| Field | What to enter |
|-------|---------------|
| **Jira site URL** | `https://fnba.atlassian.net` (already filled in). |
| **Your Jira email** | The email you sign in to Jira with. |
| **API token** | A Jira API token — see below. |

### Getting a Jira API token

Kong signs in *as you* using an API token (not your password), so anything it
posts — comments, @-mentions, transitions — shows up under your name.

1. Click **Create an API token ↗** on the setup screen (or go to
   <https://id.atlassian.com/manage-profile/security/api-tokens>).
2. Click **Create API token**, give it a label like `Kong`, and copy the value.
3. Paste it into the **API token** field and click **Connect & continue**.

Kong verifies the token against Jira before saving. If it's wrong you'll see a
message and can try again — nothing is stored until it works.

That's it. You'll land on your Kanban board.

> Your details are stored **only on your PC**, in
> `%APPDATA%\Kong\config.local.properties`, and are used only to talk to Jira.
> To change accounts later, open `http://localhost:7070/setup`.

---

## Everyday use

- **Start Kong:** click the **Kong** shortcut in the Start Menu. Your browser
  opens to the board. (Kong keeps running in the background; clicking the
  shortcut again just reopens the tab.)
- **Use it:** the app lives at `http://localhost:7070` — bookmark it if you like.
- **Stop Kong:** it's a lightweight background process. It stops when you sign
  out or restart Windows; to stop it now, end the **Kong** process in Task
  Manager.
- **Uninstall:** *Settings → Apps → Installed apps → Kong → Uninstall.*

---

## Troubleshooting

- **The browser didn't open / "can't reach this page."** Give it a few seconds
  after first launch, then open `http://localhost:7070` yourself.
- **"Port 7070 is already in use."** Kong (or another app) is already using it.
  If it's another app, an admin can change Kong's port in
  `%APPDATA%\Kong\config.local.properties` (`server.port=...`).
- **I mistyped my token / want to switch accounts.** Go to
  `http://localhost:7070/setup` and re-enter your details.
- **Start over completely.** Delete the folder `%APPDATA%\Kong` and relaunch
  Kong — you'll get the setup screen again.

---

## For maintainers — building & publishing

You need this only if you *produce* releases, not to install Kong.

### Prerequisites (build machine, Windows)

- **JDK 21+** (Temurin recommended) with `JAVA_HOME` set — includes `jpackage`.
- **Maven** on `PATH`.
- **WiX Toolset v3** on `PATH` — required by `jpackage` to build `.msi` files.
  (Not needed if you only use the GitHub Actions path below.)

### Build an installer locally

```powershell
powershell -ExecutionPolicy Bypass -File packaging\package.ps1
# → dist\Kong-<version>.msi
```

The version comes from `pom.xml`; override with `-Version 1.2.3`, or reuse an
existing jar with `-SkipBuild`.

### Automated builds (GitHub Releases)

`.github/workflows/release.yml` runs on every `v*` tag (exactly the tags `cpt`
creates). On a Windows runner it builds the MSI and attaches it to the GitHub
Release automatically — no manual `jpackage` step.

```
# cutting a release also produces the installer:
git tag v1.2.3 && git push origin v1.2.3
```

> Both remotes (`cg-fnba/Kong`, `Eichendorn/Kong`) are on GitHub, so a tag pushed
> to each triggers that repo's own workflow. If you don't want two Release builds,
> disable Actions on one repo.

### Also publish to the internal share ("Both")

After the GitHub Release exists (or from a local `dist\` build), mirror the MSI
to the share so coworkers need no GitHub access:

```powershell
# from the latest GitHub release (needs `gh auth login` once):
packaging\publish-to-share.ps1                        # defaults to V:\Encompass\Kong

# ...or publish a locally built installer:
packaging\publish-to-share.ps1 -Msi dist\Kong-1.2.3.msi

# ...or override the destination:
packaging\publish-to-share.ps1 -Share \\server\Encompass\Kong
```

This copies `Kong-<version>.msi` and refreshes a stable `Kong-latest.msi` pointer
that [Method A](#method-a--internal-file-share) tells users to download.
