package org.freemp.malevich;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Created by recoilme on 12/06/15.
 */
public class Malevich extends ImageWorker{

    /** Callbacks for Malevich events. */
    public interface ErrorDecodingListener {

        /**
         * Invoked when an image has failed to load. This is useful for reporting image failures to a
         * remote analytics service, for example.
         */
        void onImageDecodeError(Malevich malevich, String data, String error);
    }

    public interface ImageDecodedListener {
        /**
         * Invoked when an image has  load. This is useful for transformayion image
         */
        Bitmap onImageDecoded(String data, int reqWidth, int reqHeight, Bitmap bitmap);
    }

    private static final String TAG = "Malevich";

    private final Context context;
    private final boolean debug;
    private final int maxSize;
    private final ImageCache.ImageCacheParams cacheParams;
    private final Bitmap loadingImage;
    private final ErrorDecodingListener errorDecodingListener;

    private Object data = null;
    private int reqWidth = 0;
    private int reqHeight = 0;
    private ImageDecodedListener imageDecodedListener;

    public static class Builder {
        // required params
        private final Context context;
        // not requried params
        private boolean debug = false;
        private int maxSize;
        private ImageCache.ImageCacheParams cacheParams;
        private Bitmap loadingImage;
        private ErrorDecodingListener errorDecodingListener;

        public Builder (Context contextContainer) {
            if (contextContainer == null) {
                throw new IllegalArgumentException("Context must not be null.");
            }
            context = contextContainer.getApplicationContext();

            // Default params init

            // Fetch screen height and width, to use as our max size when loading images as this
            // activity runs full screen
            final DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
            final int height = displayMetrics.heightPixels;
            final int width = displayMetrics.widthPixels;

            // For most apps you may use half of the longest width to resize our images. As the
            // image scaling ensures the image is larger than this, we should be left with a
            // resolution that is appropriate for both portrait and landscape. For best image quality
            // don't divide by 2, but this will use more memory and require a larger memory
            // cache. If you set this value more then 2048 you may have problems with renderings

            this.maxSize = (height > width ? height : width);

            // Set default folder for image cache
            cacheParams = new ImageCache.ImageCacheParams(context,"images");

            // Set memory cache to 40% of app memory
            cacheParams.setMemCacheSizePercent(0.4f);

            // Create transparent bitmap for default image loading image
            int[] colors = new int[1];
            colors[0] = Color.argb(0, 0, 0, 0);
            loadingImage = Bitmap.createBitmap(colors, 1, 1, Bitmap.Config.ARGB_8888);
        }

        public Builder debug (boolean debug) {
            this.debug = debug;
            return this;
        }

        public Builder maxSize (int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public Builder LoadingImage (Bitmap loadingImage) {
            this.loadingImage = loadingImage;
            return this;
        }

        public Builder LoadingImage (int resId) {
            this.loadingImage = BitmapFactory.decodeResource(context.getResources(), resId);
            return this;
        }

        public Builder CacheParams (ImageCache.ImageCacheParams cacheParams) {
            this.cacheParams = cacheParams;
            return this;
        }

        /** Specify a listener for interesting events. */
        public Builder globalListener(ErrorDecodingListener errorDecodingListener) {
            if (errorDecodingListener == null) {
                throw new IllegalArgumentException("Listener must not be null.");
            }
            if (this.errorDecodingListener != null) {
                throw new IllegalStateException("Listener already set.");
            }
            this.errorDecodingListener = errorDecodingListener;
            return this;
        }

        public Malevich build() {
            return new Malevich(this);
        }
    }

    // Malevich init
    private Malevich (Builder builder) {
        super(builder.context, builder.debug);
        context = builder.context;
        debug = builder.debug;
        maxSize = builder.maxSize;
        cacheParams = builder.cacheParams;
        loadingImage = builder.loadingImage;
        errorDecodingListener = builder.errorDecodingListener;

        // TODO reorginize it, loading image may change?
        setLoadingImage(loadingImage);
        addImageCache(cacheParams);
    }

    // This is starting method for every image loading
    public Malevich load (Object data) {
        this.data = data;
        // clear params to default for every new load
        this.reqWidth = maxSize;
        this.reqHeight = maxSize;
        this.imageDecodedListener = null;
        return this;
    }

    public Malevich width (int reqWidth) {
        this.reqWidth = reqWidth;
        return this;
    }

    public Malevich height (int reqHeight) {
        this.reqHeight = reqHeight;
        return this;
    }

