package net.tedstein.AndroSS;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Base64;
import android.util.Log;

public class AndroSS {
    static {
        System.loadLibrary("AndroSS_nbridge");
    }
    private final Context context;

    public static enum CompressionType {PNG, JPG_HQ, JPG_FAST};
    public static enum DeviceType { UNKNOWN, GENERIC, TEGRA_2 };

    private static final String TAG = "AndroSS";
    private static final String DEFAULT_OUTPUT_DIR = "/sdcard/screenshots";
    private static final String DEFAULT_SU_PATH = "/system/bin/su";
    // Phone graphical parameters and fixed config.
    private static int screen_width;
    private static int screen_height;
    private static int screen_depth;
    private static int fb_stride;
    // Color order: [r, g, b, a]
    private static int[] c_offsets;
    private static int[] c_sizes;
    private static String files_dir;
    private static DeviceType dev_type = DeviceType.UNKNOWN;
    // Service state.
    private static String output_dir = DEFAULT_OUTPUT_DIR;
    private static String command;
    private static String su_path = DEFAULT_SU_PATH;

    // Native function signatures.
    private static native String getFBInfo(int type, String command);
    private static native int[] getFBPixels(int type, String command,
            int height, int width, int bpp, int stride,
            int[] offsets, int[] sizes);


    public static CompressionType getCompressionType() {
        return CompressionType.JPG_FAST;
    }

    public static String getSuPath(Context context) {
        return AndroSS.su_path;
    }

    public static String getParamString(Context context) {
        return getFBInfo(getDeviceType(context).ordinal(), command);
    }

    public static boolean canSu(Context context) {
        createExternalBinary(context);
        int ret = 1;
        try {
            ret =
                Runtime.getRuntime()
                    .exec(su_path + " -c " + context.getFilesDir().getAbsolutePath() + "/AndroSS")
                    .waitFor();
        } catch (InterruptedException ie) {
        } catch (IOException e) {}
        return ret == 0 ? true : false;
    }

    public static DeviceType getDeviceType(Context context) {
        if (AndroSS.dev_type == DeviceType.UNKNOWN) {
            Log.d(TAG, "Service: This is a regular device.");
            AndroSS.dev_type = DeviceType.GENERIC;
        }

        return AndroSS.dev_type;
    }
    
    public static boolean setOutputDir(Context context, String new_dir) {
        if (!new_dir.endsWith("/")) {
            new_dir += "/";
        }

        File f = new File(new_dir);
        f.mkdirs();
        if (f.canWrite()) {
            output_dir = new_dir;
            return true;
        } else {
            Log.d(TAG, "Service: Cannot write to requested output dir: " + new_dir);
            return false;
        }
    }
    
    public static boolean setSuPath(Context context, String new_su) {
        File f = new File(new_su);
        if (f.canRead()) {
            su_path = new_su;
            updateCommand(context);
            Log.d(TAG, "Service: Updated su path to: " + new_su);
            return true;
        } else {
            Log.d(TAG, "Service: su path appears invalid: " + new_su);
            return false;
        }
    }

    public AndroSS(Context context) {
        this.context = context;
        init();
    }

    // State control functions.

    private static void updateCommand(Context context) {
        AndroSS.command = AndroSS.su_path + " -c " + files_dir + "/AndroSS";
    }

