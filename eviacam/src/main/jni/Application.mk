APP_ABI := armeabi-v7a
APP_STL := c++_static
APP_CPPFLAGS := -frtti -fexceptions
APP_PLATFORM := android-19
ifeq ($(BUILD_CONFIG),DEBUG)
	APP_OPTIM := debug
else 
	APP_OPTIM := release
endif