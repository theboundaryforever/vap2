APP_PLATFORM := android-24
APP_ABI := arm64-v8a armeabi-v7a
APP_STL := c++_shared
APP_OPTIM := release
APP_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
