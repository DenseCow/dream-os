# Contributing to Dream OS

Thanks for wanting to help. Bug reports, device reports, ideas and code are all welcome.

## Reporting Bugs and Device Results

Use [Issues](https://github.com/DenseCow/dream-os/issues/new/choose) and pick a template. Please include:

- Your device model and OS version (Fire Stick: **Settings › My Fire TV › About**; iPhone: **Settings › General ›
  About**)
- The Dream version (Fire Stick: **Settings › Updates**; iPhone: the release you installed)
- What you did, what you expected, and what happened instead. Photos of the screen help a lot.

**Never paste API keys, debrid tokens, account emails or scraper repository addresses** into an issue.
Dream OS has only been tested on one Fire Stick and one iPhone, so reports from other devices, good or bad, are
especially useful.

## Before You Write Code

- Open an issue first for anything bigger than a small fix, so we can agree on the approach.
- Read [BUILDING.md](BUILDING.md) to get both apps building.
- Read the design rulebook, [`firestick/docs/STANDARDS.md`](firestick/docs/STANDARDS.md). It covers the Dream
  Cinema look (black and gray, the rainbow as the only accent), focus and layout rules, wording (Title Case
  labels, plain sentences, no technical terms on screen) and performance budgets. The iPhone app follows the
  same brand, wording and content rules.
- The two apps should behave the same way; only the UI differs. If you change a behavior on one platform, say
  in your pull request whether the other needs the same change.

## Pull Requests

- Keep each pull request focused on one change, with small, targeted edits.
- Describe what changed and how you tested it (device, OS version).
- Never commit keys or signing files: `local.properties`, `secrets.properties`, `Secrets.swift`, keystores and
  `.env` files are git-ignored for a reason.
- Don't add content, sources or scraper repositories to the apps. Dream ships with none.
- Don't add analytics, tracking or any server the developer controls.

## License

By contributing, you agree that your contributions are licensed under the
[GNU General Public License v3.0](LICENSE), like the rest of Dream OS.
