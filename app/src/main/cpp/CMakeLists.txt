cmake_minimum_required(VERSION 3.22.1)
project(anct_terminal C)

add_library(termux SHARED termux.c)
target_compile_options(termux PRIVATE -Wall -Wextra -Werror -O2)
target_link_options(termux PRIVATE -Wl,-z,max-page-size=16384)

