LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
	info_kanru_inputmethod_awesome_BinaryDictionary.cpp \
	dictionary.cpp
LOCAL_LDLIBS := -lm -llog
LOCAL_MODULE := libjni_awesomeime

include $(BUILD_SHARED_LIBRARY)
