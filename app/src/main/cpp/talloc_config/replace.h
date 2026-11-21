#ifndef _TALLOC_REPLACE_H
#define _TALLOC_REPLACE_H

#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
#endif

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <strings.h>
#include <errno.h>
#include <limits.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/auxv.h>

#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_VA_COPY 1
#define HAVE_INTPTR_T 1
#define HAVE_UINTPTR_T 1
#define HAVE_STRNLEN 1
#define HAVE_STRNDUP 1

#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 2

#ifndef _PUBLIC_
#define _PUBLIC_ __attribute__((visibility("default")))
#endif

#ifndef _PRINTF_ATTRIBUTE
#define _PRINTF_ATTRIBUTE(a1, a2) __attribute__((format(__printf__, a1, a2)))
#endif

#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif

#ifndef MAX
#define MAX(a, b) ((a) > (b) ? (a) : (b))
#endif

#ifndef discard_const_p
#define discard_const_p(type, ptr) ((type *)((intptr_t)(ptr)))
#endif

#endif /* _TALLOC_REPLACE_H */
