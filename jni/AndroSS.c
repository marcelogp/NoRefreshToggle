#include <linux/fb.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char * TAG = "AndroSS";
#include "android.h"

#define ERR_BUFFER_SIZE 256
#define STRING_BUFFER_SIZE 128
#define FD_STDOUT 1
#ifdef ANDROID
static const char * FB_PATH = "/dev/graphics/fb0";
#else
static const char * FB_PATH = "/dev/fb0";
#endif


/*
 * I've tried to pack all necessary functionality into this program without the
 * need of arguments so AndroSS only has to be su whitelisted once. Therefore,
 * this program's behavior is determined by the value of the envvar
 * ANDROSS_MODE.
 *      case (TRUE):
 *      return 0. (Useful for su tests and initial whitelisting.)
 *
 *      case (FB_PARAMS):
 *      This binary will query the framebuffer for the following information,
 *      printing it in decimal in this order, separated by spaces:
 *          horizontal resolution
 *          vertical resolution
 *          pixel depth
 *          red offset
 *          red length
 *          green offset
 *          green length
 *          blue offset
 *          blue length
 *          alpha offset
 *          alpha length
 *          stride
 *
 *      case (FB_DATA):
 *      If this has a value, this binary will interpret it as the number of
 *      bytes it should read from the framebuffer and write out.
 */
int writeFBParams(int output_fd, int fb_fd)
{
    // Run the appropriate ioctls to find out what we're dealing with.
    struct fb_fix_screeninfo fb_fixinfo;
    struct fb_var_screeninfo fb_varinfo;
    if (ioctl(fb_fd, FBIOGET_FSCREENINFO, &fb_fixinfo) < 0) {
        LogE("External: fixinfo ioctl failed.");
        close(fb_fd);
        return(1);
    }

    if (ioctl(fb_fd, FBIOGET_VSCREENINFO, &fb_varinfo) < 0) {
        LogE("External: varinfo ioctl failed.");
        close(fb_fd);
        return(1);
    }

    char output_data[STRING_BUFFER_SIZE] = {0};
    sprintf(output_data, "%u %u %u %u %u %u %u %u %u %u %u %u",
            fb_varinfo.xres,
            fb_varinfo.yres,
            fb_varinfo.bits_per_pixel,
            fb_varinfo.blue.offset,
            fb_varinfo.blue.length,
            fb_varinfo.green.offset,
            fb_varinfo.green.length,
            fb_varinfo.red.offset,
            fb_varinfo.red.length,
            fb_varinfo.transp.offset,
            fb_varinfo.transp.length,
            fb_fixinfo.line_length);

    write(output_fd, output_data, STRING_BUFFER_SIZE * sizeof(char));
    return(0);
}


int writeFBData(int output_fd, int fb_fd, int fb_bytes)
{
    void * bytes = malloc(fb_bytes);
    int bytes_read = read(fb_fd, bytes, fb_bytes);
    if (bytes_read < fb_bytes) {
        LogE("External: Only read %d bytes from framebuffer!", bytes_read);
        return 1;
    }
    LogD("External: read %u bytes from framebuffer.", bytes_read);

    int bytes_written = write(output_fd, bytes, fb_bytes);
    if (bytes_written < fb_bytes) {
        LogE("External: Only wrote %d bytes!", bytes_written);
        return 1;
    }
    LogD("External: wrote %u bytes.", bytes_written);

    return 0;
}


int main()
{
    // Find and open the correct framebuffer device.
    int fb_fd = open(FB_PATH, O_RDONLY);
    if (fb_fd < 0) {
        char * errstr = strerror(errno);
        char * errmsg = (char *)calloc(ERR_BUFFER_SIZE, sizeof(char));
        strncpy(errmsg, "External: Could not open framebuffer device: ", 45);
        strncpy(errmsg + 45, errstr, ERR_BUFFER_SIZE - 45);
        LogE(errmsg);
        return(1);
    }

    const char * mode_str = getenv("ANDROSS_MODE");
    if (mode_str == NULL) {
        mode_str = "";
    }

    int ret;
    if (strcmp(mode_str, "FB_PARAMS") == 0) {
        LogD("External: Running in Param mode.");
        ret = writeFBParams(FD_STDOUT, fb_fd);
    } else if (strcmp(mode_str, "FB_DATA") == 0) {
        LogD("External: Running in Data mode.");
        const char * fb_bytes_str = getenv("ANDROSS_FRAMEBUFFER_BYTES");
        int fb_bytes = atoi(fb_bytes_str);
        ret = writeFBData(FD_STDOUT, fb_fd, fb_bytes);
    } else {
        LogD("External: Running in True mode.");
        ret = 0;
    }

    return ret;
}

