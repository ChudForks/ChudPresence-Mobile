cmake_minimum_required(VERSION 3.22.1)
project(chudpresence_local)

find_package(discord_partner_sdk REQUIRED CONFIG)

add_library(chudpresence_local SHARED local_presence.cpp)
target_compile_features(chudpresence_local PRIVATE cxx_std_17)
target_link_libraries(chudpresence_local PRIVATE discord_partner_sdk::discord_partner_sdk log)
