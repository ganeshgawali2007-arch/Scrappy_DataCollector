# Placeholder for future native adapters (P7 whisper.cpp, P10 llama.cpp).
# P1 skeleton: builds an empty stub so the CMake/NDK path is verified early.
cmake_minimum_required(VERSION 3.22.1)
project(scraper_native)
add_library(scraper_native SHARED native-lib.cpp)
find_library(log-lib log)
target_link_libraries(scraper_native ${log-lib})
