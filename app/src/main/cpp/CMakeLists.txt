cmake_minimum_required(VERSION 3.22.1)
project(karindsp C)

add_library(karindsp SHARED
    karindsp.c
    karindsp_jni.c
)

target_compile_options(karindsp PRIVATE -O3)

target_link_libraries(karindsp m)

# NEON para procesadores viejos (armeabi-v7a). En arm64-v8a ya viene activado
# por defecto; aqui se activa el juego de instrucciones NEON en 32 bits.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(karindsp PRIVATE -mfpu=neon)
endif()
