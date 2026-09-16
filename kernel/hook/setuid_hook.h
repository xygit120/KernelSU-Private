#ifndef __KSU_H_KSU_CORE
#define __KSU_H_KSU_CORE

#include <linux/init.h>
#include <linux/types.h>

// Handler functions for hook_manager
int ksu_handle_setresuid(uid_t old_uid, uid_t new_uid);

#endif
