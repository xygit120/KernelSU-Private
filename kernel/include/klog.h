#ifndef __KSU_H_KLOG
#define __KSU_H_KLOG

#include <linux/printk.h>

#ifdef pr_fmt
#undef pr_fmt
#define pr_fmt(fmt) "KernelSU: " fmt
#endif

/*
 * Silent mode: KernelSU never emits any kernel log.
 * All pr_* calls below are compiled out at build time (arguments are still
 * type-checked by no_printk, so the code stays correct, just silent).
 */
#undef pr_emerg
#define pr_emerg(fmt, ...) no_printk(fmt, ##__VA_ARGS__)
#undef pr_alert
#define pr_alert(fmt, ...) no_printk(fmt, ##__VA_ARGS__)
#undef pr_crit
#define pr_crit(fmt, ...) no_printk(fmt, ##__VA_ARGS__)
#undef pr_err
#define pr_err(fmt, ...) no_printk(fmt, ##__VA_ARGS__)
#undef pr_warning
#define pr_warning(fmt, ...) no_printk(fmt, ##__VA_ARGS__)
#undef pr_warn
#define pr_warn(fmt, ...) no_printk(fmt, ##__VA_ARGS__)
#undef pr_notice
#define pr_notice(fmt, ...) no_printk(fmt, ##__VA_ARGS__)
#undef pr_info
#define pr_info(fmt, ...) no_printk(fmt, ##__VA_ARGS__)
#undef pr_debug
#define pr_debug(fmt, ...) no_printk(fmt, ##__VA_ARGS__)

#endif
