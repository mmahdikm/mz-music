# Security and privacy

This is a source-only snapshot of mz music 0.3.0. Safeguards added in 0.2.1
remain in place. The app has no internet, microphone or Bluetooth-scanning
permission, no analytics and no account system. Music files are read, not edited.

## Safeguards

- Release builds are non-debuggable and exclude the former preview entry point.
- Untrusted media controllers are rejected. Trusted external controllers receive
  basic transport commands, not queue editing or sound-settings access.
- Queue entries are rebuilt from validated, bounded MediaStore audio references.
- Private listening redacts system/car metadata and blocks app screenshots and
  recent-app previews. The authorized in-app library remains visible.
- Clear listening data stops playback and removes the saved queue/position while
  preserving deliberate playlists, favorites and sound settings.
- Backup/device-transfer exclusions are configured. Storage remains app-private.
- Dependency locking and SHA-256 verification are enabled. Packaging checks the
  APK's permissions, non-debuggable flag and local signing certificate pin.
- ReplayGain positive boosts default to a +6 dB cap. Removing that limit or
  switching to Original requires confirmation because volume can increase.

## Limits

Root or a compromised OS can bypass app-private storage. Another device may have
already cached track metadata. Signal limiting is not hearing protection, and
the limiter is sample-peak protection rather than guaranteed true-peak limiting.
Audio decoders and Bluetooth behavior also depend on Android and the device.
Physical-device checks remain pending; see VALIDATION.md.

The development tools use internet access for dependency downloads. The optional
`tools/audit_dependencies.py` sends public Maven coordinates to OSV; it does not
send source, music or signing credentials. Run it deliberately when reviewing
dependency advisories. An absence of advisory matches is not proof of security.

## Reporting

Do not attach signing credentials, music libraries, private device logs or personal
information to public issues. No private reporting service or address is configured
by this source snapshot. Arrange a private channel with the maintainer before
sharing sensitive vulnerability details.
