# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.
cmake_minimum_required(VERSION 3.18.1)

# Declares and names the project.
project("ft8cn")

# ============================================================================
# === ОБЩИЕ НАСТРОЙКИ (используются обеими библиотеками) ===
# ============================================================================
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/common
    ${CMAKE_CURRENT_SOURCE_DIR}/fft
    ${CMAKE_CURRENT_SOURCE_DIR}/ft8_std
    ${CMAKE_CURRENT_SOURCE_DIR}/ft8_dx
)

# Общие файлы (одинаковые для обеих библиотек)
# ВАЖНО: Эти файлы-обёртки используют #ifdef FT8_DX_MODE для условной компиляции
set(COMMON_SOURCES
    # FFT библиотеки
    fft/kiss_fft.c
    fft/kiss_fftr.c

    # Общие утилиты
    common/wave.c

    # JNI wrappers и высокоуровневая логика
    # Эти файлы содержат #ifdef FT8_DX_MODE для выбора реализации
    ft8Decoder.c
    ft8Encoder.c
    generate_ft8.cpp
    ft8Listener.cpp
    ft8Spectrum.cpp
    monitor_opr.c
    spectrum_data.c
)

# ============================================================================
# === СТАНДАРТНАЯ БИБЛИОТЕКА (стабильная, без изменений) ===
# ============================================================================
add_library(ft8cn_std SHARED
    # Core FT8 implementation (standard version)
    ft8_std/constants.c
    ft8_std/crc.c
    ft8_std/decode.c
    ft8_std/encode.c
    ft8_std/hash22.c
    ft8_std/ldpc.c
    ft8_std/pack.c
    ft8_std/text.c
    ft8_std/unpack.c

    # Общие файлы (wrapper'ы, которые подхватят стандартную реализацию)
    ${COMMON_SOURCES}
)

# Линковка стандартной библиотеки
target_link_libraries(ft8cn_std log android m)

# ============================================================================
# === DX-БИБЛИОТЕКА (с поддержкой 76-го бита, multistream) ===
# ============================================================================
add_library(ft8cn_dx SHARED
    # Core FT8 DX implementation (modified for multistream/76-bit)
    ft8_dx/constants.c
    ft8_dx/crc.c
    ft8_dx/decode.c      # <-- Поддержка парсинга 76-го бита
    ft8_dx/encode.c      # <-- Установка 76-го бита при кодировании
    ft8_dx/hash22.c
    ft8_dx/ldpc.c
    ft8_dx/pack.c        # <-- Упаковка мульти-сообщений
    ft8_dx/text.c
    ft8_dx/unpack.c      # <-- Распаковка мульти-сообщений

    # Общие файлы (те же wrapper'ы, но с флагом FT8_DX_MODE)
    ${COMMON_SOURCES}
)

# Флаг для условной компиляции в коде
# В .c/.cpp файлах используйте: #ifdef FT8_DX_MODE ... #endif
target_compile_definitions(ft8cn_dx PRIVATE FT8_DX_MODE=1)

# Линковка DX-библиотеки
target_link_libraries(ft8cn_dx log android m)

# ============================================================================
# === ОПТИМИЗАЦИИ КОМПИЛЯЦИИ (опционально) ===
# ============================================================================
# Добавляем флаги оптимизации для release-сборок
# (CMake автоматически применяет их при сборке Release)
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_compile_options(ft8cn_std PRIVATE -O3 -ffast-math)
    target_compile_options(ft8cn_dx PRIVATE -O3 -ffast-math)
endif()

# ============================================================================
# === ПРОВЕРКА СУЩЕСТВОВАНИЯ ФАЙЛОВ (для отладки) ===
# ============================================================================
# Раскомментируйте эти строки, если хотите видеть предупреждения при отсутствии файлов
# if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/ft8_std/decode.c")
#     message(WARNING "ft8_std/decode.c not found! Check your source paths.")
# endif()
# if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/ft8_dx/decode.c")
#     message(WARNING "ft8_dx/decode.c not found! Check your source paths.")
# endif()