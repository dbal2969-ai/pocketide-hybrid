# Builds libpocketide_ts.so: tree-sitter (MIT) with the JavaScript, Python, Java and Kotlin grammars (MIT), and PocketIDE's bridge.
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := pocketide_ts
LOCAL_CFLAGS := -w -O2 -DNDEBUG
LOCAL_C_INCLUDES := $(LOCAL_PATH)/tree-sitter/lib/include $(LOCAL_PATH)/tree-sitter/lib/src \
    $(LOCAL_PATH)/grammar/javascript $(LOCAL_PATH)/grammar/python $(LOCAL_PATH)/grammar/java $(LOCAL_PATH)/grammar/kotlin
LOCAL_SRC_FILES := tsbridge.c tree-sitter/lib/src/lib.c \
    grammar/javascript/parser.c grammar/javascript/scanner.c \
    grammar/python/parser.c grammar/python/scanner.c \
    grammar/java/parser.c \
    grammar/kotlin/parser.c grammar/kotlin/scanner.c
include $(BUILD_SHARED_LIBRARY)
