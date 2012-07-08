#include <linux/ioctl.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "android.h"
#define MAX_INFO_BYTES 127
#define MAX_CMD_LEN 255
#define MAX_BYTES_DIGITS 16

#include "bitwork.h"


static const char * TAG = "AndroSS";
static const char * MODE_ENVVAR = "ANDROSS_MODE";
static const char * FB_BYTES_ENVVAR = "ANDROSS_FRAMEBUFFER_BYTES";
static const ssize_t TEGRA_SKIP_BYTES = 52;
// These MUST be kept consistent with the enum DeviceType in AndroSSService!
static const int TYPE_GENERIC = 1;
static const int TYPE_TEGRA = 2;


jint JNI_OnLoad(JavaVM * vm, void * reserved) {
    JNIEnv * env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    } else {
        return JNI_VERSION_1_6;
    }
}


/**
 * Transform a command line into an argv suitable for execForOutput.
 *
 * @param command - The full command string from Dalvikspace.
 * @return A null-terminated char *[] to give to execForOutput.
 */
static inline char ** mkargv(char * command) {
    char ** argv = NULL;
    int parts = 0;
    char * next_space = command;
    while (next_space != NULL) {
        ++parts;
        next_space = strchr(next_space + 1, ' ');
    }

    argv = (char **)calloc(parts + 1, sizeof(char *));
    // strtok will helpfully provide a null to terminate the array.
    for (int i = 0; i <= parts; ++i) {
        *(argv + i) = strtok(i == 0 ? command : NULL, " ");
    }

    return argv;
}



/**
 * @param argv - To be passed directly to execv. argv[0] must be the full path
 *      to the binary you want to execute.
 * @param output_buf - The buffer in which to store the child process's output.
 * @param buf_len - To be passed to read(). Passing 0 for this and toss_bytes
 *      will result in no accesses being made to output_buf, making it safe to
 *      pass NULL.
 * @param fd - The fd on the child process from which to capture output. 1 and 2
 *      are probably the only values that make sense.
 * @param toss_bytes - Bytes to skip at the beginning of the child process's
 *      output. It is unsafe for this to be greater than the size of your
 *      buffer.
 */
static inline int execForOutput(char ** argv, uint8_t * output_buf, int buf_len,
        int fd, int toss_bytes) {
    int pipefd[2];
    pipe(pipefd);

    LogD("NBridge: Reading fd %d from %s with args:", fd, argv[0]);
    char ** arg = argv + 1;
    while (*arg != NULL) {
        LogD("\t%s", *arg);
        ++arg;
    }

    int cpid = fork();
    if (cpid == 0) {
        close(pipefd[0]);
        dup2(pipefd[1], fd);
        execv(argv[0], argv);
    }

    close(pipefd[1]);
    if (toss_bytes > 0) {
        int bytes_tossed = read(pipefd[0], output_buf, toss_bytes);
        if (bytes_tossed != toss_bytes) {
            LogE("NBridge: Error skipping junk! Only tossed %d bytes.",
                    bytes_tossed);
            return -1;
        } else {
            LogD("NBridge: Skipped %d bytes.", bytes_tossed);
        }
    }

    int bytes_read = 0;
    int reads = 0;
    int child_status = -1;
    while (bytes_read < buf_len) {
        int bytes_read_this_time = read(pipefd[0], output_buf + bytes_read,
                buf_len - bytes_read);
        if (bytes_read_this_time < 0) {
            char * errmsg = strerror(errno);
            LogE("NBridge: Error while reading from subprocess:");
            LogE(errmsg);
            return 255;
        }

        if (bytes_read_this_time == 0) {
            break;
        }
        bytes_read += bytes_read_this_time;
        reads++;
    }
    LogD("NBridge: Read %d of %d bytes from subprocess with %d reads.", bytes_read, buf_len, reads);
    close(pipefd[0]);
    return waitpid(cpid, &child_status, 0);
}


/*
 * Retrieve the physical parameters of the framebuffer. With appropriate
 * arguments, this function works for all device types.
 *
 * @param type Device type. Consistent with AndroSSService.DeviceType.
 * @param command_j The full command to run.
 * @return A string describing the parameters of the framebuffer. See AndroSS.c
 *  for the ABI.
 */
jstring Java_net_tedstein_AndroSS_AndroSS_getFBInfo(
        JNIEnv * env, jobject this,
        jint type,
        jstring command_j) {
    if (type == TYPE_GENERIC) {
        LogD("NBridge: Getting info on a generic device.");
    } else if (type == TYPE_TEGRA) {
        LogD("NBridge: Getting info on a Tegra device.");
    } else {
        LogE("NBridge: What the hell am I getting info on?! Got type %d", type);
        return 0;
    }

    const char * command_const = (*env)->GetStringUTFChars(env, command_j, 0);
    LogD("NBridge: Got command %s", command_const);
    char * command = (char *)calloc(strlen(command_const) + 1, sizeof(char));
    strncpy(command, command_const, strlen(command_const));
    char ** argv = mkargv(command);
    char * buf = (char *)calloc(MAX_INFO_BYTES + 1, sizeof(char));

    int fd = 1;
    if (type == TYPE_GENERIC) {
        setenv(MODE_ENVVAR, "FB_PARAMS", 1);
    } else if (type == TYPE_TEGRA) {
        fd = 2; // fbread spits out params on stderr.
    }

    execForOutput(argv, (uint8_t *)buf, MAX_INFO_BYTES, fd, 0);
    LogD("NBridge: Got param string: %s", buf);
    return (*env)->NewStringUTF(env, buf);
}



