# MyGallery V1 : Android app

## What this version does
- Reads photos/videos from Android MediaStore.
- Appears in the Android Share sheet for one or many images/videos.
- Receives a selection from Gallery/Google Photos/Files/other apps.
- Keeps the original filename by default.
- Lets the user rename one/many files before saving.
- Lets the user choose or create a MyGallery folder.
- For existing MediaStore media, it attempts to organize by changing RELATIVE_PATH/DISPLAY_NAME, which Android supports for moving/renaming media.
- For non-MediaStore shared URIs, it copies the content into MyGallery.
- Uses shared phone storage/MediaStore; no server and no Room/database.

## Important Android behavior
The app cannot force WhatsApp, Camera, Chrome, etc. to save through MyGallery. The supported integration is the Android Share sheet plus MediaStore. Android documents ACTION_SEND_MULTIPLE for receiving multiple URIs, and MediaStore documents RELATIVE_PATH/DISPLAY_NAME for organizing/updating media.

## Open in Android Studio
1. Install Android Studio.
2. File > Open > select this project folder.
3. Let Gradle sync.
4. Connect an Android 10+ phone or start an emulator.
5. Run the app.
6. Grant photo/video access when Android asks.

## Test
1. Open Gallery.
2. Select several photos.
3. Share > MyGallery.
4. Choose a folder or create one.
5. Leave "Keep original names" selected, or choose "Rename selected".
6. Tap Organize.
7. Open MyGallery and search/browse the folder.

## Notes
This is a V1 foundation. Android may require user approval for modifying media owned by other apps on some versions/devices. The implementation catches failures rather than deleting originals. It never silently deletes the source.


## V1.1 Gallery UI update
- 4 tabs: All, Photos, Videos, Folders
- 3/4-column thumbnail gallery instead of filename lists
- Tap any media tile for Open, Share, Rename, Move, Details and Delete
- Share/import screen previews selected media and supports per-file filename editing
- Folder picker uses folders already present in MyGallery and can create/use a new folder name
- Import action is pinned above the Android navigation area and uses navigationBarsPadding/imePadding so it is not clipped on phones
- Added Coil for MediaStore thumbnails
- App icon is `app/src/main/res/drawable/mygallery_icon.xml`; replace it with your own `mygallery_icon.png` when you have your icon ready (remove the XML first).
