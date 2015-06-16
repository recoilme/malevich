package org.freemp.malevich;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
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
public class Malevich extends ImageResizer{

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
        Bitmap onImageDecoded(Malevich malevich, String data, int reqWidth, int reqHeight, Bitmap bitmap);
    }

    private static final String TAG = "Malevich";
    private static final String IMAGE_CACHE_DIR = "images";
    private static final String HTTP_CACHE_DIR = "http";
    private static final int HTTP_CACHE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private static final int DISK_CACHE_INDEX = 0;

    private final Context context;
    private final boolean debug;
    private final int maxSize;
    private final ImageCache.ImageCacheParams cacheParams;
    private final File mHttpCacheDir;
    private final Object mHttpDiskCacheLock = new Object();
    private final Bitmap loadingImage;
    private final ErrorDecodingListener errorDecodingListener;

    private DiskLruCache mHttpDiskCache;
    private boolean mHttpDiskCacheStarting = true;
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
        // Set memory cache to 40% of app memory
        private float memCacheSizePercent = 0.4f;
        private Bitmap loadingImage;
        private ErrorDecodingListener errorDecodingListener;

        public Builder (Context contextContainer) {
            if (contextContainer == null) {
                throw new IllegalArgumentException("Context must not be null.");
            }
            context = contextContainer.getApplicationContext();

            // Default init

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

            // Malevich don't want work without memory cache, so init it
            cacheParams = new ImageCache.ImageCacheParams(context,null);
            cacheParams.setMemCacheSizePercent(memCacheSizePercent);

            loadingImage = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565);
        }

        public Builder debug (boolean debug) {
            this.debug = debug;
            return this;
        }

        public Builder maxSize (int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public Builder cacheDir (String cacheDir) {
            this.cacheParams = new ImageCache.ImageCacheParams(this.context,cacheDir);
            this.cacheParams.setMemCacheSizePercent(memCacheSizePercent);
            return this;
        }

        public Builder MemCacheSizePercent (float memCacheSizePercent) {
            this.memCacheSizePercent = memCacheSizePercent;
            this.cacheParams.setMemCacheSizePercent(memCacheSizePercent);
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
        mHttpCacheDir = ImageCache.getDiskCacheDir(context, HTTP_CACHE_DIR);
        loadingImage = builder.loadingImage;
        errorDecodingListener = builder.errorDecodingListener;

        setLoadingImage(loadingImage);
        addImageCache(cacheParams, debug);
    }

    public Malevich load (Object data) {
        this.data = data;
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

    public void into (ImageView imageView) {
        reqWidth = reqWidth == 0 ? maxSize : reqWidth;
        reqHeight = reqHeight == 0 ? maxSize : reqHeight;
        loadImage(data,imageView,reqWidth,reqHeight, imageDecodedListener);
    }

    public boolean isDebug() {
        return debug;
    }

    /**
     * The main process method, which will be called by the ImageWorker in the AsyncTask background
     * thread.
     *
     * @param data The data to load the bitmap, in this case, a regular http URL
     * @return The downloaded and resized bitmap
     */
    private Bitmap processBitmap(String data,int reqWidth, int reqHeight, ImageDecodedListener imageDecodedListener) {
        if (debug) {
            Log.d(TAG, "processBitmap - " + data);
        }

        final String key = ImageCache.hashKeyForDisk(data);
        FileDescriptor fileDescriptor = null;
        FileInputStream fileInputStream = null;
        DiskLruCache.Snapshot snapshot;
        StringBuffer error = new StringBuffer();

        synchronized (mHttpDiskCacheLock) {
            // Wait for disk cache to initialize
            while (mHttpDiskCacheStarting) {
                try {
                    mHttpDiskCacheLock.wait();
                } catch (InterruptedException e) {
                    error.append(e.toString());
                }
            }

            if (mHttpDiskCache != null) {
                try {
                    if (new File(data).exists()) {
                        fileInputStream = new FileInputStream(new File(data));
                        fileDescriptor = fileInputStream.getFD();
                    }
                    else {
                        snapshot = mHttpDiskCache.get(key);
                        if (snapshot == null) {
                            if (debug) {
                                Log.d(TAG, "processBitmap, not found in http cache, downloading...");
                            }
                            DiskLruCache.Editor editor = mHttpDiskCache.edit(key);
                            if (editor != null) {
                                final String result = downloadUrlToStream(data,
                                        editor.newOutputStream(DISK_CACHE_INDEX));
                                if (result.equals("")) {
                                    editor.commit();
                                } else {
                                    error.append(result);
                                    editor.abort();
                                }
                            }
                            snapshot = mHttpDiskCache.get(key);
                        }
                        if (snapshot != null) {
                            fileInputStream =
                                    (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
                            fileDescriptor = fileInputStream.getFD();
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "processBitmap - " + e);
                    error.append(e.toString());
                } catch (IllegalStateException e) {
                    Log.e(TAG, "processBitmap - " + e);
                    error.append(e.toString());
                } finally {
                    if (fileDescriptor == null && fileInputStream != null) {
                        try {
                            fileInputStream.close();
                        } catch (IOException e) {}
                    }
                }
            }
        }

        Bitmap bitmap = null;
        if (fileDescriptor != null) {
            bitmap = decodeSampledBitmapFromDescriptor(fileDescriptor, reqWidth,
                    reqHeight, getImageCache());

            if (imageDecodedListener != null) {
                bitmap = imageDecodedListener.onImageDecoded(this, data, reqWidth, reqHeight, bitmap);
            }
        }
        else {
            if (errorDecodingListener != null) {
                errorDecodingListener.onImageDecodeError(this, data, error.toString());
            }
        }
        if (fileInputStream != null) {
            try {
                fileInputStream.close();
            } catch (IOException e) {}
        }
        return bitmap;
    }

    @Override
    protected Bitmap processBitmap(Object data, int reqWidth, int reqHeight, ImageDecodedListener imageDecodedListener) {
        return processBitmap(String.valueOf(data),reqWidth,reqHeight, imageDecodedListener);
    }

    /**
     * Download a bitmap from a URL and write the content to an output stream.
     *
     * @param urlString The URL to fetch
     * @return true if successful, false otherwise
     */
    public String downloadUrlToStream(String urlString, OutputStream outputStream) {
        disableConnectionReuseIfNecessary();
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        String error = "";
        try {
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);

            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }
            return error;
        } catch (final IOException e) {
            Log.e(TAG, "Error in downloadBitmap - " + e);
            error = "Error in downloadBitmap - " + e.toString();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            try {
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
            } catch (final IOException e) {}
        }
        return error;
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

    //work with cache
    @Override
    protected void initDiskCacheInternal() {
        super.initDiskCacheInternal();
        initHttpDiskCache();
    }

    private void initHttpDiskCache() {
        if (!mHttpCacheDir.exists()) {
            mHttpCacheDir.mkdirs();
        }
        synchronized (mHttpDiskCacheLock) {
            if (ImageCache.getUsableSpace(mHttpCacheDir) > HTTP_CACHE_SIZE) {
                try {
                    mHttpDiskCache = DiskLruCache.open(mHttpCacheDir, 1, 1, HTTP_CACHE_SIZE);
                    if (debug) {
                        Log.d(TAG, "HTTP cache initialized");
                    }
                } catch (IOException e) {
                    mHttpDiskCache = null;
                }
            }
            mHttpDiskCacheStarting = false;
            mHttpDiskCacheLock.notifyAll();
        }
    }

    @Override
    protected void clearCacheInternal() {
        super.clearCacheInternal();
        synchronized (mHttpDiskCacheLock) {
            if (mHttpDiskCache != null && !mHttpDiskCache.isClosed()) {
                try {
                    mHttpDiskCache.delete();
                    if (debug) {
                        Log.d(TAG, "HTTP cache cleared");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "clearCacheInternal - " + e);
                }
                mHttpDiskCache = null;
                mHttpDiskCacheStarting = true;
                initHttpDiskCache();
            }
        }
    }

    @Override
    protected void flushCacheInternal() {
        super.flushCacheInternal();
        synchronized (mHttpDiskCacheLock) {
            if (mHttpDiskCache != null) {
                try {
                    mHttpDiskCache.flush();
                    if (debug) {
                        Log.d(TAG, "HTTP cache flushed");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "flush - " + e);
                }
            }
        }
    }

    @Override
    protected void closeCacheInternal() {
        super.closeCacheInternal();
        synchronized (mHttpDiskCacheLock) {
            if (mHttpDiskCache != null) {
                try {
                    if (!mHttpDiskCache.isClosed()) {
                        mHttpDiskCache.close();
                        mHttpDiskCache = null;
                        if (debug) {
                            Log.d(TAG, "HTTP cache closed");
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "closeCacheInternal - " + e);
                }
            }
        }
    }

    // Some useful image utils

    /**
     * Creates a centered bitmap of the desired size.
     *
     * @param bitmap original bitmap source
     * @param reqWidth targeted width
     * @param reqHeight targeted height
     */
    public Bitmap extractThumbnail(Bitmap bitmap, int reqWidth, int reqHeight) {
        return ThumbnailUtils.extractThumbnail(bitmap, reqWidth, reqHeight);
    }

    /**
     * Creates a circle bitmap
     *
     * @param bitmap original bitmap source
     */
    public Bitmap getCircleBitmap(Bitmap bitmap) {
        final Bitmap output = Bitmap.createBitmap(bitmap.getWidth(),
                bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(output);

        final Paint paint = new Paint();
        final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        final RectF rectF = new RectF(rect);

        paint.setAntiAlias(true);
        canvas.drawARGB(0, 0, 0, 0);
        canvas.drawOval(rectF, paint);

        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(bitmap, rect, rect, paint);

        bitmap.recycle();
        return output;
    }

    // Get squared bitmap and transform it to circle
    public Bitmap getRoundedBitmap(Bitmap bitmap,int reqWidth) {
        return getCircleBitmap(extractThumbnail(bitmap,reqWidth,reqWidth));
    }
}
