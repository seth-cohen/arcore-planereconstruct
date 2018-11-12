## ARCore ##
### Project Set up ###
See this [link][enable arcore]

#### Manifest ####
- Must use `minSDKVersion` of 24 in app/build.gradle `defaultConfig`
- Must request permission to use CAMERA
  - additionally, must require feature camera.ar
- Application must require ar.core

#### AR Activity ####
- Must request camera permission `onResume`
- Must implement `onRequestPermissionsResult`
  - and show why we need it unless they've checked not to be asked again
- Check if `ARCore` is installed
 
[enable arcore]: https://developers.google.com/ar/develop/java/enable-arcore
