# Security Policy

## Reporting a Problem

If you find a security problem in Dream OS, please **don't** open a public issue. Report it privately through
GitHub instead: open the [Security tab](https://github.com/DenseCow/dream-os/security) and choose
**Report a vulnerability**.

Please include what's affected (Fire Stick, iPhone or Windows, and the version), how to reproduce it, and what an attacker
could do with it. You'll get a reply as soon as possible, and credit in the fix's release notes if you'd like.

## Supported Versions

Only the [latest release](https://github.com/DenseCow/dream-os/releases/latest) gets security fixes.

## What Counts

Examples of things worth reporting:

- Keys, debrid tokens or Trakt sign-ins leaking out of the device or into logs other apps can read
- The Fire Stick app's local features (Phone Remote, settings transfer between TVs, AirPlay) being reachable or
  controllable by others on the network without the viewer's approval
- The in-app updater accepting an update that isn't the signed, checksummed official release
- Scraper modules being able to escape what the app intends to allow

## Good to Know

- Dream OS has no servers and no accounts of its own. Your keys and sign-ins stay on your device.
- Scraper repositories are code you choose to run. Only add repositories you trust.
