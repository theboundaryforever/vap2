LOCAL_PATH := $(call my-dir)
# Prebuilt .so files are committed under local-maven-tmp; headers live next to this makefile.
FFMPEG_LIBS := $(LOCAL_PATH)/../../../../local-maven-tmp/ffmpeg-lite/jni/$(TARGET_ARCH_ABI)
FFMPEG_INCLUDE := $(LOCAL_PATH)/ffmpeg-include
FFMPEG_PREFIX := $(LOCAL_PATH)/../../../../.ffmpeg-lite-build/prefix/$(TARGET_ARCH_ABI)

include $(CLEAR_VARS)
LOCAL_MODULE := avutil
LOCAL_SRC_FILES := $(FFMPEG_LIBS)/libavutil.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := avcodec
LOCAL_SRC_FILES := $(FFMPEG_LIBS)/libavcodec.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := avformat
LOCAL_SRC_FILES := $(FFMPEG_LIBS)/libavformat.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := swscale
LOCAL_SRC_FILES := $(FFMPEG_LIBS)/libswscale.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := ffmpeglite_jni
LOCAL_SRC_FILES := lite_ffmpeg_jni.cpp
LOCAL_C_INCLUDES := $(FFMPEG_INCLUDE) $(FFMPEG_PREFIX)/include
LOCAL_SHARED_LIBRARIES := avformat avcodec avutil swscale
LOCAL_LDLIBS := -llog -landroid -lz -lm
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
LOCAL_CPPFLAGS := -std=c++17 -fexceptions -frtti
include $(BUILD_SHARED_LIBRARY)
