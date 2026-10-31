# ChudPresence Mobile user guide

ChudPresence Mobile can show your current music or video as a Discord activity. It reads media information available on your phone and sends the activity through the Discord app on that phone.

## What you need

- An Android phone running Android 10 or later with ChudPresence Mobile installed.
- The Discord Android app installed and signed in on the same phone.
- A music or video app that provides an Android media session. The details available can vary by app and by what you are playing.

Download the versioned APK from this repository's Releases page and install it on your phone. Android may ask you to allow installs from your chosen source.

## Get started

1. Open ChudPresence Mobile and follow the setup screens. Make sure you're signed in to Discord on your phone.
2. When prompted, choose **Grant media access** and allow notification access for ChudPresence Mobile. This lets it detect active media.
3. On Android 13 or later, allow the reporting notification when prompted. This keeps the reporting control visible while it runs.
4. For more reliable reporting with the screen off, choose **Allow background reporting** when offered and allow the battery setting.
5. Finish setup and tap **Start reporting** on the dashboard.
6. Play music or a video on the phone. The dashboard shows detected media, and your Discord activity updates when media is available.

To stop sharing, open ChudPresence Mobile and tap **Stop reporting**. After restarting your phone, open the app and start reporting again.

## Choose what appears

Open **Settings → Activities** to choose how each app appears on your Discord profile. Open an app's **Activity controls** to enable or disable sharing for that app and adjust options such as showing paused activity, artwork, playback progress, and supported action buttons. Available choices depend on the app and the information it provides.

An app can appear in Activities after ChudPresence detects its media. If you remove an app from the list, it can reappear when its media is detected again.

## Optional connections

Under **Settings → Services**, you can connect Twitch or Crunchyroll to improve the details available for those services. Follow the sign-in prompts in the app. These connections are optional for basic reporting; you can disconnect them in the same section.

If you also use ChudPresence Link on your computer, **Settings → Link** lets you pair the phone and choose which supported app settings to sync. On the computer, open Link and select **Pair phone**. Connect both devices to the same network, enter the address and code shown on the computer, and follow the prompts to select matching apps and settings. Phone media still reports through Discord on the phone; Link is optional.

## If activity doesn't appear

- Confirm Discord is installed and signed in on the same phone, and that reporting is on.
- In **Settings → Access**, check media access and the reporting notification permission. You can also reopen the setup walkthrough there.
- Start playback in the media app, then check that the app is enabled under **Settings → Activities**. Some apps expose less information than others.
- If reporting stops in the background, check the phone's battery settings and allow background reporting for ChudPresence Mobile.
- If Link is unavailable, check the local network and make sure Link is running on the computer. Phone reporting can still work independently.
- Android cannot update an existing installation signed with a different key. If you previously installed a debug build, you may need to uninstall it before installing the release APK; uninstalling removes that installation's app data.
