LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := user

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := AwesomeIME

LOCAL_JNI_SHARED_LIBRARIES := libjni_awesomeime

LOCAL_AAPT_FLAGS := -0 .dict

include $(BUILD_PACKAGE)
include $(LOCAL_PATH)/dictionary/Android.mk
