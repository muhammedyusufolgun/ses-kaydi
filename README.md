# Ses-kayit

Android app that records microphone audio to MP3 while the user has a call on speakerphone.

## Use
1. Install and grant microphone permission.
2. During a call, turn on speakerphone.
3. Open Ses-kayit and tap Kaydı başlat. A persistent notification is shown while recording.
4. Tap Kaydı bitir when done.
5. Play, rename, export, or delete recordings. Delete requires two confirmations.

Recordings are stored in the app's private Music/Ses-kayit folder. İndir opens Android's save-location picker and saves an MP3 copy.

## Device limitation
This app records microphone audio, not the protected telephony audio stream. Android may silence ordinary apps while a cellular call is active. Speakerphone may help only on devices that permit microphone capture during calls. Test with a short call before relying on a recording. Two-sided recording cannot be guaranteed across Android devices.

## Build
GitHub Actions builds a debug APK on pushes to main and publishes Ses-kayit.apk as a downloadable workflow artifact.
