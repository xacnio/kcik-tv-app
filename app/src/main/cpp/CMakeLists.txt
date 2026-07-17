cmake_minimum_required(VERSION 3.22.1)
project(kciktvnr C)

# Classic RNNoise (v0.1.1 era, embedded default model) + JNI bridge.
# Float build: do NOT define HAVE_CONFIG_H / FIXED_POINT.
add_library(rnnoise_nr SHARED
        rnnoise/src/celt_lpc.c
        rnnoise/src/denoise.c
        rnnoise/src/kiss_fft.c
        rnnoise/src/pitch.c
        rnnoise/src/rnn.c
        rnnoise/src/rnn_data.c
        rnnoise/src/rnn_reader.c
        rnnoise_jni.c)

target_include_directories(rnnoise_nr PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/include
        ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/src)

target_compile_options(rnnoise_nr PRIVATE
        -O3 -fvisibility=hidden -DNDEBUG
        -Wno-unused -Wno-absolute-value)

find_library(log-lib log)
target_link_libraries(rnnoise_nr ${log-lib})
