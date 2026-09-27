APP_ABI := armeabi-v7a arm64-v8a
#armeabi-v7a arm64-v8a x86 x86_64
# NDK r27 起最低支持 API 21，android-16 不再可用
APP_PLATFORM := android-21
#APP_STL := gnustl_shared
#APP_STL := stlport_static
APP_STL :=c++_shared
APP_CXXFLAGS += -frtti
