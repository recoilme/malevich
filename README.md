# malevich
Android displaying bitmaps library

This library is just wrapper for Google tutorial Loading Large Bitmaps Efficiently:
--------------
http://developer.android.com/intl/ru/training/displaying-bitmaps/load-bitmap.html

Example of usage:
--------------
https://github.com/recoilme/android-DisplayingBitmaps

How to use:
--------------
```
// init
Malevich malevich = new Malevich.Builder(this).build();


// use
malevich.load(mImageUrl).into(mImageView);

```
### Whats all!

Advansed usage:
--------------


Memory and disk caching params
```
ImageCache.ImageCacheParams cacheParams = new ImageCache.ImageCacheParams(this, "dir");
        cacheParams.memoryCacheEnabled = true; //Enable memory cache
        cacheParams.setMemCacheSizePercent(0.4f);  //Percent of available memory for cache
        cacheParams.compressQuality = 90; // Compress quality
        cacheParams.compressFormat = Bitmap.CompressFormat.PNG; // Compress format
        cacheParams.diskCacheEnabled = true; // Use disk cache
        cacheParams.diskCacheSize = 10485760; // Disk cache size
```

Malevich Builder
---------
```
malevich = new Malevich.Builder(this)
        .debug(true) // write log
        .maxSize(1024) // max size of image in px
        .LoadingImage(R.drawable.some) // preloader image or recource
        .CacheParams(casheParams) // custom cache
        .build();
```
Loading image
--------------
```
malevich.load(some).into(ImageView);
```
you may load:
1. Bitmap
2. BitmapDrawable
3. Resource id
4. HttpUrl

Transform image after loading with prebuild utils or custom method
--------------
```
malevich.load(url).width(mItemHeight).height(mItemHeight).imageDecodedListener(new Malevich.ImageDecodedListener() {
                    @Override
                    public Bitmap onImageDecoded(String data, int reqWidth, int reqHeight, Bitmap bitmap) {

                        // Get squared bitmap and transform it to circle
                        return Malevich.Utils.getSquaredCircleBitmap(bitmap,reqWidth);
                    }
                }).into(imageView);
```
Pause loading on scroll
----------------
```
mGridView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView absListView, int scrollState) {
                // Pause fetcher to ensure smoother scrolling when flinging
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_FLING) {
                    // Pause image loading on scroll to help with performance
                    malevich.setPauseWork(true);
                } else {
                    malevich.setPauseWork(false);
                }
            }

            @Override
            public void onScroll(AbsListView absListView, int firstVisibleItem,
                    int visibleItemCount, int totalItemCount) {
            }
        });
```

Canсel and pausing tasks
-----------------
```
    @Override
    public void onResume() {
        super.onResume();
        malevich.setExitTasksEarly(false);
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public void onPause() {
        super.onPause();
        malevich.setPauseWork(false);
        malevich.setExitTasksEarly(true);
        malevich.flushCache();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        malevich.closeCache();
    }
```