    private boolean init() {
        // Configure necessary directories.
        AndroSS.setOutputDir(context, AndroSS.DEFAULT_OUTPUT_DIR);
        AndroSS.files_dir = context.getFilesDir().getAbsolutePath();

        String param_string;
        AndroSS.c_offsets = new int[4];
        AndroSS.c_sizes = new int[4];

        updateCommand(context);

        // Configure su.
        AndroSS.setSuPath(context, AndroSS.DEFAULT_SU_PATH);

        // Create the AndroSS external binary.
        AndroSS.createExternalBinary(context);

        Log.i(TAG,"getFBInfo "+ DeviceType.GENERIC.ordinal() + " / " + AndroSS.command);
        param_string = getFBInfo(DeviceType.GENERIC.ordinal(), AndroSS.command);

        // Parse screen info.
        if (param_string.equals("")) {
            Log.e(TAG,"Service: Got empty param string from native!");
            return false;
        }

        Log.d(TAG, "Service: Got framebuffer params: " + param_string);
        String[] params = param_string.split(" ");

        AndroSS.screen_width = Integer.parseInt(params[0]);
        AndroSS.screen_height = Integer.parseInt(params[1]);
        AndroSS.screen_depth = Integer.parseInt(params[2]);

        for (int color = 0; color < 4; ++color) {
            AndroSS.c_offsets[color] = Integer.parseInt(params[3 + (color * 2)]);
            AndroSS.c_sizes[color] = Integer.parseInt(params[4 + (color * 2)]);
        }

        AndroSS.fb_stride = Integer.parseInt(params[11]);

        return true;
    }

    // Actual screen-shooting functionality.
    private static void createExternalBinary(Context context) {
        try {
            FileOutputStream myfile = context.openFileOutput("AndroSS", Context.MODE_PRIVATE);
            myfile.write(Base64.decode(AndroSSNative.native64, Base64.DEFAULT));
            myfile.close();
            Runtime.getRuntime().exec("chmod 770 " + context.getFilesDir().getAbsolutePath()
                                          + "/AndroSS");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private boolean writeScreenshot(Bitmap bmp, String filename) {
        boolean success = false;

        File output = new File(AndroSS.output_dir + filename);
        try {
            // A little wasteful, maybe, but this avoids errors due to the
            // output dir not existing.
            output.getParentFile().mkdirs();
            FileOutputStream os = new FileOutputStream(output);
            switch (getCompressionType()) {
            case PNG:
                success = bmp.compress(Bitmap.CompressFormat.PNG, 0, os);
                break;
            case JPG_HQ:
                success = bmp.compress(Bitmap.CompressFormat.JPEG, 90, os);
                break;
            case JPG_FAST:
                success = bmp.compress(Bitmap.CompressFormat.JPEG, 40, os);
            }
            os.flush();
            os.close();
        } catch (FileNotFoundException fnfe) {
        } catch (IOException ioe) {}

        return success;
    }
    
    public Bitmap getScreenshot() {
        Log.d(TAG, "Service: Getting framebuffer pixels.");

        int bpp = AndroSS.screen_depth / 8;
        int bitmap_width = screen_width;
        int bitmap_height = screen_height;
        int rotation = -1;

        // First serious order of business is to get the pixels.
        int[] pixels = {0};
        String command = "";

        command = AndroSS.getSuPath(context) + " -c " + AndroSS.files_dir + "/AndroSS";
        pixels = getFBPixels(getDeviceType(context).ordinal(), command,
                    screen_height, screen_width, bpp, fb_stride,
                    c_offsets, c_sizes);

        if (pixels == null) {
            Log.d(TAG, "Service: pixels is null.");            
            return null;
        }

        Log.d(TAG, "Service: Creating bitmap.");
        Bitmap bmp_ss = Bitmap.createBitmap(
                bitmap_width,
                bitmap_height,
                Bitmap.Config.ARGB_8888);
        bmp_ss.setPixels(pixels, 0, screen_width,
                0, 0, screen_width, screen_height);

        Matrix rotator = new Matrix();

        // screen_{width,height} are applied before the rotate, so we don't
        // need to change them based on rotation.
        return Bitmap.createBitmap(bmp_ss, 0, 0, screen_width, screen_height, rotator, false);
    }
    
    public void takeScreenshot() {
        Bitmap bmp_ss = getScreenshot();

        // Build an intelligent filename, write out to file, and register with
        // the Android media services.
        String filename = "lastSS";
        switch (getCompressionType()) {
        case PNG:
            filename += ".png";
            break;
        case JPG_HQ:
        case JPG_FAST:
            filename += ".jpg";
            break;
        }
        boolean success = writeScreenshot(bmp_ss, filename);

        Log.d(TAG,
                "Service: Write to " + AndroSS.output_dir + filename + ": "
                + (success ? "succeeded" : "failed"));
    }
}