    public Malevich imageDecodedListener(ImageDecodedListener imageDecodedListener) {
        this.imageDecodedListener = imageDecodedListener;
        return this;
    }

    // This is final method for every image loading
    public void into (ImageView imageView) {
        loadImage(data, imageView, reqWidth, reqHeight, imageDecodedListener);
    }


    // Built in Malevich Utils

    public enum Utils {;

        public static boolean hasHoneycomb() {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB;
        }

        public static boolean hasHoneycombMR1() {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1;
        }

        public static boolean hasJellyBean() {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN;
        }

        public static boolean hasKitKat() {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
        }

        /**
         * Workaround for bug pre-Froyo, see here for more info:
         * http://android-developers.blogspot.com/2011/09/androids-http-clients.html
         */
        public static void disableConnectionReuseIfNecessary() {
            // HTTP connection reuse which was buggy pre-froyo
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.FROYO) {
                System.setProperty("http.keepAlive", "false");
            }
        }

        /**
         * Decode and sample down a bitmap from resources to the requested width and height.
         *
         * @param res The resources object containing the image data
         * @param resId The resource id of the image data
         * @param reqWidth The requested width of the resulting bitmap
         * @param reqHeight The requested height of the resulting bitmap
         * @param cache The ImageCache used to find candidate bitmaps for use with inBitmap
         * @return A bitmap sampled down from the original with the same aspect ratio and dimensions
         *         that are equal to or greater than the requested width and height
         */
        public static Bitmap decodeSampledBitmapFromResource(Resources res, int resId,
                                                             int reqWidth, int reqHeight, ImageCache cache, boolean debug) {
            if (debug) {
                Log.d(TAG, "decodeSampledBitmapFromResource - " + resId);
            }
            // BEGIN_INCLUDE (read_bitmap_dimensions)
            // First decode with inJustDecodeBounds=true to check dimensions
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(res, resId, options);

            // Calculate inSampleSize
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            // END_INCLUDE (read_bitmap_dimensions)

            // If we're running on Honeycomb or newer, try to use inBitmap
            if (hasHoneycomb()) {
                addInBitmapOptions(options, cache);
            }

            // Decode bitmap with inSampleSize set
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeResource(res, resId, options);
        }

        /**
         * Decode and sample down a bitmap from a file to the requested width and height.
         *
         * @param filename The full path of the file to decode
         * @param reqWidth The requested width of the resulting bitmap
         * @param reqHeight The requested height of the resulting bitmap
         * @param cache The ImageCache used to find candidate bitmaps for use with inBitmap
         * @return A bitmap sampled down from the original with the same aspect ratio and dimensions
         *         that are equal to or greater than the requested width and height
         */
        public static Bitmap decodeSampledBitmapFromFile(String filename,
                                                         int reqWidth, int reqHeight, ImageCache cache) {

            // First decode with inJustDecodeBounds=true to check dimensions
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(filename, options);

            // Calculate inSampleSize
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

            // If we're running on Honeycomb or newer, try to use inBitmap
            if (hasHoneycomb()) {
                addInBitmapOptions(options, cache);
            }

            // Decode bitmap with inSampleSize set
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(filename, options);
        }

        /**
         * Decode and sample down a bitmap from a file input stream to the requested width and height.
         *
         * @param fileDescriptor The file descriptor to read from
         * @param reqWidth The requested width of the resulting bitmap
         * @param reqHeight The requested height of the resulting bitmap
         * @param cache The ImageCache used to find candidate bitmaps for use with inBitmap
         * @return A bitmap sampled down from the original with the same aspect ratio and dimensions
         *         that are equal to or greater than the requested width and height
         */
        public static Bitmap decodeSampledBitmapFromDescriptor(
                FileDescriptor fileDescriptor, int reqWidth, int reqHeight, ImageCache cache) {

            // First decode with inJustDecodeBounds=true to check dimensions
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options);

            // Calculate inSampleSize
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

            // Decode bitmap with inSampleSize set
            options.inJustDecodeBounds = false;

            // If we're running on Honeycomb or newer, try to use inBitmap
            if (Malevich.Utils.hasHoneycomb()) {
                addInBitmapOptions(options, cache);
            }

