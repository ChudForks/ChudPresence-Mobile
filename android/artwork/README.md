# Launcher icon

`launcher-icon.png` is the approved, full-resolution launcher artwork. It was
created with the built-in imagegen tool from the Chat Presence extension asset at
`D:\Projects\ChudPresenceSolo-Chromium-Activity-Lab\extension\icons\icon128.png`.
The user approved the preview on October 26, 2026.

The prompt requested the supplied white media-card emblem on a smooth,
full-bleed electric-blue, violet, and pink-magenta diagonal gradient, centered
at about 70% of the square canvas width, with no extra text, symbols, frame,
or pre-rounded outer corners.

Regenerate the packaged bitmap sizes from this approved image on Windows:

```powershell
.\android\tools\Generate-LauncherIcons.ps1
```

The adaptive bitmap places the complete approved square in the middle 72dp of a
108dp layer. Edge colors extend into the outer motion margins, so Android's
launcher masks preserve the approved composition. Both regular and round adaptive
icons use this flattened artwork as their background with a transparent foreground.
Android 13+ also receives a native monochrome media-card vector for themed icons.
The status-bar notification continues to use its existing monochrome resource.

The in-app header uses the original transparent emblem directly from
`D:\Projects\ChudPresenceSolo-Chromium-Activity-Lab\extension\icons\icon128.png`,
copied without changes to `drawable-nodpi/presence_brand.png`. The launcher keeps
the approved gradient artwork.
