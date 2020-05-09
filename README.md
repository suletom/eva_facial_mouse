# Compile (Tested on stock Ubuntu 18.04 desktop)

0. download, install android studio with git support and install make utility (On 18.04: apt install make git)

1. clone the repository: you can use android studio to clone the project from here: [https://github.com/suletom/eva_facial_mouse.git](https://github.com/suletom/eva_facial_mouse.git)

2. download android NDK (android-ndk-r13b [NDK downloads link!](https://developer.android.com/ndk/downloads/older_releases) and install SDK with SDK manager

3. download opencv SDK: OpenCV-android-sdk_2.4.13.6 [OpenCV SDK downloads link!](https://opencv.org/releases/)

4. set android SDK and NDK path in android studio (File -> Project Structure) OR create local.properties file in the project root for example like this:
ndk.dir=/home/xxx/Android/Ndk/android-ndk-r13b
sdk.dir=/home/xxx/Android/Sdk

5. modify eviacam build.gradle -> set the environment variable "OPENCV_ANDROID_SDK" to point to the downloaded OpenCV-android-sdk_2.4.13.6 directory!

6. gradle sync then compile

# Fixes:
- Popup messages ported to new android versions  
- some camera api 2 blocking problems solved
- some screen on/off queueing problems fixed
- Improved handling of device screen rotation
- Extending overlay over on screen navbar to make it clickable

# New Features:
- Added an actvity to provide ability to recover from camera error/loss
- Added swipe and zoom(+/-) functions to the dock bar
- Key event click (from external hardware) with timing settings and swipe action support
- Setting to turn off dwell click and ability to turn back
- Key event click can click on any target not just on actionable elements
- Key event blocking
- Sound on click is now independent from system sound effects
- Hungarian translation

# Comment
My father suffers from a rare disease(GBS). He can move his toe, but nothing else. I made these modifications to support his healing.
After testing the original app i found dwell click not user friendly, thats why i combined a cheap bluetooth remote controller with a massive slipper and made a device that let's him to click on the screen reliably.
Any keyboard mouse or joystick like this can be used to click and of course it's possible to solder any kind of switch to the buttons of these devices too, so the idea can be reused on disabled people using their legs hands or other parts of their body.
![Special mouse slipper](https://github.com/suletom/eva_facial_mouse/special_mouse_slipper.jpg)

I say thanks to the original author and everybody who helped to make the app better!

# Source
originating from cmauri/eva_facial_mouse forked from /space-station/eva_facial_mouse
EVA Facial Mouse is released under GNU/GPL v3.0

# Compile with Following libs
OpenCV-android-sdk_2.4.13.6

## Requirements at the moment

* Mobile phone or tablet
* Android 7 or higher
* Front camera
* Dual-core processor or higher

## Limitations

Due to pre-existing restrictions of the Android platform, there are currently some limitations.

* It cannot be used simultaneously with other applications that make use of the camera.
* For Android versions prior to 5.1 most standard keyboards do not work with EVA, so a basic keyboard is provided. Such a keyboard needs to be manually activated after the installation.
* Does not work with most games. 
* Browsers do not handle properly some actions (we recommend using Google Chrome).
* Applications such as Maps, Earth and Gallery work with restrictions.

For obvious reasons, EVA has not been tested with all devices available on the market. If you find any issues with your device, please, let us know.
* tested on: Samsung Galaxy A3 2017 (A.8), Xiaomi Redmi 8A (A.9), Xiaomi Mi A1 (A.9)

