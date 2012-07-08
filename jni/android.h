#ifdef ANDROID
#include <android/log.h>
#define LogV(msg, ...) __android_log_print(ANDROID_LOG_VERBOSE, TAG, msg, ##__VA_ARGS__)
#define LogD(msg, ...) __android_log_print(ANDROID_LOG_DEBUG, TAG, msg, ##__VA_ARGS__)
#define LogI(msg, ...) __android_log_print(ANDROID_LOG_INFO, TAG, msg, ##__VA_ARGS__)
#define LogW(msg, ...) __android_log_print(ANDROID_LOG_WARN, TAG, msg, ##__VA_ARGS__)
#define LogE(msg, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, msg, ##__VA_ARGS__)
#define LogF(msg, ...) __android_log_print(ANDROID_LOG_FATAL, TAG, msg, ##__VA_ARGS__)
#else
#define LogV(msg, ...) fprintf(stdout, msg, ##__VA_ARGS__); fprintf(stdout, "\n")
#define LogD(msg, ...) fprintf(stdout, msg, ##__VA_ARGS__); fprintf(stdout, "\n")
#define LogI(msg, ...) fprintf(stdout, msg, ##__VA_ARGS__); fprintf(stdout, "\n")
#define LogW(msg, ...) fprintf(stdout, msg, ##__VA_ARGS__); fprintf(stdout, "\n")
#define LogE(msg, ...) fprintf(stderr, msg, ##__VA_ARGS__); fprintf(stdout, "\n")
#define LogF(msg, ...) fprintf(stderr, msg, ##__VA_ARGS__); fprintf(stdout, "\n")
#endif