/*
 * Retrieve the pixels. With appropriate arguments, this function works for all
 * device types.
 *
 * @param type Device type. Consistent with AndroSSService.DeviceType.
 * @param command_j The full command to run.
 * @param height Framebuffer height.
 * @param width Framebuffer width.
 * @param bpp Framebuffer depth in bytes per pixel.
 * @param stride Framebuffer stride.
 * @param offsets_j Color offsets within each pixel.
 * @param sizes_j Color sizes within each pixel.
 * @return A (width * height) array of ARGB_8888 pixels.
 */
jintArray Java_net_tedstein_AndroSS_AndroSS_getFBPixels(
        JNIEnv * env, jobject this,
        jint type,
        jstring command_j,
        jint height, jint width, jint bpp, jint stride,
        jintArray offsets_j, jintArray sizes_j) {
    if (type == TYPE_GENERIC) {
        LogD("NBridge: Getting pixels on a generic device.");
    } else if (type == TYPE_TEGRA) {
        LogD("NBridge: Getting pixels on a Tegra device.");
    } else {
        LogE("NBridge: What the hell am I getting pixels on?! Got type %d",
                type);
        return 0;
    }

    // Extract color offsets and sizes from the Java array types.
    int offsets[4], sizes[4];
    (*env)->GetIntArrayRegion(env, offsets_j, 0, 4, offsets);
    (*env)->GetIntArrayRegion(env, sizes_j, 0, 4, sizes);
    const char * command_const = (*env)->GetStringUTFChars(env, command_j, 0);
    char * command = (char *)calloc(strlen(command_const) + 1, sizeof(char));
    strncpy(command, command_const, strlen(command_const));

    // Allocate enough space to store all pixels in ARGB_8888. We'll initially
    // put the pixels at the highest address within our buffer they can fit.
    int pixbuf_size = height * ((stride > width * 4) ? stride : width * 4);
    uint8_t * pixbuf = malloc(pixbuf_size);
    unsigned int pixbuf_offset = pixbuf_size - (stride * height);

    if (type == TYPE_GENERIC) {
        char bytes_str[MAX_BYTES_DIGITS];
        sprintf(bytes_str, "%u", stride * height);

        // Tell the external binary to read the framebuffer and how many bytes we want.
        setenv(MODE_ENVVAR, "FB_DATA", 1);
        setenv(FB_BYTES_ENVVAR, bytes_str, 1);
    }

    // And then slurp the data.
    char ** argv = mkargv(command);
    execForOutput(argv, pixbuf + pixbuf_offset, stride * height, 1,
        type == TYPE_TEGRA ? TEGRA_SKIP_BYTES : 0);

    // Convert all of the pixels to ARGB_8888 according to the parameters passed
    // in from Dalvikspace. To save space and time, we do this in-place. If each
    // pixel is fewer than four bytes, this involves shifting data like this:
    // (lower addresses to the left, r = raw, f = formatted, two bytes per char)
    // < -- -- -- -- r1 r2 r3 r4 >
    // < f1 f1 -- -- r1 r2 r3 r4 >
    // < f1 f1 f2 f2 r1 r2 r3 r4 >
    // < f1 f1 f2 f2 f3 f3 r3 r4 >
    // < f1 f1 f2 f2 f3 f3 f4 f4 >
    int pixels = width * height;
    LogD("NBridge: Converting %u pixels.", pixels);
    struct timeval start_tv, end_tv;
    gettimeofday(&start_tv, NULL);

    uint8_t * unformatted_pixels = pixbuf + pixbuf_offset;
    for (int i = 0; i < height; ++i) {
        uint8_t * unformatted_line = unformatted_pixels + (i * stride);
        for (int j = 0; j < width; ++j) {
            uint32_t pix = extractPixel(unformatted_line, j, bpp);
            *(((uint32_t *)pixbuf) + (i * width) + j) = formatPixel(pix, offsets, sizes);
        }
    }

    gettimeofday(&end_tv, NULL);
    int seconds = end_tv.tv_sec - start_tv.tv_sec;
    int useconds = end_tv.tv_usec - start_tv.tv_usec;
    LogD("NBridge: Conversion finished in %u ms.", (seconds * 1000) + (useconds / 1000));


    // Finally, cast pixbuf as an jint[] and convert it to a jintArray we can
    // return to Java.
    jintArray ret = (*env)->NewIntArray(env, pixels);
    (*env)->SetIntArrayRegion(env, ret, 0, pixels, (jint *)pixbuf);
    free(pixbuf);
    LogD("NBridge: Returning data.");
    return ret;
}