            return BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options);
        }

        @TargetApi(Build.VERSION_CODES.HONEYCOMB)
        private static void addInBitmapOptions(BitmapFactory.Options options, ImageCache cache) {
            //BEGIN_INCLUDE(add_bitmap_options)
            // inBitmap only works with mutable bitmaps so force the decoder to
            // return mutable bitmaps.
            options.inMutable = true;

            if (cache != null) {
                // Try and find a bitmap to use for inBitmap
                Bitmap inBitmap = cache.getBitmapFromReusableSet(options);

                if (inBitmap != null) {
                    options.inBitmap = inBitmap;
                }
            }
            //END_INCLUDE(add_bitmap_options)
        }

        /**
         * Calculate an inSampleSize for use in a {@link BitmapFactory.Options} object when decoding
         * bitmaps using the decode* methods from {@link BitmapFactory}. This implementation calculates
         * the closest inSampleSize that is a power of 2 and will result in the final decoded bitmap
         * having a width and height equal to or larger than the requested width and height.
         *
         * @param options An options object with out* params already populated (run through a decode*
         *            method with inJustDecodeBounds==true
         * @param reqWidth The requested width of the resulting bitmap
         * @param reqHeight The requested height of the resulting bitmap
         * @return The value to be used for inSampleSize
         */
        public static int calculateInSampleSize(BitmapFactory.Options options,
                                                int reqWidth, int reqHeight) {
            // BEGIN_INCLUDE (calculate_sample_size)
            // Raw height and width of image
            final int height = options.outHeight;
            final int width = options.outWidth;

            int inSampleSize = 1;
            // People are strange, so take 2/3 required size, for optimal memory usage, is it dirty?
            reqHeight = (int) (reqHeight * 0.6f);
            reqWidth = (int) (reqWidth * 0.6f);
            if (height > reqHeight || width > reqWidth) {

                final int halfHeight = height / 2;
                final int halfWidth = width / 2;

                // Calculate the largest inSampleSize value that is a power of 2 and keeps both
                // height and width larger than the requested height and width.
                while ((halfHeight / inSampleSize) > reqHeight
                        && (halfWidth / inSampleSize) > reqWidth) {
                    inSampleSize *= 2;
                }
                // wtf, Romain?

                // TODO test panorams

                // This offers some additional logic in case the image has a strange
                // aspect ratio. For example, a panorama may have a much larger
                // width than height. In these cases the total pixels might still
                // end up being too large to fit comfortably in memory, so we should
                // be more aggressive with sample down the image (=larger inSampleSize).
                /*
                long totalPixels = width * height / inSampleSize;

                // Anything more than 2x the requested pixels we'll sample down further
                final long totalReqPixelsCap = reqWidth * reqHeight * 2;

                while (totalPixels > totalReqPixelsCap) {
                    inSampleSize *= 2;
                    totalPixels /= 2;
                }
                */
            }
            return inSampleSize;
            // END_INCLUDE (calculate_sample_size)
        }

        // Some useful image utils

        /**
         * Creates a centered bitmap of the desired size.
         * I am not sure in this code provided by google
         *
         * @param bitmap original bitmap source
         * @param reqWidth targeted width
         * @param reqHeight targeted height
         */
        public static Bitmap extractThumbnail(Bitmap bitmap, int reqWidth, int reqHeight) {
            return ThumbnailUtils.extractThumbnail(bitmap, reqWidth, reqHeight);
        }

        /**
         * Creates a circle bitmap
         *
         * @param bitmap original bitmap source
         */
        public static Bitmap getCircleBitmap(Bitmap bitmap) {
            final Bitmap output = Bitmap.createBitmap(bitmap.getWidth(),
                    bitmap.getHeight(), Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(output);

            final Paint paint = new Paint();
            final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            final RectF rectF = new RectF(rect);
            final int color = Color.RED;

            paint.setAntiAlias(true);
            canvas.drawARGB(0, 0, 0, 0);
            paint.setColor(color);
            canvas.drawOval(rectF, paint);

            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(bitmap, rect, rect, paint);

            bitmap.recycle();
            return output;
        }

        // Get squared bitmap and transform it to circle
        public static Bitmap getSquaredCircleBitmap(Bitmap bitmap,int reqWidth) {
            return getCircleBitmap(extractThumbnail(bitmap,reqWidth,reqWidth));
        }

        // Draw circle bitmap with text inside
        public static Bitmap drawTextInCircle(int diametr, String txt, int color, float textSize) {
            Bitmap b = Bitmap.createBitmap(diametr, diametr, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(b);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(diametr / 2, diametr / 2, diametr / 2, paint);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.parseColor("#FFFFFF"));
            paint.setTextSize(textSize);
            canvas.drawText("" + txt, diametr / 2, diametr / 2 + (paint.getTextSize() / 3), paint);
            return b;
        }
    }
}
