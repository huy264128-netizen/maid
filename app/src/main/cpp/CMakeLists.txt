cmake_minimum_required(VERSION 3.22.1)
project(termux_proot C ASM)

# ==============================================================================
# 1. Static libtalloc (required by Termux PRoot)
# ==============================================================================
add_library(talloc STATIC
    talloc/talloc.c
)

target_include_directories(talloc PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/talloc
    ${CMAKE_CURRENT_SOURCE_DIR}/talloc_config
)

target_compile_options(talloc PRIVATE
    -O2
    -fPIC
    -Wno-unused-parameter
    -Wno-sign-compare
)

# ==============================================================================
# 2. PRoot freestanding Loader (packaged as libproot_loader.so in nativeLibraryDir)
# ==============================================================================
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(PROOT_LOADER_ADDRESS "0x5400000000")
    set(PROOT_LOADER_ARCH_CFLAGS "")
    set(PROOT_HAS_POKEDATA_WORKAROUND TRUE)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(PROOT_LOADER_ADDRESS "0x20000000")
    set(PROOT_LOADER_ARCH_CFLAGS "")
    set(PROOT_HAS_POKEDATA_WORKAROUND FALSE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(PROOT_LOADER_ADDRESS "0x600000000000")
    set(PROOT_LOADER_ARCH_CFLAGS "")
    set(PROOT_HAS_POKEDATA_WORKAROUND FALSE)
elseif(ANDROID_ABI STREQUAL "x86")
    set(PROOT_LOADER_ADDRESS "0xa0000000")
    set(PROOT_LOADER_ARCH_CFLAGS "-mregparm=3")
    set(PROOT_HAS_POKEDATA_WORKAROUND FALSE)
else()
    message(FATAL_ERROR "Unsupported ANDROID_ABI for PRoot: ${ANDROID_ABI}")
endif()

add_executable(proot_loader
    proot/src/loader/loader.c
    proot/src/loader/assembly.S
)

set_target_properties(proot_loader PROPERTIES
    OUTPUT_NAME "libproot_loader.so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)

target_include_directories(proot_loader PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/proot_config
    ${CMAKE_CURRENT_SOURCE_DIR}/proot/src
)

target_compile_options(proot_loader PRIVATE
    -fPIC
    -ffreestanding
    -O2
    -D_FILE_OFFSET_BITS=64
    -D_GNU_SOURCE
)
if(PROOT_LOADER_ARCH_CFLAGS)
    target_compile_options(proot_loader PRIVATE ${PROOT_LOADER_ARCH_CFLAGS})
endif()

target_link_options(proot_loader PRIVATE
    -static
    -nostdlib
    -Wl,--no-gc-sections,--build-id=none,-Ttext=${PROOT_LOADER_ADDRESS},--rosegment,-z,noexecstack,-z,max-page-size=16384
)
if(PROOT_HAS_POKEDATA_WORKAROUND)
    target_link_options(proot_loader PRIVATE -Wl,-u,pokedata_workaround)
endif()

# ==============================================================================
# 3. Generate loader-info.c on arm64-v8a (computes offset_to_pokedata_workaround)
# ==============================================================================
set(PROOT_GENERATED_SOURCES "")

if(PROOT_HAS_POKEDATA_WORKAROUND)
    set(LOADER_INFO_C "${CMAKE_CURRENT_BINARY_DIR}/loader-info.c")
    add_custom_command(
        OUTPUT "${LOADER_INFO_C}"
        COMMAND "${CMAKE_NM}" "$<TARGET_FILE:proot_loader>" > "${CMAKE_CURRENT_BINARY_DIR}/loader.syms"
        COMMAND "${CMAKE_COMMAND}"
            -DSYMS_FILE=${CMAKE_CURRENT_BINARY_DIR}/loader.syms
            -DOUT_FILE=${LOADER_INFO_C}
            -P "${CMAKE_CURRENT_SOURCE_DIR}/generate_loader_info.cmake"
        DEPENDS proot_loader "${CMAKE_CURRENT_SOURCE_DIR}/generate_loader_info.cmake"
        COMMENT "Generating loader-info.c (offset_to_pokedata_workaround) from proot_loader"
        VERBATIM
    )
    list(APPEND PROOT_GENERATED_SOURCES "${LOADER_INFO_C}")
endif()

# ==============================================================================
# 4. Termux PRoot main executable (packaged as libproot.so in nativeLibraryDir)
# ==============================================================================
set(PROOT_SOURCES
    proot/src/cli/cli.c
    proot/src/cli/proot.c
    proot/src/cli/note.c
    proot/src/execve/enter.c
    proot/src/execve/exit.c
    proot/src/execve/shebang.c
    proot/src/execve/elf.c
    proot/src/execve/ldso.c
    proot/src/execve/auxv.c
    proot/src/execve/aoxp.c
    proot/src/path/binding.c
    proot/src/path/glue.c
    proot/src/path/canon.c
    proot/src/path/f2fs-bug.c
    proot/src/path/path.c
    proot/src/path/proc.c
    proot/src/path/temp.c
    proot/src/syscall/seccomp.c
    proot/src/syscall/syscall.c
    proot/src/syscall/chain.c
    proot/src/syscall/enter.c
    proot/src/syscall/exit.c
    proot/src/syscall/sysnum.c
    proot/src/syscall/socket.c
    proot/src/syscall/heap.c
    proot/src/syscall/rlimit.c
    proot/src/syscall/pipe_shadow.c
    proot/src/tracee/tracee.c
    proot/src/tracee/mem.c
    proot/src/tracee/reg.c
    proot/src/tracee/event.c
    proot/src/tracee/seccomp.c
    proot/src/tracee/statx.c
    proot/src/ptrace/ptrace.c
    proot/src/ptrace/user.c
    proot/src/ptrace/wait.c
    proot/src/extension/extension.c
    proot/src/extension/ashmem_memfd/ashmem_memfd.c
    proot/src/extension/kompat/kompat.c
    proot/src/extension/fake_id0/chown.c
    proot/src/extension/fake_id0/chroot.c
    proot/src/extension/fake_id0/getsockopt.c
    proot/src/extension/fake_id0/sendmsg.c
    proot/src/extension/fake_id0/socket.c
    proot/src/extension/fake_id0/open.c
    proot/src/extension/fake_id0/unlink.c
    proot/src/extension/fake_id0/rename.c
    proot/src/extension/fake_id0/chmod.c
    proot/src/extension/fake_id0/utimensat.c
    proot/src/extension/fake_id0/access.c
    proot/src/extension/fake_id0/exec.c
    proot/src/extension/fake_id0/link.c
    proot/src/extension/fake_id0/symlink.c
    proot/src/extension/fake_id0/mk.c
    proot/src/extension/fake_id0/stat.c
    proot/src/extension/fake_id0/helper_functions.c
    proot/src/extension/fake_id0/fake_id0.c
    proot/src/extension/hidden_files/hidden_files.c
    proot/src/extension/mountinfo/mountinfo.c
    proot/src/extension/port_switch/port_switch.c
    proot/src/extension/sysvipc/sysvipc.c
    proot/src/extension/sysvipc/sysvipc_msg.c
    proot/src/extension/sysvipc/sysvipc_sem.c
    proot/src/extension/sysvipc/sysvipc_shm.c
    proot/src/extension/link2symlink/link2symlink.c
    proot/src/extension/fix_symlink_size/fix_symlink_size.c
)

add_executable(proot
    ${PROOT_SOURCES}
    ${PROOT_GENERATED_SOURCES}
)

add_dependencies(proot proot_loader)

set_target_properties(proot PROPERTIES
    OUTPUT_NAME "libproot.so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)

target_include_directories(proot PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/proot_config
    ${CMAKE_CURRENT_SOURCE_DIR}/proot/src
    ${CMAKE_CURRENT_SOURCE_DIR}/talloc
    ${CMAKE_CURRENT_SOURCE_DIR}/talloc_config
)

target_compile_options(proot PRIVATE
    -O2
    -fPIE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-missing-field-initializers
    -D_FILE_OFFSET_BITS=64
    -D_GNU_SOURCE
    "-DPROOT_UNBUNDLE_LOADER=\".\""
    -DNO_LOADER_32BIT
)

target_link_libraries(proot PRIVATE
    talloc
)

target_link_options(proot PRIVATE
    -pie
    -Wl,-z,noexecstack,-z,max-page-size=16384
)
