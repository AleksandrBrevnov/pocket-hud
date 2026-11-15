# Recording filenames: 1.0.55

New Live WAV parts start with the smartphone's readable local date/time, e.g.
`record_2026-11-15_14-18-36_<session-id>_0007.wav`. No time-zone suffix is shown.
The unique session ID and part index remain; old files are not renamed.

Each part timestamp is calculated from archive creation time plus the exact
duration of PCM already archived (32000 bytes/second). This describes the
recording sample timeline; gaps in microphone capture are not reconstructed.
The device's time zone at archive creation is used, with daylight-saving rules
applied for each part's instant.

Parts remain capped at 600 seconds / 19200000 PCM bytes. This is an application
setting, not a Google API or WAV ten-minute restriction. Recording automatically
continues in the next part. Smaller files are easier to export and inspect;
changing the segmentation policy is outside this filename change.

Runtime tests write actual WAV parts, check their names and preserved PCM,
and cover the Europe/Rome daylight-saving transition. The rest of recording,
translation, notification and summary behavior is unchanged.
