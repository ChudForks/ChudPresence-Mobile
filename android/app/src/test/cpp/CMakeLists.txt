cmake_minimum_required(VERSION 3.22.1)
project(chudpresence_native_tests LANGUAGES CXX)

enable_testing()
add_executable(utf8_test utf8_test.cpp)
target_compile_features(utf8_test PRIVATE cxx_std_17)
add_test(NAME utf16_to_utf8 COMMAND utf8_test)
